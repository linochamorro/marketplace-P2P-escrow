package com.easymarket.marketplace.service;

import com.stripe.exception.StripeException;
import com.stripe.model.Refund;

/**
 * Boundary for submitting a durable refund order to Stripe.
 */
public interface StripeRefundGateway {

    /**
     * Requests a Stripe refund using the persistent idempotency key of an outbox order.
     *
     * @param paymentIntentId Stripe PaymentIntent to refund
     * @param idempotencyKey persistent outbox key reused in every retry
     * @return refund accepted by Stripe
     * @throws StripeException if Stripe rejects the request or cannot be reached
     */
    Refund crearRefund(String paymentIntentId, String idempotencyKey) throws StripeException;
}
