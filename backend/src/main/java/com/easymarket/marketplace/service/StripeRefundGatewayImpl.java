package com.easymarket.marketplace.service;

import com.stripe.exception.StripeException;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
import org.springframework.stereotype.Service;

/**
 * Stripe SDK implementation that creates a refund for a PaymentIntent.
 */
@Service
public class StripeRefundGatewayImpl implements StripeRefundGateway {

    /**
     * Calls Stripe Refund.create with the outbox's stable idempotency key.
     *
     * @param paymentIntentId Stripe PaymentIntent to refund
     * @param idempotencyKey persistent outbox key reused in every retry
     * @return refund accepted by Stripe
     * @throws StripeException if Stripe rejects the request or cannot be reached
     */
    @Override
    public Refund crearRefund(String paymentIntentId, String idempotencyKey) throws StripeException {
        RefundCreateParams params = RefundCreateParams.builder().setPaymentIntent(paymentIntentId).build();
        RequestOptions options = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
        return Refund.create(params, options);
    }
}
