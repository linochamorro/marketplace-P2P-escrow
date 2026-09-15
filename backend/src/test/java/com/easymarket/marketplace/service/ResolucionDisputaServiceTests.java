package com.easymarket.marketplace.service;

import com.easymarket.marketplace.exception.DecisionResolucionDisputaInvalidaException;
import com.easymarket.marketplace.exception.MotivoResolucionDisputaObligatorioException;
import com.easymarket.marketplace.exception.PaymentIntentTransaccionNoEncontradoException;
import com.easymarket.marketplace.exception.TransicionEstadoTransaccionInvalidaException;
import com.easymarket.marketplace.model.EstadoTransaccion;
import com.easymarket.marketplace.model.IdempotencyKey;
import com.easymarket.marketplace.model.MovimientoSaldo;
import com.easymarket.marketplace.model.Notificacion;
import com.easymarket.marketplace.model.Publicacion;
import com.easymarket.marketplace.model.ResolucionDisputa;
import com.easymarket.marketplace.model.StripeRefundOutbox;
import com.easymarket.marketplace.model.Transaccion;
import com.easymarket.marketplace.model.TransaccionEvento;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.IdempotencyKeyRepository;
import com.easymarket.marketplace.repository.MovimientoSaldoRepository;
import com.easymarket.marketplace.repository.PublicacionRepository;
import com.easymarket.marketplace.repository.StripeRefundOutboxRepository;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias de la resolución binaria y atómica de disputas de Story 9.
 *
 * <p>Las pruebas distinguen el crédito interno al vendedor de la reversión al comprador: la
 * primera rama inserta un movimiento positivo y la segunda restaura stock y crea una orden
 * durable de Stripe. Ambas persisten un evento append-only con la identidad real del admin,
 * decisión y motivo obligatorio. Desde PHA12TSK03 (recuperación de PHA09TSK05) verifican además
 * que ambas ramas emiten, dentro de la misma transacción, la notificación accionable
 * DISPUTA_RESUELTA al comprador y al vendedor con los mensajes dirigidos ("tu compra"/"tu venta")
 * que la UI enruta, y que ningún rechazo temprano emite notificaciones.</p>
 */
@ExtendWith(MockitoExtension.class)
class ResolucionDisputaServiceTests {

    private static final Long TRANSACCION_ID = 100L;
    private static final Long COMPRADOR_ID = 20L;
    private static final Long VENDEDOR_ID = 10L;
    private static final Long ADMIN_ID = 30L;
    private static final long PRECIO_SNAPSHOT = 12_345L;

    @Mock private TransaccionRepository transaccionRepository;
    @Mock private UsuarioRepository usuarioRepository;
    @Mock private MovimientoSaldoRepository movimientoSaldoRepository;
    @Mock private PublicacionRepository publicacionRepository;
    @Mock private IdempotencyKeyRepository idempotencyKeyRepository;
    @Mock private TransaccionEventoRepository transaccionEventoRepository;
    @Mock private StripeRefundOutboxRepository stripeRefundOutboxRepository;
    @Mock private NotificacionService notificacionService;

    /**
     * Configura el mock de {@link NotificacionService} en modo leniente para que responda a cada
     * emisión accionable construyendo la instancia real de {@link Notificacion} con los argumentos
     * recibidos (patrón de PHA09TSK05-L02). Es leniente porque los tests de rechazo temprano nunca
     * llegan a emitir notificaciones y, sin {@code lenient()}, Mockito reportaría stubbings
     * innecesarios para esas pruebas. El stub de {@code crearNotificacionAdmin} usa un usuario
     * admin simulado porque la resolución del destinatario real ocurre dentro del servicio
     * notificado, no en el servicio bajo prueba.
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
        lenient().when(notificacionService.crearNotificacionAdmin(any(), any(), any(), any()))
            .thenAnswer(invocation -> new Notificacion(
                usuario(ADMIN_ID),
                invocation.getArgument(2),
                invocation.getArgument(1),
                invocation.getArgument(0),
                invocation.getArgument(3)));
    }

    /** Verifica que cualquier estado distinto de disputa no produce efectos persistentes. */
    @Test
    @DisplayName("Rechaza resolver fuera de disputa sin escrituras")
    void resolver_EstadoNoDisputa_RechazaSinEscrituras() {
        Transaccion transaccion = transaccion(EstadoTransaccion.ENTREGADO);
        when(transaccionRepository.findByIdForUpdate(TRANSACCION_ID)).thenReturn(Optional.of(transaccion));

        assertThatThrownBy(() -> service().resolver(TRANSACCION_ID, ADMIN_ID,
            ResolucionDisputa.A_FAVOR_VENDEDOR, "La entrega quedó acreditada"))
            .isInstanceOf(TransicionEstadoTransaccionInvalidaException.class);

        verificarSinEscrituras();
        verify(usuarioRepository, never()).findById(any());
    }

