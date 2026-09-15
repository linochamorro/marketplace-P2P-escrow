package com.easymarket.marketplace.service;

import com.easymarket.marketplace.exception.ActorNoEsCompradorTransaccionException;
import com.easymarket.marketplace.exception.PlazoConfirmacionRecepcionExcedidoException;
import com.easymarket.marketplace.exception.TransicionEstadoTransaccionInvalidaException;
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
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias del servicio de confirmación explícita de recepción de la Story 6c.
 *
 * <p>Comprueba autorización del comprador, idempotencia por estado, plazo contado desde
 * {@code fecha_entregado}, crédito de saldo y los dos registros append-only. La frontera de 48
 * horas se interpreta como inclusiva: exactamente 48 horas después de la entrega todavía está
 * dentro de plazo; solo un instante posterior se rechaza. Desde PHA12TSK03 (recuperación de
 * PHA09TSK05) verifica además que la confirmación exitosa emite, dentro de la misma transacción,
 * la notificación accionable COMPRA_CONFIRMADA al comprador y al vendedor con los mensajes
 * dirigidos ("tu compra"/"tu venta") que la UI enruta, y que ningún rechazo temprano emite
 * notificaciones.</p>
 */
@ExtendWith(MockitoExtension.class)
class ConfirmacionRecepcionServiceTests {

    private static final Long TRANSACCION_ID = 100L;
    private static final Long COMPRADOR_ID = 20L;
    private static final Long VENDEDOR_ID = 10L;
    private static final Long ADMIN_ID = 30L;
    private static final ZonedDateTime AHORA = ZonedDateTime.of(2026, 8, 6, 12, 0, 0, 0, ZoneOffset.UTC);

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
     * recibidos (patrón de PHA09TSK05-L02). Es leniente porque los tests de rechazo temprano nunca
     * llegan a emitir notificaciones y, sin {@code lenient()}, Mockito reportaría stubbings
     * innecesarios para esas pruebas.
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
     * Rechaza al vendedor como actor aunque la transacción esté entregada y dentro del plazo.
     */
    @Test
    @DisplayName("Rechaza confirmar recepción si el actor no es el comprador")
    void confirmarRecepcion_ActorNoEsComprador_LanzaExcepcionEspecificaSinEscrituras() {
        Transaccion transaccion = transaccionEntregada(AHORA.minusHours(1));
        when(transaccionRepository.findByIdForUpdate(TRANSACCION_ID)).thenReturn(Optional.of(transaccion));

        assertThatThrownBy(() -> service().confirmarRecepcion(TRANSACCION_ID, VENDEDOR_ID))
            .isInstanceOf(ActorNoEsCompradorTransaccionException.class);

        verificarSinEscrituras();
    }

    /**
     * Rechaza al administrador como actor, pues el rol no sustituye la identidad del comprador.
     */
    @Test
    @DisplayName("Rechaza confirmar recepción si el actor es administrador y no comprador")
    void confirmarRecepcion_ActorAdminNoEsComprador_LanzaExcepcionEspecificaSinEscrituras() {
        Transaccion transaccion = transaccionEntregada(AHORA.minusHours(1));
        when(transaccionRepository.findByIdForUpdate(TRANSACCION_ID)).thenReturn(Optional.of(transaccion));

        assertThatThrownBy(() -> service().confirmarRecepcion(TRANSACCION_ID, ADMIN_ID))
            .isInstanceOf(ActorNoEsCompradorTransaccionException.class);

        verificarSinEscrituras();
    }

    /**
     * Rechaza una segunda confirmación porque el estado recibido no puede transicionar otra vez.
     */
    @Test
    @DisplayName("Rechaza confirmar recepción fuera de entregado sin repetir escrituras")
    void confirmarRecepcion_EstadoRecibido_LanzaExcepcionEspecificaSinEscrituras() {
        Transaccion transaccion = transaccionEntregada(AHORA.minusHours(1));
        transaccion.setEstado(EstadoTransaccion.RECIBIDO);
        when(transaccionRepository.findByIdForUpdate(TRANSACCION_ID)).thenReturn(Optional.of(transaccion));

        assertThatThrownBy(() -> service().confirmarRecepcion(TRANSACCION_ID, COMPRADOR_ID))
            .isInstanceOf(TransicionEstadoTransaccionInvalidaException.class);

        verificarSinEscrituras();
    }

    /**
     * Rechaza una confirmación posterior al plazo sin acreditar ni auditar una transición inexistente.
     */
    @Test
    @DisplayName("Rechaza confirmar recepción después de 48 horas sin escrituras")
    void confirmarRecepcion_PlazoExcedido_LanzaExcepcionEspecificaSinEscrituras() {
        Transaccion transaccion = transaccionEntregada(AHORA.minusHours(48).minusNanos(1));
        when(transaccionRepository.findByIdForUpdate(TRANSACCION_ID)).thenReturn(Optional.of(transaccion));

        assertThatThrownBy(() -> service().confirmarRecepcion(TRANSACCION_ID, COMPRADOR_ID))
            .isInstanceOf(PlazoConfirmacionRecepcionExcedidoException.class);

        verificarSinEscrituras();
    }

