package com.easymarket.marketplace.service;

import com.easymarket.marketplace.exception.StockAgotadoException;
import com.easymarket.marketplace.model.ProcessedStripeEvent;
import com.easymarket.marketplace.model.StripeRefundOutbox;
import com.easymarket.marketplace.model.Transaccion;
import com.easymarket.marketplace.repository.IdempotencyKeyRepository;
import com.easymarket.marketplace.repository.ProcessedStripeEventRepository;
import com.easymarket.marketplace.repository.StripeRefundOutboxRepository;
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
 *             precio, notifica al vendedor de la publicación y luego puebla el {@code transaccion_id} en la {@link
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
 * <p>El método recibe {@code compradorId}, {@code publicacionId} y {@code paymentIntentId} como
 * parámetros separados del evento. La resolución de estos identificadores corresponde al
 * orquestador de la capa superior, que los entrega al procesador junto con el evento.</p>
 *
 * <p><strong>Manejo de {@link StockAgotadoException}:</strong> Si {@code reservarStock} lanza
 * {@code StockAgotadoException} (perdedor de la carrera de stock, plan.md: "Si al procesar
 * payment_intent.succeeded el decremento atómico WHERE stock&gt;=1 falla"), la excepción se
 * captura en {@code procesarSucceeded}, donde se persiste una orden durable {@code PENDIENTE} en
 * {@code stripe_refund_outbox} (transacción {@code null}, {@code payment_intent_id} del evento,
 * clave {@code refund:pi:{paymentIntentId}}) dentro de la MISMA transacción del evento; no avisa
 * al vendedor si la reserva fracasa por stock agotado; retorna {@code null}. El registro de
 * {@code processed_stripe_events} guardado se conserva (sin reintentos infinitos de Stripe) y
 * la entrega repetida retorna temprano por idempotencia. Este servicio nunca emite
 * {@code Refund.create} ni llama a Stripe: la emisión es del {@code StripeRefundOutboxJob}
 * existente (saga/outbox, PHA04).</p>
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
    private final NotificacionService notificacionService;
    private final StripeRefundOutboxRepository stripeRefundOutboxRepository;

    /**
     * Construye el servicio inyectando los repositorios y servicios necesarios.
     *
     * @param processedStripeEventRepository repositorio JPA de eventos procesados (idempotencia)
     * @param reservaStockService            servicio de reserva atómica de stock (PHA03TSK04)
     * @param idempotencyKeyRepository       repositorio JPA de claves de idempotencia (para poblar
     *                                       {@code transaccion_id})
     * @param notificacionService             servicio de notificaciones in-app idempotentes para
     *                                       avisar al vendedor de una compra nueva
     * @param stripeRefundOutboxRepository   repositorio JPA de órdenes durables de reembolso
     *                                       (para el perdedor de la carrera de stock, PHA16TSK02)
     */
    public ProcesadorEventosWebhookService(ProcessedStripeEventRepository processedStripeEventRepository,
                                            ReservaStockService reservaStockService,
                                            IdempotencyKeyRepository idempotencyKeyRepository,
                                            NotificacionService notificacionService,
                                            StripeRefundOutboxRepository stripeRefundOutboxRepository) {
        this.processedStripeEventRepository = processedStripeEventRepository;
        this.reservaStockService = reservaStockService;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.notificacionService = notificacionService;
        this.stripeRefundOutboxRepository = stripeRefundOutboxRepository;
    }

    /**
     * Procesa un evento de webhook de Stripe de forma idempotente, registrando el event_id en
     * la tabla {@code processed_stripe_events} y disparando la transición de negocio
     * correspondiente al tipo de evento.
     *
     * <p><strong>Idempotencia:</strong> Si el {@code eventId} ya existe en la tabla, el método
     * retorna inmediatamente {@code null} sin reprocesar el evento.</p>
     *
 * <p><strong>Parámetros de negocio:</strong> Los parámetros {@code compradorId},
 * {@code publicacionId} y {@code paymentIntentId} se reciben por separado del evento y son
 * proporcionados por la capa de orquestación superior.</p>
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

        // Registrar el evento como procesado ANTES de la transición de negocio, en la misma
        // transacción atómica (constitution, principio 1). Si la reserva fracasa por stock
        // agotado, el catch de procesarSucceeded persiste la orden de refund en esta misma
        // transacción y retorna null sin relanzar, por lo que la marca se conserva (sin
        // reintentos infinitos de Stripe). Si la persistencia de la orden falla, su excepción
        // sí se propaga y revierte también la marca del evento (atomicidad).
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
     * creación de la transacción {@code reservada}, notifica al vendedor de la publicación y, si
     * tiene éxito, puebla el {@code transaccion_id} en la {@code IdempotencyKey} correspondiente.
     * La notificación usa la misma transacción {@code REQUIRED} del método público; si falla su
     * persistencia, el fallo se propaga para revertir los efectos propios de la compra.
     *
     * <p><strong>Manejo de {@link StockAgotadoException} (perdedor de la carrera de stock,
     * PHA16TSK02):</strong> La excepción se captura aquí y, dentro de la MISMA transacción del
     * evento, se persiste una orden durable {@code PENDIENTE} en {@code stripe_refund_outbox}
     * (transacción {@code null}, {@code payment_intent_id} del evento, clave determinista
     * {@code refund:pi:{paymentIntentId}}), que el {@code StripeRefundOutboxJob} existente
     * procesará sin cambios. Luego se devuelve {@code null} SIN relanzar, por lo que el registro
     * en {@code processed_stripe_events} (ya insertado en {@link #procesarEvento}) se conserva:
     * una nueva entrega del mismo evento retorna temprano por idempotencia (sin reintentos
     * infinitos de Stripe). En cambio, si la persistencia de la orden falla, esa excepción NO se
     * captura: se propaga y la transacción revierte también la marca del evento (atomicidad,
     * constitution principio 1). Este método nunca emite {@code Refund.create} ni llama a
     * Stripe (saga/outbox, PHA04).</p>
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

            notificacionService.crearNotificacionUsuario(
                transaccion.getPublicacion().getUsuario(),
                "COMPRA_CONFIRMADA",
                "Nueva compra confirmada en tu publicación #" + transaccion.getPublicacion().getId()
                    + ": transacción #" + transaccion.getId(),
                transaccion.getPublicacion(),
                transaccion,
                ZonedDateTime.now());

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
            // Perdedor de la carrera de stock (PHA16TSK02): persistir la orden durable de
            // reembolso en la MISMA transacción del evento, ANTES de retornar null. La marca del
            // evento se conserva porque no se relanza (sin reintentos infinitos de Stripe); si
            // este save falla, su excepción se propaga y revierte también la marca (atomicidad).
            // Nunca Refund.create ni llamadas a Stripe aquí: la emisión es del job (PHA04).
            stripeRefundOutboxRepository.save(new StripeRefundOutbox(
                paymentIntentId, "refund:pi:" + paymentIntentId, ZonedDateTime.now()));
            log.warn("Stock agotado al procesar payment_intent.succeeded: paymentIntentId={}, eventId={}. "
                    + "Orden durable de reembolso registrada en la outbox.", paymentIntentId, eventId);
            return null;
        }
    }
}
