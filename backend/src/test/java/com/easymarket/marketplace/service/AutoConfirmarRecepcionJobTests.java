package com.easymarket.marketplace.service;

import com.easymarket.marketplace.model.EstadoTransaccion;
import com.easymarket.marketplace.model.MovimientoSaldo;
import com.easymarket.marketplace.model.Notificacion;
import com.easymarket.marketplace.model.Publicacion;
import com.easymarket.marketplace.model.Transaccion;
import com.easymarket.marketplace.model.TransaccionEvento;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.MovimientoSaldoRepository;
import com.easymarket.marketplace.repository.TransaccionEventoRepository;
import com.easymarket.marketplace.repository.TransaccionRepository;
import com.easymarket.marketplace.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias de {@link AutoConfirmarRecepcionJob} (Story 6e).
 *
 * <p>Verifican que la auto-confirmación de una transacción {@code entregado} vencida transiciona a
 * {@code recibido_sin_respuesta}, acredita el snapshot entero al vendedor, persiste su movimiento
 * append-only y el evento de sistema, y —desde PHA12TSK03, recuperación de PHA09TSK05— emite la
 * notificación accionable COMPRA_CONFIRMADA al comprador y al vendedor con los mensajes dirigidos
 * ("tu compra"/"tu venta") que la UI enruta, todo dentro de la misma transacción del job. Cuando
 * no hay transacciones vencidas no se emite ninguna notificación. Los efectos concurrentes y de
 * bloqueo {@code FOR UPDATE SKIP LOCKED} se cubren en
 * {@code AutoConfirmarRecepcionJobIntegrationTests} contra PostgreSQL real.</p>
 */
@ExtendWith(MockitoExtension.class)
class AutoConfirmarRecepcionJobTests {

    private static final Long TRANSACCION_ID = 100L;
    private static final Long COMPRADOR_ID = 20L;
    private static final Long VENDEDOR_ID = 10L;
    private static final long PRECIO_SNAPSHOT = 12_345L;

    @Mock
    private TransaccionRepository transaccionRepository;

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private MovimientoSaldoRepository movimientoSaldoRepository;

    @Mock
    private TransaccionEventoRepository transaccionEventoRepository;

    @Mock
    private NotificacionService notificacionService;

    /**
     * Configura el mock de {@link NotificacionService} en modo leniente para que responda a cada
     * emisión accionable construyendo la instancia real de {@link Notificacion} con los argumentos
     * recibidos (patrón de PHA09TSK05-L02). Es leniente porque el escenario sin transacciones
     * vencidas nunca llega a emitir notificaciones y, sin {@code lenient()}, Mockito reportaría
     * stubbings innecesarios para esa prueba.
     */
    @BeforeEach
    void setUp() {
        lenient().when(notificacionService.crearNotificacionUsuario(any(), any(), any(), any(), any()))
            .thenAnswer(invocation -> new Notificacion(
                invocation.getArgument(0),
                invocation.getArgument(3),
                invocation.getArgument(2),
                invocation.getArgument(1),
                invocation.getArgument(4)));
    }

