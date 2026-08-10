package com.easymarket.marketplace.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Schedules durable Stripe refund submission every fifteen minutes.
 */
@Component
public class StripeRefundOutboxJob {

    private final StripeRefundOutboxProcessor stripeRefundOutboxProcessor;

    /**
     * Creates the scheduled refund job.
     *
     * @param stripeRefundOutboxProcessor processor that submits eligible orders to Stripe
     */
    public StripeRefundOutboxJob(StripeRefundOutboxProcessor stripeRefundOutboxProcessor) {
        this.stripeRefundOutboxProcessor = stripeRefundOutboxProcessor;
    }

    /**
     * Runs the durable refund poll every fifteen minutes.
     */
    @Scheduled(fixedDelayString = "PT15M")
    public void ejecutar() {
        stripeRefundOutboxProcessor.procesarPendientes();
    }
}