    /** Verifica que una decisión nula se rechaza explícitamente antes de bloquear o escribir. */
    @Test
    @DisplayName("Rechaza decisión nula sin bloquear ni escribir")
    void resolver_DecisionNula_RechazaSinEfectos() {
        assertThatThrownBy(() -> service().resolver(TRANSACCION_ID, ADMIN_ID, null, "Motivo válido"))
            .isInstanceOf(DecisionResolucionDisputaInvalidaException.class);

        verificarSinEscrituras();
        verify(transaccionRepository, never()).findByIdForUpdate(any());
    }

    /** Verifica que null, vacío y blancos no pueden convertirse en una resolución auditable. */
    @Test
    @DisplayName("Rechaza motivo nulo, vacío o blanco sin bloquear ni escribir")
    void resolver_MotivoAusente_RechazaSinEfectos() {
        for (String motivo : new String[]{null, "", " \t "}) {
            assertThatThrownBy(() -> service().resolver(TRANSACCION_ID, ADMIN_ID,
                ResolucionDisputa.A_FAVOR_VENDEDOR, motivo))
                .isInstanceOf(MotivoResolucionDisputaObligatorioException.class);
        }

        verificarSinEscrituras();
        verify(transaccionRepository, never()).findByIdForUpdate(any());
    }

    /** Verifica el crédito exacto, su movimiento y la auditoría para el vendedor. */
    @Test
    @DisplayName("Resuelve a favor del vendedor acreditando snapshot y auditando admin")
    void resolver_AFavorVendedor_AcreditaSaldoMovimientoYEventoSinStockNiOutbox() {
        Transaccion transaccion = transaccion(EstadoTransaccion.DISPUTA);
        Usuario admin = usuario(ADMIN_ID);
        when(transaccionRepository.findByIdForUpdate(TRANSACCION_ID)).thenReturn(Optional.of(transaccion));
        when(usuarioRepository.findById(ADMIN_ID)).thenReturn(Optional.of(admin));
        when(transaccionRepository.save(transaccion)).thenReturn(transaccion);

        Transaccion resultado = service().resolver(TRANSACCION_ID, ADMIN_ID,
            ResolucionDisputa.A_FAVOR_VENDEDOR, "La entrega quedó acreditada");

        assertThat(resultado.getEstado()).isEqualTo(EstadoTransaccion.COMPLETADA);
        verify(usuarioRepository).incrementarSaldoDisponible(VENDEDOR_ID, PRECIO_SNAPSHOT);
        MovimientoSaldo movimiento = capturarMovimiento();
        assertThat(movimiento.getTransaccion()).isSameAs(resultado);
        assertThat(movimiento.getVendedor()).isSameAs(resultado.getPublicacion().getUsuario());
        assertThat(movimiento.getMonto()).isEqualTo(PRECIO_SNAPSHOT);
        TransaccionEvento evento = capturarEvento();
        assertThat(evento.getActor()).isSameAs(admin);
        assertThat(evento.getEstadoOrigen()).isEqualTo(EstadoTransaccion.DISPUTA);
        assertThat(evento.getEstadoDestino()).isEqualTo(EstadoTransaccion.COMPLETADA);
        assertThat(evento.getMotivo()).isEqualTo("A_FAVOR_VENDEDOR: La entrega quedó acreditada");
        verify(notificacionService).crearNotificacionUsuario(same(resultado.getComprador()),
            eq("DISPUTA_RESUELTA"), contains("tu compra"), same(resultado), any());
        verify(notificacionService).crearNotificacionUsuario(same(resultado.getPublicacion().getUsuario()),
            eq("DISPUTA_RESUELTA"), contains("tu venta"), same(resultado), any());
        verify(publicacionRepository, never()).incrementarStock(any());
        verify(idempotencyKeyRepository, never()).findByTransaccionId(any());
        verify(stripeRefundOutboxRepository, never()).save(any());
    }