    /**
     * Verifica que una transacción entregada hace más de 48 horas se auto-confirma una sola vez:
     * acredita saldo, persiste movimiento y evento de sistema, transiciona a
     * {@code recibido_sin_respuesta} y emite COMPRA_CONFIRMADA al comprador y al vendedor.
     */
    @Test
    @DisplayName("Auto-confirma la entrega vencida y notifica COMPRA_CONFIRMADA a ambas partes")
    void ejecutar_TransaccionEntregadaHace49Horas_TransicionaAcreditaYNotificaAmbasPartes() {
        Transaccion transaccion = transaccionEntregadaHace49Horas();
        when(transaccionRepository.findEntregadasVencidasForUpdateSkipLocked(any(ZonedDateTime.class)))
            .thenReturn(List.of(transaccion));
        when(transaccionRepository.save(transaccion)).thenReturn(transaccion);

        autoConfirmarRecepcionJob().ejecutar();

        assertThat(transaccion.getEstado()).isEqualTo(EstadoTransaccion.RECIBIDO_SIN_RESPUESTA);
        verify(usuarioRepository).incrementarSaldoDisponible(VENDEDOR_ID, PRECIO_SNAPSHOT);
        ArgumentCaptor<MovimientoSaldo> movimientoCaptor = ArgumentCaptor.forClass(MovimientoSaldo.class);
        verify(movimientoSaldoRepository).save(movimientoCaptor.capture());
        MovimientoSaldo movimiento = movimientoCaptor.getValue();
        assertThat(movimiento.getTransaccion()).isSameAs(transaccion);
        assertThat(movimiento.getVendedor()).isSameAs(transaccion.getPublicacion().getUsuario());
        assertThat(movimiento.getMonto()).isEqualTo(PRECIO_SNAPSHOT);
        ArgumentCaptor<TransaccionEvento> eventoCaptor = ArgumentCaptor.forClass(TransaccionEvento.class);
        verify(transaccionEventoRepository).save(eventoCaptor.capture());
        TransaccionEvento evento = eventoCaptor.getValue();
        assertThat(evento.getActor()).isNull();
        assertThat(evento.getEstadoOrigen()).isEqualTo(EstadoTransaccion.ENTREGADO);
        assertThat(evento.getEstadoDestino()).isEqualTo(EstadoTransaccion.RECIBIDO_SIN_RESPUESTA);
        verify(notificacionService).crearNotificacionUsuario(same(transaccion.getComprador()),
            eq("COMPRA_CONFIRMADA"), contains("tu compra"), same(transaccion), any(ZonedDateTime.class));
        verify(notificacionService).crearNotificacionUsuario(same(transaccion.getPublicacion().getUsuario()),
            eq("COMPRA_CONFIRMADA"), contains("tu venta"), same(transaccion), any(ZonedDateTime.class));
    }

    /**
     * Verifica que una ejecución sin transacciones vencidas no acredita saldos ni emite
     * notificaciones.
     */
    @Test
    @DisplayName("Sin transacciones vencidas no acredita ni emite notificaciones")
    void ejecutar_SinTransaccionesVencidas_NoEmiteNotificaciones() {
        when(transaccionRepository.findEntregadasVencidasForUpdateSkipLocked(any(ZonedDateTime.class)))
            .thenReturn(List.of());

        autoConfirmarRecepcionJob().ejecutar();

        verify(transaccionRepository, never()).save(any());
        verify(usuarioRepository, never()).incrementarSaldoDisponible(any(), anyLong());
        verifyNoInteractions(notificacionService);
    }

    /**
     * Construye el job bajo prueba con los mocks de esta clase.
     *
     * @return job configurado para verificación unitaria
     */
    private AutoConfirmarRecepcionJob autoConfirmarRecepcionJob() {
        return new AutoConfirmarRecepcionJob(transaccionRepository, usuarioRepository,
            movimientoSaldoRepository, transaccionEventoRepository, notificacionService);
    }

    /**
     * Crea una transacción entregada hace 49 horas, fuera de la ventana de acción del comprador.
     *
     * @return transacción elegible para la auto-confirmación automática
     */
    private Transaccion transaccionEntregadaHace49Horas() {
        Usuario comprador = usuario(COMPRADOR_ID);
        Usuario vendedor = usuario(VENDEDOR_ID);
        Publicacion publicacion = new Publicacion();
        publicacion.setUsuario(vendedor);

        Transaccion transaccion = new Transaccion();
        transaccion.setId(TRANSACCION_ID);
        transaccion.setComprador(comprador);
        transaccion.setPublicacion(publicacion);
        transaccion.setEstado(EstadoTransaccion.ENTREGADO);
        transaccion.setPrecioSnapshot(PRECIO_SNAPSHOT);
        transaccion.setFechaEntregado(ZonedDateTime.now().minusHours(49));
        return transaccion;
    }

    /**
     * Crea un usuario persistente simulado para la prueba.
     *
     * @param id ID persistente a asignar al usuario
     * @return usuario configurado con el ID indicado
     */
    private Usuario usuario(Long id) {
        Usuario usuario = new Usuario();
        usuario.setId(id);
        return usuario;
    }
}
