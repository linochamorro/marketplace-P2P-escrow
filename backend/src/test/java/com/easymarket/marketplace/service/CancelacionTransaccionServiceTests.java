package com.easymarket.marketplace.service;

import com.easymarket.marketplace.exception.MotivoCancelacionObligatorioException;
import com.easymarket.marketplace.exception.PaymentIntentTransaccionNoEncontradoException;
import com.easymarket.marketplace.exception.ActorNoAutorizadoParaCancelarTransaccionException;
import com.easymarket.marketplace.exception.TransicionEstadoTransaccionInvalidaException;
import com.easymarket.marketplace.model.EstadoTransaccion;
import com.easymarket.marketplace.model.IdempotencyKey;
import com.easymarket.marketplace.model.Publicacion;
import com.easymarket.marketplace.model.StripeRefundOutbox;
import com.easymarket.marketplace.model.Transaccion;
import com.easymarket.marketplace.model.TransaccionEvento;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.IdempotencyKeyRepository;
import com.easymarket.marketplace.repository.PublicacionRepository;
import com.easymarket.marketplace.repository.StripeRefundOutboxRepository;
import com.easymarket.marketplace.repository.TransaccionEventoRepository;
import com.easymarket.marketplace.repository.TransaccionRepository;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias de la cancelación transaccional y de su orden durable de reembolso de Story 7.
 */
@ExtendWith(MockitoExtension.class)
class CancelacionTransaccionServiceTests {

    private static final Long TRANSACCION_ID = 100L;
    private static final Long COMPRADOR_ID = 20L;
    private static final Long VENDEDOR_ID = 10L;
    private static final Long TERCERO_ID = 30L;

    @Mock private TransaccionRepository transaccionRepository;
    @Mock private PublicacionRepository publicacionRepository;
    @Mock private IdempotencyKeyRepository idempotencyKeyRepository;
    @Mock private TransaccionEventoRepository transaccionEventoRepository;
    @Mock private StripeRefundOutboxRepository stripeRefundOutboxRepository;

    /** Verifica que null, vacío y blancos se rechazan sin ninguna escritura. */
    @Test
    @DisplayName("Rechaza motivo nulo, vacío o blanco sin escrituras")
    void cancelar_MotivoAusente_RechazaSinEscrituras() {
        for (String motivo : new String[]{null, "", " \t "}) {
            assertThatThrownBy(() -> service().cancelar(TRANSACCION_ID, COMPRADOR_ID, motivo))
                .isInstanceOf(MotivoCancelacionObligatorioException.class);
        }
        verificarSinEscrituras();
        verify(transaccionRepository, never()).findByIdForUpdate(any());
    }

    /** Verifica que los estados fuera de reservada/enviado no generan efectos persistentes. */
    @Test
    @DisplayName("Rechaza cancelar desde estado no permitido sin escrituras")
    void cancelar_EstadoNoPermitido_RechazaSinEscrituras() {
        Transaccion transaccion = transaccion(EstadoTransaccion.ENTREGADO);
        when(transaccionRepository.findByIdForUpdate(TRANSACCION_ID)).thenReturn(Optional.of(transaccion));

        assertThatThrownBy(() -> service().cancelar(TRANSACCION_ID, COMPRADOR_ID, "No puedo recibirlo"))
            .isInstanceOf(TransicionEstadoTransaccionInvalidaException.class);

        verificarSinEscrituras();
        verify(idempotencyKeyRepository, never()).findByTransaccionId(any());
    }

