package com.easymarket.marketplace.service;

import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Servicio de dominio de verificación de firma de webhook de Stripe (PHA03TSK07 de
 * {@code tasks.md}, Constitution principio 4 enmendado).
 *
 * <p>Implementa el paso 1 del orden de validaciones definido en la sección
 * "Verificación de webhooks Stripe" de {@code plan.md}:</p>
 * <ol>
 *   <li>Verificar firma → si falla, 400 sin tocar la base de datos.</li>
 *   <li>Solo si es válida, {@code INSERT} en {@code processed_stripe_events} (TSK08).</li>
 *   <li>Solo si el insert tuvo éxito, procesar la transición (TSK08).</li>
 * </ol>
 *
 * <p>Este servicio es una función pura de verificación de firma. Su única responsabilidad
 * es llamar a {@link Webhook#constructEvent(String, String, String)} del SDK stripe-java
 * y devolver el {@link Event} deserializado si la firma es válida. No recibe el
 * webhook secret desde configuración — lo recibe como parámetro, maximizando la
 * testabilidad y permitiendo que el orquestador (TSK08/TSK10) lo inyecte desde
 * {@code @Value("${stripe.webhook-secret}")}.</p>
 *
 * <p>No se envuelve {@link com.stripe.exception.SignatureVerificationException} en una
 * excepción de dominio propia porque la verificación de firma es intrínsecamente una
 * operación de Stripe — no hay un "segundo proveedor de pagos" en el alcance del
 * proyecto. El método declara {@link StripeException} (superclase) para que el llamante
 * pueda manejar tanto firma inválida como error de comunicación de forma general.</p>
 *
 * <p>No hace logging del payload (podría contener datos sensibles). Solo loguea un
 * mensaje genérico en caso de fallo de verificación.</p>
 */
@Service
public class WebhookVerificationService {

    private static final Logger log = LoggerFactory.getLogger(WebhookVerificationService.class);

    /**
     * Construye el servicio de verificación de firma de webhook.
     *
     * <p>Sin dependencias externas — todas las dependencias se reciben como parámetros
     * del método {@link #verificarFirma(String, String, String)}.</p>
     */
    public WebhookVerificationService() {
        // Sin dependencias externas — servicio puro de dominio
    }

    /**
     * Verifica la firma de un webhook de Stripe utilizando el header
     * {@code Stripe-Signature} y el webhook secret, mediante el SDK stripe-java.
     *
     * <p>Si la firma es válida, retorna el {@link Event} deserializado con todos los
     * campos del payload de Stripe. Si la firma es inválida (payload manipulado,
     * timestamp fuera de tolerancia, secret incorrecto), lanza
     * {@link com.stripe.exception.SignatureVerificationException} (subclase de
     * {@link StripeException}) para que el orquestador (TSK08/TSK10) decida cómo
     * responder (400 sin detalle, log append-only).</p>
     *
     * <p>No maneja la excepción internamente — la propaga para mantener la separación
     * de responsabilidades: este servicio solo verifica, no decide la respuesta HTTP.</p>
     *
     * @param payload         el cuerpo crudo del webhook (JSON sin deserializar por Spring)
     * @param sigHeader       el valor completo del header {@code Stripe-Signature}
     *                        (formato: {@code t=timestamp,v1=firma[,v0=firma_legacy]})
     * @param webhookSecret   el webhook signing secret de Stripe (prefijo {@code whsec_}),
     *                        obtenido desde {@code STRIPE_WEBHOOK_SECRET} en variables de entorno
     * @return el objeto {@link Event} deserializado si la firma es válida
     * @throws StripeException si la firma es inválida ({@link com.stripe.exception.SignatureVerificationException})
     *                         o si ocurre un error de comunicación/deserialización
     */
    public Event verificarFirma(String payload, String sigHeader, String webhookSecret)
            throws StripeException {

        try {
            Event event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
            log.info("Webhook signature verification successful: eventType={}, eventId={}",
                    event.getType(), event.getId());
            return event;
        } catch (StripeException e) {
            log.warn("Webhook signature verification failed: {}", e.getMessage());
            throw e;
        }
    }
}