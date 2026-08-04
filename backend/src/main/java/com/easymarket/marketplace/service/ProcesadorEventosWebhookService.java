package com.easymarket.marketplace.service;

import com.easymarket.marketplace.exception.StockAgotadoException;
import com.easymarket.marketplace.model.ProcessedStripeEvent;
import com.easymarket.marketplace.model.Transaccion;
import com.easymarket.marketplace.repository.IdempotencyKeyRepository;
import com.easymarket.marketplace.repository.ProcessedStripeEventRepository;
import com.stripe.model.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;

/**
 * Servicio de dominio de procesamiento idempotente de eventos webhook de Stripe (PHA03TSK08,
 * Story 5, spec.md; plan.md, secciones "Idempotencia de webhooks Stripe" y "Flujo de compra y
 * reserva de stock (PHA03)").
 *
 * <p>Este servicio es el ORQUESTADOR de eventos webhook de Stripe. Recibe un {@link Event} de
 * Stripe (cuya firma ya fue verificada por {@link WebhookVerificationService}, PHA03TSK07) y:</p>
 *
 * <ol>
 *   <li><strong>INSERT atómico en {@code processed_stripe_events}</strong> — guardia de
 *       idempotencia. Si el {@code eventId} ya existe (PK duplicada), el evento ya fue procesado
 *       y se descarta silenciosamente.</li>
 *   <li><strong>Transición según tipo de evento</strong>:
 *       <ul>
 *         <li>{@code payment_intent.succeeded} → {@link ReservaStockService#reservarStock} para
 *             crear la transacción {@code reservada} con decremento atómico de stock y snapshot de
 *             precio, luego puebla el {@code transaccion_id} en la {@link
 *             com.easymarket.marketplace.model.IdempotencyKey} correspondiente.</li>
 *         <li>{@code payment_intent.payment_failed} → loguea el fallo, no crea nada (consistente
 *             con Story 5: "si el cobro con Stripe falla, no se crea la transacción, el stock no
 *             se descuenta").</li>
 *         <li>{@code charge.refunded} → loguea el reembolso, no crea nada (el efecto ya ocurrió
 *             en el paso que generó el refund).</li>
 *         <li>Cualquier otro tipo de evento → ignorar silenciosamente.</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p><strong>Decisión de diseño (Opción A):</strong> el método recibe {@code compradorId},
 * {@code publicacionId} y {@code paymentIntentId} como parámetros separados del evento,
 * porque estos IDs no se extraen del evento en sí (Stripe no los incluye en metadatos del
 * PaymentIntent en esta implementación). La resolución de estos IDs la realiza el orquestador
 * de capa superior (TSK10) que sabe cómo extraerlos del contexto de la transacción.</p>
 *
 * <p><strong>Manejo de {@link StockAgotadoException}:</strong> Si {@code reservarStock} lanza
 * {@code StockAgotadoException} (perdedor de la carrera de stock, plan.md: "Si al procesar
 * payment_intent.succeeded el decremento atómico WHERE stock&gt;=1 falla"), la excepción se
 * captura dentro de {@code procesarSucceeded} SIN relanzarla, para que el registro en
 * {@code processed_stripe_events} NO se revierta — de lo contrario Stripe reintentaría el evento
 * infinitamente. El registro de idempotencia ya está hecho, así que el reintento se descarta.
 * TSK10 consultará {@code processed_stripe_events} para detectar succeeded sin transacción y
 * emitir un {@code Refund.create} si es necesario (plan.md: perdedor de la carrera → Refund).</p>
 *
 * <p><strong>Eventos no manejados:</strong> Cualquier tipo de evento distinto a los tres
 * confirmados en plan.md ({@code payment_intent.succeeded}, {@code payment_intent.payment_failed},
 * {@code charge.refunded}) se ignora silenciosamente con un log de advertencia.</p>
 */
@Service
public class ProcesadorEventosWebhookService {

    private static final Logger log = LoggerFactory.getLogger(ProcesadorEventosWebhookService.class);

    private final ProcessedStripeEventRepository processedStripeEventRepository;
    private final ReservaStockService reservaStockService;
    private final IdempotencyKeyRepository idempotencyKeyRepository;

    /**
     * Construye el servicio inyectando los repositorios y servicios necesarios.
     *
     * @param processedStripeEventRepository repositorio JPA de eventos procesados (idempotencia)
     * @param reservaStockService            servicio de reserva atómica de stock (PHA03TSK04)
     * @param idempotencyKeyRepository       repositorio JPA de claves de idempotencia (para poblar
     *                                       {@code transaccion_id})
     */
    public ProcesadorEventosWebhookService(ProcessedStripeEventRepository processedStripeEventRepository,
                                            ReservaStockService reservaStockService,
                                            IdempotencyKeyRepository idempotencyKeyRepository) {
        this.processedStripeEventRepository = processedStripeEventRepository;
        this.reservaStockService = reservaStockService;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
    }

