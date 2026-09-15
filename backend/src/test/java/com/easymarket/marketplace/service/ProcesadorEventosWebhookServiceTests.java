package com.easymarket.marketplace.service;

import com.easymarket.marketplace.exception.StockAgotadoException;
import com.easymarket.marketplace.model.Publicacion;
import com.easymarket.marketplace.model.Rol;
import com.easymarket.marketplace.model.Transaccion;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.model.StripeRefundOutbox;
import com.easymarket.marketplace.repository.IdempotencyKeyRepository;
import com.easymarket.marketplace.repository.ProcessedStripeEventRepository;
import com.easymarket.marketplace.repository.StripeRefundOutboxRepository;
import com.stripe.model.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.ZonedDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias del guardado idempotente del procesador de eventos webhook y del refund
 * durable al perdedor de la carrera de stock (PHA16TSK02, Story 5 de spec.md).
 */
@ExtendWith(MockitoExtension.class)
class ProcesadorEventosWebhookServiceTests {

    @Mock
    private ProcessedStripeEventRepository processedStripeEventRepository;

    @Mock
    private ReservaStockService reservaStockService;

    @Mock
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Mock
    private NotificacionService notificacionService;

    @Mock
    private StripeRefundOutboxRepository stripeRefundOutboxRepository;

    private ProcesadorEventosWebhookService service;

    /** Construye el servicio real con sus cinco dependencias aisladas por mocks. */
    @BeforeEach
    void setUp() {
        service = new ProcesadorEventosWebhookService(
            processedStripeEventRepository,
            reservaStockService,
            idempotencyKeyRepository,
            notificacionService,
            stripeRefundOutboxRepository
        );
    }

    /**
     * Comprueba que la segunda invocación del mismo evento retorna antes de la reserva y del aviso.
     */
    @Test
    void eventoRepetido_RetornaNullSinReservarNiNotificar() {
        String eventId = "evt-unit-idempotente";
        Event event = new Event();
        event.setId(eventId);
        event.setType("payment_intent.succeeded");

        Usuario vendedor = new Usuario("vendedor-unit@example.com", "hash", Rol.USUARIO, 0L,
            ZonedDateTime.parse("2026-08-29T12:00:00Z"));
        vendedor.setId(7L);
        Publicacion publicacion = new Publicacion();
        publicacion.setId(22L);
        publicacion.setUsuario(vendedor);
        Transaccion transaccion = new Transaccion();
        transaccion.setId(33L);
        transaccion.setPublicacion(publicacion);

        when(processedStripeEventRepository.existsById(eventId)).thenReturn(false, true);
        when(reservaStockService.reservarStock(11L, 22L)).thenReturn(transaccion);
        when(idempotencyKeyRepository.findByPaymentIntentId("pi-unit-idempotente"))
            .thenReturn(Optional.empty());

        Transaccion primeraRespuesta = service.procesarEvento(
            event, 11L, 22L, "pi-unit-idempotente");
        Transaccion segundaRespuesta = service.procesarEvento(
            event, 11L, 22L, "pi-unit-idempotente");

        assertThat(primeraRespuesta).isSameAs(transaccion);
        assertThat(segundaRespuesta).isNull();
        verify(reservaStockService, times(1)).reservarStock(11L, 22L);
        verify(notificacionService, times(1)).crearNotificacionUsuario(
            eq(vendedor),
            eq("COMPRA_CONFIRMADA"),
            eq("Nueva compra confirmada en tu publicación #22: transacción #33"),
            eq(publicacion),
            eq(transaccion),
            org.mockito.ArgumentMatchers.any(ZonedDateTime.class));
    }

    /**
     * Comprueba el criterio (a) de PHA16TSK02: stock agotado → orden {@code PENDIENTE} sin
     * transacción (perdedor de la carrera), con el {@code payment_intent_id} exacto del evento y
     * la clave idempotente exacta {@code refund:pi:<pi>}; retorna {@code null} y no notifica.
     */
    @Test
    void stockAgotado_InsertaOrdenPendienteSinTransaccionYRetornaNull() {
        String eventId = "evt-unit-agotado";
        String paymentIntentId = "pi-unit-agotado";
        Event event = new Event();
        event.setId(eventId);
        event.setType("payment_intent.succeeded");

        when(processedStripeEventRepository.existsById(eventId)).thenReturn(false);
        when(reservaStockService.reservarStock(11L, 22L))
            .thenThrow(new StockAgotadoException("sin stock disponible"));

        Transaccion resultado = service.procesarEvento(event, 11L, 22L, paymentIntentId);

        assertThat(resultado).isNull();
        ArgumentCaptor<StripeRefundOutbox> captor = ArgumentCaptor.forClass(StripeRefundOutbox.class);
        verify(stripeRefundOutboxRepository, times(1)).save(captor.capture());
        StripeRefundOutbox orden = captor.getValue();
        assertThat(orden.getTransaccion()).isNull();
        assertThat(orden.getPaymentIntentId()).isEqualTo(paymentIntentId);
        assertThat(orden.getIdempotencyKey()).isEqualTo("refund:pi:" + paymentIntentId);
        assertThat(orden.getEstado()).isEqualTo("PENDIENTE");
        assertThat(orden.getIntentos()).isZero();
        verify(notificacionService, never()).crearNotificacionUsuario(
            any(), any(), any(), any(), any(), any());
    }