    /**
     * Acepta la frontera inclusiva exacta de 48 horas y acredita el saldo con el snapshot entero.
     */
    @Test
    @DisplayName("Confirma exactamente a las 48 horas, acredita el snapshot y no modifica stock")
    void confirmarRecepcion_EnFronteraDe48Horas_TransicionaAcreditaYAuditaSinModificarStock() {
        Transaccion transaccion = transaccionEntregada(AHORA.minusHours(48));
        clearInvocations(transaccion.getPublicacion());
        when(transaccionRepository.findByIdForUpdate(TRANSACCION_ID)).thenReturn(Optional.of(transaccion));
        when(transaccionRepository.save(transaccion)).thenReturn(transaccion);

        Transaccion resultado = service().confirmarRecepcion(TRANSACCION_ID, COMPRADOR_ID);

        assertThat(resultado.getEstado()).isEqualTo(EstadoTransaccion.RECIBIDO);
        verify(usuarioRepository).incrementarSaldoDisponible(VENDEDOR_ID, 12_345L);
        ArgumentCaptor<MovimientoSaldo> movimientoCaptor = ArgumentCaptor.forClass(MovimientoSaldo.class);
        verify(movimientoSaldoRepository).save(movimientoCaptor.capture());
        MovimientoSaldo movimiento = movimientoCaptor.getValue();
        assertThat(movimiento.getTransaccion()).isSameAs(resultado);
        assertThat(movimiento.getVendedor()).isSameAs(resultado.getPublicacion().getUsuario());
        assertThat(movimiento.getMonto()).isEqualTo(12_345L).isPositive();
        ArgumentCaptor<TransaccionEvento> eventoCaptor = ArgumentCaptor.forClass(TransaccionEvento.class);
        verify(transaccionEventoRepository).save(eventoCaptor.capture());
        TransaccionEvento evento = eventoCaptor.getValue();
        assertThat(evento.getTransaccion()).isSameAs(resultado);
        assertThat(evento.getActor()).isSameAs(resultado.getComprador());
        assertThat(evento.getEstadoOrigen()).isEqualTo(EstadoTransaccion.ENTREGADO);
        assertThat(evento.getEstadoDestino()).isEqualTo(EstadoTransaccion.RECIBIDO);
        assertThat(evento.getMotivo()).isNull();
        verify(notificacionService).crearNotificacionUsuario(same(resultado.getComprador()),
            eq("COMPRA_CONFIRMADA"), contains("tu compra"), same(resultado), any(ZonedDateTime.class));
        verify(notificacionService).crearNotificacionUsuario(same(resultado.getPublicacion().getUsuario()),
            eq("COMPRA_CONFIRMADA"), contains("tu venta"), same(resultado), any(ZonedDateTime.class));
        verify(resultado.getPublicacion(), never()).setStock(anyInt());
    }

    /**
     * Verifica que el método que compone transición, saldo y auditoría está delimitado por una
     * transacción de Spring para que un fallo de persistencia revierta todos los efectos.
     *
     * @throws NoSuchMethodException si la firma de dominio esperada deja de existir
     */
    @Test
    @DisplayName("Declara una transacción Spring para transición, saldo y eventos")
    void confirmarRecepcion_MetodoDeDominio_EstaAnotadoComoTransaccional() throws NoSuchMethodException {
        Method metodo = ConfirmacionRecepcionService.class.getMethod("confirmarRecepcion", Long.class, Long.class);

        assertThat(metodo.isAnnotationPresent(Transactional.class)).isTrue();
    }

    /**
     * Construye el servicio bajo prueba con un reloj fijo para verificar el límite temporal.
     *
     * @return servicio configurado con mocks y el instante {@link #AHORA}
     */
    private ConfirmacionRecepcionService service() {
        return new ConfirmacionRecepcionService(transaccionRepository, usuarioRepository,
            movimientoSaldoRepository, transaccionEventoRepository, notificacionService,
            Clock.fixed(AHORA.toInstant(), ZoneOffset.UTC));
    }

    /**
     * Crea una transacción entregada, asociada al comprador y a la publicación del vendedor.
     *
     * @param fechaEntregado instante que inicia el plazo de confirmación
     * @return transacción preparada para los escenarios de confirmación
     */
    private Transaccion transaccionEntregada(ZonedDateTime fechaEntregado) {
        Usuario comprador = usuario(COMPRADOR_ID, 0L);
        Usuario vendedor = usuario(VENDEDOR_ID, 500L);
        Publicacion publicacion = spy(new Publicacion());
        publicacion.setUsuario(vendedor);
        publicacion.setStock(4);

        Transaccion transaccion = new Transaccion();
        transaccion.setId(TRANSACCION_ID);
        transaccion.setComprador(comprador);
        transaccion.setPublicacion(publicacion);
        transaccion.setEstado(EstadoTransaccion.ENTREGADO);
        transaccion.setPrecioSnapshot(12_345L);
        transaccion.setFechaEntregado(fechaEntregado);
        return transaccion;
    }

    /**
     * Crea un usuario persistente simulado con el saldo inicial indicado.
     *
     * @param id ID persistente a asignar al usuario
     * @param saldoDisponible saldo disponible inicial en centavos
     * @return usuario configurado para la prueba
     */
    private Usuario usuario(Long id, long saldoDisponible) {
        Usuario usuario = new Usuario();
        usuario.setId(id);
        usuario.setSaldoDisponible(saldoDisponible);
        return usuario;
    }

    /**
     * Verifica que un rechazo no persiste transición, saldo, movimiento, evento ni emite
     * notificaciones.
     */
    private void verificarSinEscrituras() {
        verify(transaccionRepository, never()).save(any());
        verify(usuarioRepository, never()).incrementarSaldoDisponible(any(), anyLong());
        verify(movimientoSaldoRepository, never()).save(any());
        verify(transaccionEventoRepository, never()).save(any());
        verify(notificacionService, never()).crearNotificacionUsuario(any(), any(), any(), any(), any());
        verify(notificacionService, never()).crearNotificacionAdmin(any(), any(), any(), any());
    }
}
