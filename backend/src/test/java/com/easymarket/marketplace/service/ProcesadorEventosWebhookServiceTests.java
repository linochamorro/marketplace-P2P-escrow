package com.easymarket.marketplace.service;

import com.easymarket.marketplace.model.Publicacion;
import com.easymarket.marketplace.model.Rol;
import com.easymarket.marketplace.model.Transaccion;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.IdempotencyKeyRepository;
import com.easymarket.marketplace.repository.ProcessedStripeEventRepository;
import com.stripe.model.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZonedDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias del guardado idempotente del procesador de eventos webhook.
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

    private ProcesadorEventosWebhookService service;

    /** Construye el servicio real con sus cuatro dependencias aisladas por mocks. */
    @BeforeEach
    void setUp() {
        service = new ProcesadorEventosWebhookService(
            processedStripeEventRepository,
            reservaStockService,
            idempotencyKeyRepository,
            notificacionService
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
}