    /**
     * Comprueba el criterio (b) de PHA16TSK02 (regresión del camino feliz): reserva exitosa →
     * retorna la transacción y NUNCA persiste una orden de refund.
     */
    @Test
    void reservaExitosa_NoCreaOrdenDeRefund() {
        String eventId = "evt-unit-ok";
        String paymentIntentId = "pi-unit-ok";
        Event event = new Event();
        event.setId(eventId);
        event.setType("payment_intent.succeeded");

        Usuario vendedor = new Usuario("vendedor-ok@example.com", "hash", Rol.USUARIO, 0L,
            ZonedDateTime.parse("2026-08-29T12:00:00Z"));
        vendedor.setId(7L);
        Publicacion publicacion = new Publicacion();
        publicacion.setId(22L);
        publicacion.setUsuario(vendedor);
        Transaccion transaccion = new Transaccion();
        transaccion.setId(33L);
        transaccion.setPublicacion(publicacion);

        when(processedStripeEventRepository.existsById(eventId)).thenReturn(false);
        when(reservaStockService.reservarStock(11L, 22L)).thenReturn(transaccion);
        when(idempotencyKeyRepository.findByPaymentIntentId(paymentIntentId))
            .thenReturn(Optional.empty());

        Transaccion respuesta = service.procesarEvento(event, 11L, 22L, paymentIntentId);

        assertThat(respuesta).isSameAs(transaccion);
        verify(stripeRefundOutboxRepository, never()).save(any(StripeRefundOutbox.class));
    }

    /**
     * Comprueba el criterio (c) de PHA16TSK02 a nivel unitario: un evento ya procesado retorna
     * temprano sin reservar ni persistir ninguna orden de refund.
     */
    @Test
    void eventoRepetido_NoCreaOrdenDeRefund() {
        String eventId = "evt-unit-repetido";
        Event event = new Event();
        event.setId(eventId);
        event.setType("payment_intent.succeeded");

        when(processedStripeEventRepository.existsById(eventId)).thenReturn(true);

        assertThat(service.procesarEvento(event, 11L, 22L, "pi-unit-repetido")).isNull();
        verify(reservaStockService, never()).reservarStock(any(), any());
        verify(stripeRefundOutboxRepository, never()).save(any(StripeRefundOutbox.class));
    }

    /**
     * Comprueba el criterio (d) de PHA16TSK02 a nivel unitario: si la persistencia de la orden
     * falla, la excepción específica se propaga al llamador (el rollback de la marca del evento
     * lo verifica el test de integración contra PostgreSQL real).
     */
    @Test
    void falloAlPersistirOrden_PropagaExcepcionSinRetornarNull() {
        String eventId = "evt-unit-fallo-orden";
        String paymentIntentId = "pi-unit-fallo-orden";
        Event event = new Event();
        event.setId(eventId);
        event.setType("payment_intent.succeeded");

        when(processedStripeEventRepository.existsById(eventId)).thenReturn(false);
        when(reservaStockService.reservarStock(11L, 22L))
            .thenThrow(new StockAgotadoException("sin stock disponible"));
        when(stripeRefundOutboxRepository.save(any(StripeRefundOutbox.class)))
            .thenThrow(new DataIntegrityViolationException("clave idempotente duplicada"));

        assertThatThrownBy(() -> service.procesarEvento(event, 11L, 22L, paymentIntentId))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Comprueba el criterio (e) de PHA16TSK02 (sin cambios): {@code charge.refunded} y
     * {@code payment_intent.payment_failed} retornan {@code null} sin reservar stock ni
     * persistir ninguna orden de refund.
     */
    @Test
    void eventosSinRefund_NoCreanOrdenNiReservan() {
        String refundedEventId = "evt-unit-refunded";
        String failedEventId = "evt-unit-failed";
        Event refunded = new Event();
        refunded.setId(refundedEventId);
        refunded.setType("charge.refunded");
        Event failed = new Event();
        failed.setId(failedEventId);
        failed.setType("payment_intent.payment_failed");

        when(processedStripeEventRepository.existsById(refundedEventId)).thenReturn(false);
        when(processedStripeEventRepository.existsById(failedEventId)).thenReturn(false);

        assertThat(service.procesarEvento(refunded, 11L, 22L, "pi-unit-refunded")).isNull();
        assertThat(service.procesarEvento(failed, 11L, 22L, "pi-unit-failed")).isNull();
        verify(reservaStockService, never()).reservarStock(any(), any());
        verify(stripeRefundOutboxRepository, never()).save(any(StripeRefundOutbox.class));
    }
}
