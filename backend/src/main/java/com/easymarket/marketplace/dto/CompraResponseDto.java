package com.easymarket.marketplace.dto;

/**
 * DTO de respuesta del endpoint {@code POST /compras} (PHA03TSK09, Story 5, spec.md).
 *
 * <p>Devuelve al frontend los datos necesarios para completar el pago mediante Stripe.js /
 * PaymentElement embebido (plan.md, "Flujo de compra y reserva de stock (PHA03)", UX de pago):
 * <ul>
 *   <li>{@code clientSecret}: el client_secret del PaymentIntent, requerido por Stripe.js para
 *       confirmar el pago.</li>
 *   <li>{@code paymentIntentId}: el ID del PaymentIntent creado (patrón {@code pi_XXXX}),
 *       útil para trazabilidad y tests.</li>
 * </ul>
 * Este endpoint NO crea transacción ni reserva stock (eso ocurre en el webhook
 * {@code payment_intent.succeeded}, PHA03TSK08, ya cerrada).</p>
 *
 * @param clientSecret el client_secret del PaymentIntent (formato {@code pi_XXXX_secret_YYYY})
 * @param paymentIntentId el ID del PaymentIntent en Stripe (formato {@code pi_XXXX})
 */
public record CompraResponseDto(String clientSecret, String paymentIntentId) {
}