    /**
     * Procesa un evento de webhook de Stripe de forma idempotente, registrando el event_id en
     * la tabla {@code processed_stripe_events} y disparando la transición de negocio
     * correspondiente al tipo de evento.
     *
     * <p><strong>Idempotencia:</strong> Si el {@code eventId} ya existe en la tabla, el método
     * retorna inmediatamente {@code null} sin reprocesar el evento.</p>
     *
     * <p><strong>Parámetros extra:</strong> Los parámetros {@code compradorId},
     * {@code publicacionId} y {@code paymentIntentId} se reciben por separado del evento
     * (Opción A de diseño) porque estos IDs se resuelven en la capa de orquestación superior
     * (TSK10), no se deserializan del objeto {@code Event} de Stripe.</p>
     *
     * @param event           evento de Stripe (ya verificado por {@link WebhookVerificationService})
     * @param compradorId     ID del usuario comprador (resuelto por el orquestador)
     * @param publicacionId   ID de la publicación comprada (resuelto por el orquestador)
     * @param paymentIntentId ID del PaymentIntent de Stripe asociado (resuelto por el orquestador)
     * @return la {@link Transaccion} creada si el evento fue {@code payment_intent.succeeded}
*         y la reserva fue exitosa; {@code null} si el evento ya fue procesado, si el tipo
 *         de evento no crea transacción, o si la reserva falló por stock agotado
     */
    @Transactional
    public Transaccion procesarEvento(Event event, Long compradorId, Long publicacionId, String paymentIntentId) {
        String eventId = event.getId();

        // Paso 1: idempotencia — si el eventId ya fue procesado, descartar
        if (processedStripeEventRepository.existsById(eventId)) {
            log.info("Evento Stripe ya procesado, ignorando: eventId={}, type={}", eventId, event.getType());
            return null;
        }

        // Registrar el evento como procesado ANTES de la transición de negocio.
        // Si la transición falla (ej. StockAgotadoException), este registro NO se revierte
        // (ver manejo en procesarSucceeded) para evitar reintentos infinitos de Stripe.
        processedStripeEventRepository.save(new ProcessedStripeEvent(eventId, ZonedDateTime.now()));

        // Paso 2: transición según tipo de evento
        return switch (event.getType()) {
            case "payment_intent.succeeded" ->
                procesarSucceeded(compradorId, publicacionId, paymentIntentId, eventId);
            case "payment_intent.payment_failed" -> {
                log.warn("PaymentIntent falló: paymentIntentId={}, eventId={}", paymentIntentId, eventId);
                yield null;
            }
            case "charge.refunded" -> {
                log.info("Reembolso procesado: paymentIntentId={}, eventId={}", paymentIntentId, eventId);
                yield null;
            }
            default -> {
                log.warn("Evento Stripe no manejado: type={}, eventId={}", event.getType(), eventId);
                yield null;
            }
        };
    }

    /**
     * Procesa un evento {@code payment_intent.succeeded}: intenta la reserva atómica de stock y
     * creación de la transacción {@code reservada}, y si tiene éxito, puebla el
     * {@code transaccion_id} en la {@code IdempotencyKey} correspondiente.
     *
     * <p><strong>Manejo de {@link StockAgotadoException}:</strong> La excepción se captura aquí
     * para que el registro en {@code processed_stripe_events} (ya insertado en
     * {@link #procesarEvento}) NO se revierta. Si se revirtiera, Stripe reintentaría el evento y
     * volvería a fallar por el mismo motivo (reintentos infinitos). Al retener el registro de
     * idempotencia, Stripe no reintenta. La excepción se relanza después del log para que el
     * orquestador TSK10 pueda emitir un {@code Refund.create} (plan.md, perdedor de la carrera
     * de stock).</p>
     *
     * @param compradorId     ID del usuario comprador
     * @param publicacionId   ID de la publicación comprada
* @param paymentIntentId ID del PaymentIntent de Stripe asociado
 * @param eventId         ID del evento de Stripe (para logging)
 * @return la {@link Transaccion} creada y persistida, o {@code null} si el stock se agotó
     */
    private Transaccion procesarSucceeded(Long compradorId, Long publicacionId, String paymentIntentId, String eventId) {
        try {
            Transaccion transaccion = reservaStockService.reservarStock(compradorId, publicacionId);

            // Poblar transaccion_id en la idempotency key correspondiente
            idempotencyKeyRepository.findByPaymentIntentId(paymentIntentId)
                .ifPresent(key -> {
                    key.setTransaccionId(transaccion.getId());
                    idempotencyKeyRepository.save(key);
                    log.info("Transacción vinculada a idempotency key: paymentIntentId={}, transaccionId={}",
                        paymentIntentId, transaccion.getId());
                });

            log.info("Transacción creada desde webhook succeeded: transaccionId={}, paymentIntentId={}, eventId={}",
                transaccion.getId(), paymentIntentId, eventId);
            return transaccion;

        } catch (StockAgotadoException e) {
            // NO relanzar: el registro en processed_stripe_events (insertado en procesarEvento)
            // no debe revertirse. Si se revierte, Stripe reintentaría el evento infinitamente sin
            // stock disponible. Al retener el registro de idempotencia, el reintento se descarta.
            // TSK10 consultará el estado de la transacción para emitir Refund.create si es necesario.
            log.warn("Stock agotado al procesar payment_intent.succeeded: paymentIntentId={}, eventId={}. " +
                    "Se requiere Refund.create vía TSK10.", paymentIntentId, eventId);
            return null;
        }
    }
}