package com.easymarket.marketplace.service;

/**
 * Resultado de la creación de un PaymentIntent en Stripe (PHA03TSK06, Story 5, spec.md).
 *
 * <p>Este record encapsula la información que {@link StripePaymentService#crearPaymentIntent}
 * devuelve a la capa superior (PHA03TSK09, controlador {@code POST /compras}). La capa API
 * necesita ambos campos:
 * <ul>
 *   <li>{@code paymentIntentId}: se persiste en la tabla {@code idempotency_keys} junto con la
 *       key de idempotencia (plan.md, sección "Idempotencia de compra (doble-submit)").</li>
 *   <li>{@code clientSecret}: se devuelve al frontend para que complete la confirmación del
 *       pago mediante Stripe.js / PaymentElement.</li>
 * </ul>
 * </p>
 *
 * @param paymentIntentId el ID del PaymentIntent en Stripe (formato {@code pi_XXXX})
 * @param clientSecret    el client_secret del PaymentIntent (formato {@code pi_XXXX_secret_YYYY})
 */
public record CrearPaymentIntentResult(String paymentIntentId, String clientSecret) {
}