    /** Verifica que la correlación Stripe faltante bloquea todos los efectos locales. */
    @Test
    @DisplayName("Rechaza sin PaymentIntent antes de cualquier escritura")
    void cancelar_SinPaymentIntent_RechazaSinEscrituras() {
        Transaccion transaccion = transaccion(EstadoTransaccion.RESERVADA);
        when(transaccionRepository.findByIdForUpdate(TRANSACCION_ID)).thenReturn(Optional.of(transaccion));
        when(idempotencyKeyRepository.findByTransaccionId(TRANSACCION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().cancelar(TRANSACCION_ID, COMPRADOR_ID, "Cambio de planes"))
            .isInstanceOf(PaymentIntentTransaccionNoEncontradoException.class);

        verificarSinEscrituras();
    }

    /** Verifica todos los efectos locales y la orden durable para una cancelación de reservada. */
    @Test
    @DisplayName("Cancela reservada, restaura una unidad y persiste evento y orden durable")
    void cancelar_ReservadaPorComprador_PersisteEfectosYOrdenDurable() {
        Transaccion transaccion = transaccion(EstadoTransaccion.RESERVADA);
        IdempotencyKey key = new IdempotencyKey();
        key.setPaymentIntentId("pi_123");
        when(transaccionRepository.findByIdForUpdate(TRANSACCION_ID)).thenReturn(Optional.of(transaccion));
        when(idempotencyKeyRepository.findByTransaccionId(TRANSACCION_ID)).thenReturn(Optional.of(key));
        when(transaccionRepository.save(transaccion)).thenReturn(transaccion);

        Transaccion resultado = service().cancelar(TRANSACCION_ID, COMPRADOR_ID, "Cambio de planes");

        assertThat(resultado.getEstado()).isEqualTo(EstadoTransaccion.CANCELADA);
        assertThat(resultado.getMotivoCancelacion()).isEqualTo("Cambio de planes");
        verify(publicacionRepository).incrementarStock(50L);
        TransaccionEvento evento = capturarEvento();
        assertThat(evento.getActor()).isSameAs(resultado.getComprador());
        assertThat(evento.getEstadoOrigen()).isEqualTo(EstadoTransaccion.RESERVADA);
        assertThat(evento.getEstadoDestino()).isEqualTo(EstadoTransaccion.CANCELADA);
        assertThat(evento.getMotivo()).isEqualTo("Cambio de planes");
        StripeRefundOutbox orden = capturarOrden();
        assertThat(orden.getTransaccion()).isSameAs(resultado);
        assertThat(orden.getPaymentIntentId()).isEqualTo("pi_123");
        assertThat(orden.getIdempotencyKey()).isEqualTo("refund:100");
        assertThat(orden.getEstado()).isEqualTo("PENDIENTE");
        assertThat(orden.getIntentos()).isZero();
        assertThat(orden.getCreatedAt()).isNotNull();
        assertThat(orden.getUpdatedAt()).isNotNull();
    }

    /** Verifica que el vendedor también está autorizado para cancelar una reserva. */
    @Test
    @DisplayName("Permite al vendedor cancelar reservada y persiste sus efectos")
    void cancelar_ReservadaPorVendedor_PersisteEfectosYOrdenDurable() {
        Transaccion transaccion = transaccion(EstadoTransaccion.RESERVADA);
        IdempotencyKey key = new IdempotencyKey();
        key.setPaymentIntentId("pi_789");
        when(transaccionRepository.findByIdForUpdate(TRANSACCION_ID)).thenReturn(Optional.of(transaccion));
        when(idempotencyKeyRepository.findByTransaccionId(TRANSACCION_ID)).thenReturn(Optional.of(key));
        when(transaccionRepository.save(transaccion)).thenReturn(transaccion);

        Transaccion resultado = service().cancelar(TRANSACCION_ID, VENDEDOR_ID, "No puedo concretar la venta");

        assertThat(resultado.getEstado()).isEqualTo(EstadoTransaccion.CANCELADA);
        verify(publicacionRepository).incrementarStock(50L);
        assertThat(capturarEvento().getActor()).isSameAs(resultado.getPublicacion().getUsuario());
        assertThat(capturarOrden().getPaymentIntentId()).isEqualTo("pi_789");
    }

    /** Verifica que el vendedor puede cancelar una transacción enviada. */
    @Test
    @DisplayName("Permite al vendedor cancelar enviada")
    void cancelar_EnviadaPorVendedor_Cancela() {
        Transaccion transaccion = transaccion(EstadoTransaccion.ENVIADO);
        IdempotencyKey key = new IdempotencyKey();
        key.setPaymentIntentId("pi_456");
        when(transaccionRepository.findByIdForUpdate(TRANSACCION_ID)).thenReturn(Optional.of(transaccion));
        when(idempotencyKeyRepository.findByTransaccionId(TRANSACCION_ID)).thenReturn(Optional.of(key));
        when(transaccionRepository.save(transaccion)).thenReturn(transaccion);

        Transaccion resultado = service().cancelar(TRANSACCION_ID, VENDEDOR_ID, "No puedo despacharlo");

        assertThat(resultado.getEstado()).isEqualTo(EstadoTransaccion.CANCELADA);
        assertThat(capturarEvento().getActor()).isSameAs(resultado.getPublicacion().getUsuario());
    }

    /** Verifica que el comprador no puede cancelar una transacción que ya fue enviada. */
    @Test
    @DisplayName("Rechaza al comprador al cancelar enviada sin escrituras")
    void cancelar_EnviadaPorComprador_RechazaSinEscrituras() {
        Transaccion transaccion = transaccion(EstadoTransaccion.ENVIADO);
        when(transaccionRepository.findByIdForUpdate(TRANSACCION_ID)).thenReturn(Optional.of(transaccion));

        assertThatThrownBy(() -> service().cancelar(TRANSACCION_ID, COMPRADOR_ID, "Quiero cancelarla"))
            .isInstanceOf(ActorNoAutorizadoParaCancelarTransaccionException.class);

        verificarSinEscrituras();
        verify(idempotencyKeyRepository, never()).findByTransaccionId(any());
    }

    /** Verifica que un tercero no puede cancelar una transacción que ya fue enviada. */
    @Test
    @DisplayName("Rechaza a un tercero al cancelar enviada sin escrituras")
    void cancelar_EnviadaPorTercero_RechazaSinEscrituras() {
        Transaccion transaccion = transaccion(EstadoTransaccion.ENVIADO);
        when(transaccionRepository.findByIdForUpdate(TRANSACCION_ID)).thenReturn(Optional.of(transaccion));

        assertThatThrownBy(() -> service().cancelar(TRANSACCION_ID, TERCERO_ID, "Quiero cancelarla"))
            .isInstanceOf(ActorNoAutorizadoParaCancelarTransaccionException.class);

        verificarSinEscrituras();
        verify(idempotencyKeyRepository, never()).findByTransaccionId(any());
    }

    /** Verifica que el límite de la operación es una única transacción de Spring. */
    @Test
    @DisplayName("Declara una transacción Spring para todos los efectos de cancelación")
    void cancelar_MetodoDeDominio_EstaAnotadoComoTransaccional() throws NoSuchMethodException {
        Method metodo = CancelacionTransaccionService.class.getMethod("cancelar", Long.class, Long.class, String.class);
        assertThat(metodo.isAnnotationPresent(Transactional.class)).isTrue();
    }

    /** @return servicio configurado con los mocks de esta prueba. */
    private CancelacionTransaccionService service() {
        return new CancelacionTransaccionService(transaccionRepository, publicacionRepository,
            idempotencyKeyRepository, transaccionEventoRepository, stripeRefundOutboxRepository);
    }

    /** @return el evento append-only enviado al repositorio. */
    private TransaccionEvento capturarEvento() {
        ArgumentCaptor<TransaccionEvento> captor = ArgumentCaptor.forClass(TransaccionEvento.class);
        verify(transaccionEventoRepository).save(captor.capture());
        return captor.getValue();
    }

    /** @return la orden de reembolso durable enviada al repositorio. */
    private StripeRefundOutbox capturarOrden() {
        ArgumentCaptor<StripeRefundOutbox> captor = ArgumentCaptor.forClass(StripeRefundOutbox.class);
        verify(stripeRefundOutboxRepository).save(captor.capture());
        return captor.getValue();
    }

    /**
     * Configura una transacción con comprador, vendedor y publicación persistidos simuladamente.
     *
     * @param estado estado inicial para el escenario de prueba
     * @return transacción configurada con IDs y relaciones necesarias
     */
    private Transaccion transaccion(EstadoTransaccion estado) {
        Usuario comprador = usuario(COMPRADOR_ID);
        Usuario vendedor = usuario(VENDEDOR_ID);
        Publicacion publicacion = new Publicacion();
        publicacion.setId(50L);
        publicacion.setUsuario(vendedor);
        Transaccion transaccion = new Transaccion();
        transaccion.setId(TRANSACCION_ID);
        transaccion.setComprador(comprador);
        transaccion.setPublicacion(publicacion);
        transaccion.setEstado(estado);
        return transaccion;
    }

    /**
     * Crea un usuario persistido simulado.
     *
     * @param id ID que se asignará al usuario
     * @return usuario con el ID indicado
     */
    private Usuario usuario(Long id) {
        Usuario usuario = new Usuario();
        usuario.setId(id);
        return usuario;
    }

    /** Verifica que un rechazo no escribe stock, estado, auditoría ni outbox. */
    private void verificarSinEscrituras() {
        verify(publicacionRepository, never()).incrementarStock(any());
        verify(transaccionRepository, never()).save(any());
        verify(transaccionEventoRepository, never()).save(any());
        verify(stripeRefundOutboxRepository, never()).save(any());
    }
}
