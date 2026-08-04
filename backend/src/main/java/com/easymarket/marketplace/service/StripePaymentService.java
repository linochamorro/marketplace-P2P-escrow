package com.easymarket.marketplace.service;

import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Servicio de dominio de integración con Stripe para la creación de PaymentIntents
 * en modo captura inmediata (PHA03TSK06, Story 5, spec.md).
 *
 * <p>Implementa el paso 1b del flujo de compra definido en plan.md ("Flujo de compra y reserva
 * de stock (PHA03)"): creación del PaymentIntent de Stripe <b>sin</b> crear transacción ni
 * reservar stock. El PaymentIntent se crea con {@code confirm=false} para que el frontend
 * complete la confirmación del pago mediante Stripe.js / PaymentElement embebido.</p>
 *
 * <p>No recibe ni procesa eventos de webhook (PHA03TSK07/TSK08) y no expone endpoints REST
 * (PHA03TSK09). Alcance estricto: solo la creación del PaymentIntent contra la API de Stripe.</p>
 *
 * <p>Las excepciones {@link StripeException} se propagan sin capturar — la capa de controlador
 * (PHA03TSK09) y/o el handler global ({@link com.easymarket.marketplace.exception.GlobalExceptionHandler})
 * las manejan en el nivel adecuado.</p>
 *
 * <p>Uso de idempotencia: la {@code idempotencyKey} recibida se pasa a Stripe mediante
 * {@link RequestOptions#setIdempotencyKey(String)} para que Stripe garantice que la misma key
 * no cree múltiples PaymentIntents (plan.md, sección "Idempotencia de compra (doble-submit)").</p>
 *
* <p>Moneda: siempre "pen" (soles peruanos, entero en centavos), consistente con plan.md
 * sección "Flujo de compra y reserva de stock (PHA03)". El método acepta el parámetro por
 * flexibilidad y testabilidad.</p>
 *
 * <p>Metadata de negocio (PHA03TSK10, plan.md sección "Resolución de IDs de negocio en el
 * webhook"): además de {@code project=easymarket}, el PaymentIntent se crea con
 * {@code comprador_id} y {@code publicacion_id} en su metadata. Estos IDs son los que el
 * endpoint {@code POST /webhooks/stripe} extrae del evento {@code payment_intent.succeeded}
 * para orquestar {@link ProcesadorEventosWebhookService}</p>
 */
@Service
public class StripePaymentService {

    private static final Logger log = LoggerFactory.getLogger(StripePaymentService.class);

    /** Valor de la metadata {@code project} que identifica el marketplace (plan.md). */
    private static final String METADATA_PROJECT = "project";

    /** Valor de la metadata {@code project}. */
    private static final String METADATA_PROJECT_VALUE = "easymarket";

    /** Clave de metadata con el ID del usuario comprador (PHA03TSK10). */
    private static final String METADATA_COMPRADOR_ID = "comprador_id";

    /** Clave de metadata con el ID de la publicación comprada (PHA03TSK10). */
    private static final String METADATA_PUBLICACION_ID = "publicacion_id";

    /**
     * Construye el servicio de pago con Stripe.
     */
    public StripePaymentService() {
        // Sin dependencias externas — Stripe.apiKey se configura globalmente en StripeConfig
    }

    /**
     * Crea un PaymentIntent en modo captura inmediata (capture_method=automatic) contra
     * la API de Stripe.
     *
     * <p>El PaymentIntent se crea con {@code confirm=false} para que el frontend lo confirme
     * posteriormente con Stripe.js. Los métodos de pago automáticos están habilitados sin
     * redirecciones ({@code allow_redirects=never}) para usar el PaymentElement embebido.</p>
     *
     * <p>La key de idempotencia se envía a Stripe en los headers HTTP para garantizar que
     * reintentos con la misma key no dupliquen el PaymentIntent.</p>
     *
     * <p><strong>Metadata de negocio (PHA03TSK10):</strong> además de {@code project=easymarket},
     * se agregan {@code comprador_id} y {@code publicacion_id}. Esta metadata es la que el
     * endpoint {@code POST /webhooks/stripe} extraerá del evento {@code payment_intent.succeeded}
     * para resolver los IDs de negocio y orquestar {@link ProcesadorEventosWebhookService}
     * (plan.md, sección "Resolución de IDs de negocio en el webhook (PHA03TSK10)").</p>
     *
     * @param montoCentavos   el monto del PaymentIntent en centavos (entero, principio 3:
     *                        dinero como enteros, nunca punto flotante)
     * @param moneda          el código de moneda ISO 4217 en minúsculas (para este proyecto
     *                        siempre {@code "pen"})
     * @param idempotencyKey  la clave de idempotencia única (UUID v4 generada por el frontend)
     * @param compradorId     ID del usuario comprador (se persiste en la metadata del
     *                        PaymentIntent para resolverlo en el webhook de PHA03TSK10)
     * @param publicacionId   ID de la publicación comprada (se persiste en la metadata del
     *                        PaymentIntent para resolverlo en el webhook de PHA03TSK10)
     * @return un {@link CrearPaymentIntentResult} con el ID del PaymentIntent y su client_secret
     * @throws StripeException si ocurre un error de comunicación o rechazo por la API de Stripe
     */
    public CrearPaymentIntentResult crearPaymentIntent(long montoCentavos, String moneda, String idempotencyKey,
                                                       Long compradorId, Long publicacionId)
            throws StripeException {

        log.info("Creando PaymentIntent: monto={} {} (centavos), idempotencyKey={}, compradorId={}, publicacionId={}",
                montoCentavos, moneda, idempotencyKey, compradorId, publicacionId);

        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(montoCentavos)
                .setCurrency(moneda)
                .setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.AUTOMATIC)
                .setAutomaticPaymentMethods(
                        PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                                .setEnabled(true)
                                .setAllowRedirects(
                                        PaymentIntentCreateParams.AutomaticPaymentMethods.AllowRedirects.NEVER
                                )
                                .build()
                )
                .setConfirm(false)
                .putMetadata(METADATA_PROJECT, METADATA_PROJECT_VALUE)
                .putMetadata(METADATA_COMPRADOR_ID, compradorId.toString())
                .putMetadata(METADATA_PUBLICACION_ID, publicacionId.toString())
                .build();

        RequestOptions options = RequestOptions.builder()
                .setIdempotencyKey(idempotencyKey)
                .build();

        PaymentIntent intent = PaymentIntent.create(params, options);

        log.info("PaymentIntent creado: id={}, status={}, amount={}", intent.getId(), intent.getStatus(), intent.getAmount());

        return new CrearPaymentIntentResult(intent.getId(), intent.getClientSecret());
    }
}