package com.easymarket.marketplace.service;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.param.PaymentIntentCreateParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stripe sandbox integration tests for the refund gateway.
 *
 * <p>The class is enabled only with a real {@code STRIPE_SECRET_KEY}; it never blocks CI that
 * lacks external Stripe credentials.</p>
 */
@EnabledIfEnvironmentVariable(named = "STRIPE_SECRET_KEY", matches = "sk_test_.+")
class StripeRefundGatewayIntegrationTests {

    /**
     * Verifies that retrying one durable key does not create two Stripe refunds.
     *
     * @throws StripeException if Stripe sandbox rejects the PaymentIntent or refund request
     */
    @Test
    @DisplayName("Retrying a refund with the same outbox key returns the same Stripe refund")
    void crearRefund_MismaClaveDeOutbox_NoDuplicaRefund() throws StripeException {
        Stripe.apiKey = System.getenv("STRIPE_SECRET_KEY");
        PaymentIntent intent = PaymentIntent.create(PaymentIntentCreateParams.builder()
            .setAmount(100L).setCurrency("usd").setConfirm(true).setPaymentMethod("pm_card_visa")
            .setAutomaticPaymentMethods(PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                .setEnabled(true)
                .setAllowRedirects(PaymentIntentCreateParams.AutomaticPaymentMethods.AllowRedirects.NEVER)
                .build())
            .build());
        StripeRefundGateway gateway = new StripeRefundGatewayImpl();
        String key = "refund-integration:" + UUID.randomUUID();

        Refund primerRefund = gateway.crearRefund(intent.getId(), key);
        Refund refundReintentado = gateway.crearRefund(intent.getId(), key);

        assertThat(refundReintentado.getId()).isEqualTo(primerRefund.getId());
    }
}