    /** Verifica la reversión local, orden durable y auditoría para el comprador. */
    @Test
    @DisplayName("Resuelve a favor del comprador restaurando stock y creando outbox")
    void resolver_AFavorComprador_RestauraStockOutboxYEventoSinSaldoNiMovimiento() {
        Transaccion transaccion = transaccion(EstadoTransaccion.DISPUTA);
        Usuario admin = usuario(ADMIN_ID);
        IdempotencyKey key = new IdempotencyKey();
        key.setPaymentIntentId("pi_123");
        when(transaccionRepository.findByIdForUpdate(TRANSACCION_ID)).thenReturn(Optional.of(transaccion));
        when(usuarioRepository.findById(ADMIN_ID)).thenReturn(Optional.of(admin));
        when(idempotencyKeyRepository.findByTransaccionId(TRANSACCION_ID)).thenReturn(Optional.of(key));
        when(transaccionRepository.save(transaccion)).thenReturn(transaccion);

        Transaccion resultado = service().resolver(TRANSACCION_ID, ADMIN_ID,
            ResolucionDisputa.A_FAVOR_COMPRADOR, "El producto no coincide con la publicación");

        assertThat(resultado.getEstado()).isEqualTo(EstadoTransaccion.CANCELADA);
        verify(publicacionRepository).incrementarStock(50L);
        StripeRefundOutbox orden = capturarOrden();
        assertThat(orden.getTransaccion()).isSameAs(resultado);
        assertThat(orden.getPaymentIntentId()).isEqualTo("pi_123");
        assertThat(orden.getIdempotencyKey()).isEqualTo("refund:100");
        TransaccionEvento evento = capturarEvento();
        assertThat(evento.getActor()).isSameAs(admin);
        assertThat(evento.getEstadoOrigen()).isEqualTo(EstadoTransaccion.DISPUTA);
        assertThat(evento.getEstadoDestino()).isEqualTo(EstadoTransaccion.CANCELADA);
        assertThat(evento.getMotivo()).isEqualTo("A_FAVOR_COMPRADOR: El producto no coincide con la publicación");
        verify(notificacionService).crearNotificacionUsuario(same(resultado.getComprador()),
            eq("DISPUTA_RESUELTA"), contains("tu compra"), same(resultado), any());
        verify(notificacionService).crearNotificacionUsuario(same(resultado.getPublicacion().getUsuario()),
            eq("DISPUTA_RESUELTA"), contains("tu venta"), same(resultado), any());
        verify(usuarioRepository, never()).incrementarSaldoDisponible(any(), anyLong());
        verify(movimientoSaldoRepository, never()).save(any());
    }

