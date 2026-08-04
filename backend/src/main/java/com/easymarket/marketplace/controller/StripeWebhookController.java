package com.easymarket.marketplace.controller;

import com.easymarket.marketplace.service.ProcesadorEventosWebhookService;
import com.easymarket.marketplace.service.WebhookVerificationService;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.HasId;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Controller REST del webhook de Stripe (PHA03TSK10, Story 5, spec.md; Constitution principio 4
 * enmendado).
 *
 * <p>Implementa {@code POST /webhooks/stripe}: recibe el evento de pago que Stripe entrega al
 * marketplace y orquesta {@link ProcesadorEventosWebhookService} (PHA03TSK08) para registrar la
 * transacción {@code reservada} cuando el PaymentIntent se confirma. El endpoint es
 * <b>público</b> (permitAll en {@code SecurityConfig}): Stripe no envía cookie JWT — la
 * autenticación del emisor es la firma HMAC del header {@code Stripe-Signature}.</p>
 *
 * <p><strong>Body crudo sin deserialización previa:</strong> el payload se recibe como
 * {@link String} ({@code @RequestBody String}) y la firma se verifica contra ese JSON crudo,
 * antes de que Spring lo convierta a ningún POJO. Mantener el raw body intacto es requisito
 * del criterio de aceptación (fila PHA03TSK10 de {@code tasks.md}) y de plan.md, sección
 * "Verificación de webhooks Stripe".</p>
 *
 * <p><strong>Orquestación (orden estricto de plan.md):</strong>
 * <ol>
 *   <li><b>Verificar firma primero</b> ({@link WebhookVerificationService#verificarFirma}):
 *       inválida → HTTP 400 sin detalle, log del intento como evento de seguridad, sin tocar la
 *       base de datos.</li>
 *   <li><b>Rama por tipo de evento (refinamiento 2026-08-03, decisión de Lino):</b>
 *       <ul>
 *         <li>{@code payment_intent.succeeded}: evento CRÍTICO. Se exige extraer
 *             {@code comprador_id}/{@code publicacion_id} de la metadata del PaymentIntent
 *             (decisión de plan.md 2026-08-03); si el {@code data.object} no es un PaymentIntent
 *             o la metadata falta/no parsea → HTTP 400 sin detalle (evento no processable).</li>
 *         <li>Cualquier otro evento con firma válida ({@code charge.refunded},
 *             {@code payment_intent.payment_failed}, tipos desconocidos): ack <b>200</b> y se
 *             delega en el procesador idempotente con {@code compradorId}/{@code publicacionId}
 *             nulos (el procesador solo los usa en la rama {@code succeeded}; en las demás
 *             inserta en {@code processed_stripe_events} y loguea sin efecto). NO se responde 400
 *             por {@code data.object} no-PaymentIntent: evita reintentos infinitos de Stripe
 *             (backoff exponencial), justo lo que la idempotencia del Constitution principio 4
 *             enmendado busca evitar.</li>
 *       </ul>
 *   </li>
 *   <li><b>Procesar el evento</b>: {@link ProcesadorEventosWebhookService#procesarEvento} con la
 *       guardia {@code existsById} de idempotencia (el INSERT en {@code processed_stripe_events}
 *       y la transición ocurren en la misma transacción atómica).</li>
 *   <li><b>Responder 200</b> si la firma fue válida y el evento fue aceptado, aunque el
 *       procesador retorne {@code null} (evento ya procesado, tipo no manejado o stock agotado
 *       — el evento fue aceptado e idempotente).</li>
 * </ol>
 * </p>
 *
 * <p><strong>Alcance estricto (sin scope creep):</strong> NO se implementa aquí el {@code
 * Refund.create} del perdedor de la carrera de stock ni la correlación de {@code charge.refunded}
 * con transacciones (follow-ups explícitos fuera de alcance de la fila PHA03TSK10, según
 * decisión de plan.md/ESTADO_PROYECTO).</p>
 */
@RestController
@RequestMapping("/webhooks/stripe")
public class StripeWebhookController {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);

    /** Tipo de evento de Stripe que dispara la creación de la transacción {@code reservada}. */
    private static final String EVENTO_PAYMENT_INTENT_SUCCEEDED = "payment_intent.succeeded";

    /** Clave de metadata del PaymentIntent con el ID del usuario comprador (plan.md 2026-08-03). */
    private static final String METADATA_COMPRADOR_ID = "comprador_id";

    /** Clave de metadata del PaymentIntent con el ID de la publicación comprada (plan.md 2026-08-03). */
    private static final String METADATA_PUBLICACION_ID = "publicacion_id";

    private final WebhookVerificationService webhookVerificationService;
    private final ProcesadorEventosWebhookService procesadorEventosWebhookService;
    private final String webhookSecret;

    /**
     * Construye el controlador inyectando los servicios de dominio del webhook y el secret de
     * firma desde configuración.
     *
     * @param webhookVerificationService     servicio de verificación de firma {@code Stripe-Signature}
     *                                       (PHA03TSK07)
     * @param procesadorEventosWebhookService servicio de procesamiento idempotente de eventos
     *                                       (PHA03TSK08)
     * @param webhookSecret                  webhook signing secret de Stripe (prefijo {@code whsec_}),
     *                                       inyectado desde {@code stripe.webhook-secret}
     */
    public StripeWebhookController(WebhookVerificationService webhookVerificationService,
                                   ProcesadorEventosWebhookService procesadorEventosWebhookService,
                                   @Value("${stripe.webhook-secret:}") String webhookSecret) {
        this.webhookVerificationService = webhookVerificationService;
        this.procesadorEventosWebhookService = procesadorEventosWebhookService;
        this.webhookSecret = webhookSecret;
    }

    /**
     * Endpoint público {@code POST /webhooks/stripe}: recibe el payload crudo de Stripe y su
     * header {@code Stripe-Signature}, verifica la firma contra el raw body y, si es válida,
     * orquesta el procesador de eventos.
     *
     * <p><strong>Orden de validaciones (plan.md):</strong> 1) firma → 2) rama por tipo de
     * evento → 3) procesamiento idempotente → 4) respuesta. La firma inválida responde HTTP 400
     * sin cuerpo (sin detalle al emisor) y sin tocar la base de datos; el intento se registra en
     * el log como evento de seguridad.</p>
     *
     * <p><strong>Refinamiento 2026-08-03 (decisión de Lino):</strong> solo {@code
     * payment_intent.succeeded} exige la extracción de IDs de la metadata del PaymentIntent y
     * puede responder 400 si el evento no es processable. Todo otro evento con firma válida
     * recibe ack 200 y se delega en el procesador idempotente (ver {@link #procesarSucceeded} y
     * la rama no-succeeded).</p>
     *
     * <p><strong>Idempotencia:</strong> si el {@code eventId} ya fue procesado, el procesador
     * retorna {@code null} y el endpoint responde igualmente 200 (Stripe reintenta los webhooks
     * no confirmados; confirmar con 200 evita reintentos infinitos — Constitution principio 4
     * enmendado).</p>
     *
     * @param payload   cuerpo crudo del webhook (JSON de Stripe, sin deserializar por Spring)
     * @param sigHeader valor del header {@code Stripe-Signature} (formato
     *                  {@code t=timestamp,v1=firma[,v0=firma_legacy]}); si está ausente, la
     *                  firma no puede verificarse y se responde 400
     * @return HTTP 200 OK si la firma es válida y el evento fue aceptado (aunque el procesador
     *         retorne {@code null}); HTTP 400 Bad Request sin cuerpo si la firma es inválida o
     *         si un {@code payment_intent.succeeded} no puede resolver los IDs de negocio
     */
    @PostMapping
    public ResponseEntity<Void> recibirWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "Stripe-Signature", required = false) String sigHeader) {

        // ── Paso 1: verificación de firma (antes de tocar cualquier dato) ────────────────
        if (sigHeader == null || sigHeader.isBlank()) {
            log.warn("Webhook Stripe rechazado: header Stripe-Signature ausente (evento de seguridad)");
            return ResponseEntity.badRequest().build();
        }

        final Event event;
        try {
            event = webhookVerificationService.verificarFirma(payload, sigHeader, webhookSecret);
        } catch (StripeException e) {
            log.warn("Webhook Stripe rechazado: firma inválida (evento de seguridad): {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }

        // ── Paso 2: rama por tipo de evento (refinamiento 2026-08-03) ─────────────────────
        if (EVENTO_PAYMENT_INTENT_SUCCEEDED.equals(event.getType())) {
            return procesarSucceeded(event);
        }

        // Cualquier otro evento con firma válida → ack 200 y delegación en el procesador
        // idempotente (con IDs nulos: el procesador solo los usa en la rama succeeded).
        // NO responde 400 por data.object no-PaymentIntent (evita reintentos infinitos de Stripe).
        procesadorEventosWebhookService.procesarEvento(event, null, null, extraerObjectId(event));
        return ResponseEntity.ok().build();
    }

    /**
     * Procesa la rama crítica {@code payment_intent.succeeded}: extrae {@code comprador_id} y
     * {@code publicacion_id} de la metadata del PaymentIntent y orquesta la reserva atómica de
     * stock vía {@link ProcesadorEventosWebhookService#procesarEvento}.
     *
     * <p>Si el {@code data.object} no es un {@link PaymentIntent} o la metadata no contiene los
     * IDs de negocio parseables, responde HTTP 400 sin cuerpo (evento no processable) — esta es
     * la única rama en la que un evento firmado válidamente puede recibir 400 (refinamiento
     * 2026-08-03, decisión de Lino).</p>
     *
     * @param event evento {@code payment_intent.succeeded} con la firma ya verificada
     * @return HTTP 200 OK si la metadata se resolvió y el evento fue aceptado; HTTP 400 Bad
     *         Request sin cuerpo si el evento no es processable
     */
    private ResponseEntity<Void> procesarSucceeded(Event event) {
        PaymentIntent paymentIntent = extraerPaymentIntent(event);
        if (paymentIntent == null) {
            log.warn("Webhook Stripe rechazado: payment_intent.succeeded sin PaymentIntent "
                    + "deserializable. eventId={} (evento crítico no processable)", event.getId());
            return ResponseEntity.badRequest().build();
        }

        Long compradorId = extraerLongMetadata(paymentIntent, METADATA_COMPRADOR_ID);
        Long publicacionId = extraerLongMetadata(paymentIntent, METADATA_PUBLICACION_ID);
        if (compradorId == null || publicacionId == null) {
            log.warn("Webhook Stripe rechazado: metadata incompleta en PaymentIntent. "
                    + "eventId={}, paymentIntentId={}, compradorId={}, publicacionId={} "
                    + "(evento crítico no processable)",
                    event.getId(), paymentIntent.getId(), compradorId, publicacionId);
            return ResponseEntity.badRequest().build();
        }

        // ── Paso 3: procesamiento idempotente (INSERT + transición atómica, TSK08) ──────
        procesadorEventosWebhookService.procesarEvento(event, compradorId, publicacionId, paymentIntent.getId());

        // ── Paso 4: el evento fue aceptado e idempotente → 200 (incluso si retorna null)
        return ResponseEntity.ok().build();
    }

    /**
     * Extrae el {@link PaymentIntent} del {@code data.object} del evento verificado, si el
     * objeto deserializado es de tipo PaymentIntent.
     *
     * <p>Usa {@link Event.Data#getObject()} del SDK stripe-java: deserializa el JSON de
     * {@code data.object} en el tipo de Stripe correspondiente según el campo {@code object}
     * del payload ({@code payment_intent} → {@link PaymentIntent}). A diferencia de
     * {@link Event#getDataObjectDeserializer()}, este mapeo no depende del {@code api_version}
     * del evento, por lo que es robusto también para payloads minimalistas de test.</p>
     *
     * @param event evento de Stripe con la firma ya verificada
     * @return el {@link PaymentIntent} si el {@code data.object} es un PaymentIntent; {@code null}
     *         en cualquier otro caso
     */
    private PaymentIntent extraerPaymentIntent(Event event) {
        Event.Data data = event.getData();
        if (data == null || data.getObject() == null) {
            return null;
        }
        StripeObject objeto = data.getObject();
        if (objeto instanceof PaymentIntent paymentIntent) {
            return paymentIntent;
        }
        return null;
    }

    /**
     * Lee y parsea un valor {@code Long} desde la metadata del PaymentIntent.
     *
     * @param paymentIntent PaymentIntent del evento verificado
     * @param claveMetadata clave de metadata a leer (ej. {@code comprador_id})
     * @return el valor parseado a {@link Long}, o {@code null} si la clave falta, el valor está
     *         vacío o no es un número entero parseable
     */
    private Long extraerLongMetadata(PaymentIntent paymentIntent, String claveMetadata) {
        Map<String, String> metadata = paymentIntent.getMetadata();
        if (metadata == null) {
            return null;
        }
        String valor = metadata.get(claveMetadata);
        if (valor == null || valor.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(valor);
        } catch (NumberFormatException e) {
            log.warn("Webhook Stripe: metadata '{}' no es un Long parseable: '{}'", claveMetadata, valor);
            return null;
        }
    }

    /**
     * Extrae el {@code id} del {@code data.object} del evento si el objeto deserializado lo
     * expone ({@link HasId}), para usarlo en el logging de la rama no-succeeded.
     *
     * <p>Válido para {@code PaymentIntent} ({@code pi_...}), {@code Charge} ({@code ch_...})
     * y demás objetos de Stripe con {@code id}; si el {@code data.object} es nulo o el tipo
     * deserializado no implementa {@link HasId}, retorna {@code null}.</p>
     *
     * @param event evento de Stripe con la firma ya verificada
     * @return el {@code id} del objeto del {@code data.object}, o {@code null} si no está
     *         disponible
     */
    private String extraerObjectId(Event event) {
        Event.Data data = event.getData();
        if (data == null || data.getObject() == null) {
            return null;
        }
        StripeObject objeto = data.getObject();
        if (objeto instanceof HasId hasId) {
            return hasId.getId();
        }
        return null;
    }
}