    /** Verifica que no se cancela ni restaura stock sin correlación de Stripe durable. */
    @Test
    @DisplayName("Rechaza resolución a favor del comprador sin PaymentIntent y sin efectos")
    void resolver_AFavorCompradorSinPaymentIntent_RechazaSinEfectos() {
        Transaccion transaccion = transaccion(EstadoTransaccion.DISPUTA);
        Usuario admin = usuario(ADMIN_ID);
        when(transaccionRepository.findByIdForUpdate(TRANSACCION_ID)).thenReturn(Optional.of(transaccion));
        when(usuarioRepository.findById(ADMIN_ID)).thenReturn(Optional.of(admin));
        when(idempotencyKeyRepository.findByTransaccionId(TRANSACCION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().resolver(TRANSACCION_ID, ADMIN_ID,
            ResolucionDisputa.A_FAVOR_COMPRADOR, "El producto no coincide"))
            .isInstanceOf(PaymentIntentTransaccionNoEncontradoException.class);

        verificarSinEscrituras();
    }

    /** Verifica que todos los efectos propios están protegidos por una transacción Spring. */
    @Test
    @DisplayName("Declara una transacción Spring para todos los efectos de resolución")
    void resolver_MetodoDeDominio_EstaAnotadoComoTransaccional() throws NoSuchMethodException {
        Method metodo = ResolucionDisputaService.class.getMethod("resolver", Long.class, Long.class,
            ResolucionDisputa.class, String.class);

        assertThat(metodo.isAnnotationPresent(Transactional.class)).isTrue();
    }

    /** @return servicio configurado con los mocks de esta prueba. */
    private ResolucionDisputaService service() {
        return new ResolucionDisputaService(transaccionRepository, usuarioRepository, movimientoSaldoRepository,
            publicacionRepository, idempotencyKeyRepository, transaccionEventoRepository,
            stripeRefundOutboxRepository, notificacionService);
    }

    /** @return movimiento de saldo persistido por la rama vendedora. */
    private MovimientoSaldo capturarMovimiento() {
        ArgumentCaptor<MovimientoSaldo> captor = ArgumentCaptor.forClass(MovimientoSaldo.class);
        verify(movimientoSaldoRepository).save(captor.capture());
        return captor.getValue();
    }

    /** @return evento append-only persistido por la resolución. */
    private TransaccionEvento capturarEvento() {
        ArgumentCaptor<TransaccionEvento> captor = ArgumentCaptor.forClass(TransaccionEvento.class);
        verify(transaccionEventoRepository).save(captor.capture());
        return captor.getValue();
    }

    /** @return orden durable de reembolso persistida por la rama compradora. */
    private StripeRefundOutbox capturarOrden() {
        ArgumentCaptor<StripeRefundOutbox> captor = ArgumentCaptor.forClass(StripeRefundOutbox.class);
        verify(stripeRefundOutboxRepository).save(captor.capture());
        return captor.getValue();
    }

    /**
     * Crea una transacción asociada a una publicación y vendedor para el escenario indicado.
     *
     * @param estado estado inicial de la transacción
     * @return transacción con precio snapshot entero, comprador y relaciones persistidas simuladamente
     */
    private Transaccion transaccion(EstadoTransaccion estado) {
        Publicacion publicacion = new Publicacion();
        publicacion.setId(50L);
        publicacion.setUsuario(usuario(VENDEDOR_ID));
        Transaccion transaccion = new Transaccion();
        transaccion.setId(TRANSACCION_ID);
        transaccion.setComprador(usuario(COMPRADOR_ID));
        transaccion.setPublicacion(publicacion);
        transaccion.setEstado(estado);
        transaccion.setPrecioSnapshot(PRECIO_SNAPSHOT);
        return transaccion;
    }

    /**
     * Crea un usuario persistido simulado.
     *
     * @param id ID que se asignará al usuario
     * @return usuario con la identidad indicada
     */
    private Usuario usuario(Long id) {
        Usuario usuario = new Usuario();
        usuario.setId(id);
        return usuario;
    }

    /** Verifica que un rechazo no persiste ningún efecto local ni emite notificaciones. */
    private void verificarSinEscrituras() {
        verify(usuarioRepository, never()).incrementarSaldoDisponible(any(), anyLong());
        verify(movimientoSaldoRepository, never()).save(any());
        verify(publicacionRepository, never()).incrementarStock(any());
        verify(transaccionRepository, never()).save(any());
        verify(transaccionEventoRepository, never()).save(any());
        verify(stripeRefundOutboxRepository, never()).save(any());
        verify(notificacionService, never()).crearNotificacionUsuario(any(), any(), any(), any(), any());
        verify(notificacionService, never()).crearNotificacionAdmin(any(), any(), any(), any());
    }
}
