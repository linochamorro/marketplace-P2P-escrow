package com.easymarket.marketplace.service;

import com.easymarket.marketplace.model.StripeRefundOutbox;
import com.easymarket.marketplace.repository.StripeRefundOutboxRepository;
import com.stripe.exception.StripeException;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;

/**
 * Processes durable Stripe refund orders after their short database-locking transaction ends.
 */
@Service
public class StripeRefundOutboxProcessor {

    private final StripeRefundOutboxSelectionService selectionService;
    private final StripeRefundOutboxRepository stripeRefundOutboxRepository;
    private final StripeRefundGateway stripeRefundGateway;

    /**
     * Creates the processor that submits and records Stripe refund requests.
     *
     * @param selectionService service that selects locked pending orders in a short transaction
     * @param stripeRefundOutboxRepository repository that persists each result
     * @param stripeRefundGateway Stripe API boundary
     */
    public StripeRefundOutboxProcessor(StripeRefundOutboxSelectionService selectionService,
                                       StripeRefundOutboxRepository stripeRefundOutboxRepository,
                                       StripeRefundGateway stripeRefundGateway) {
        this.selectionService = selectionService;
        this.stripeRefundOutboxRepository = stripeRefundOutboxRepository;
        this.stripeRefundGateway = stripeRefundGateway;
    }

    /**
     * Submits every currently pending order and persists its outcome without propagating Stripe failures.
     *
     * <p>This method is intentionally not transactional: {@link StripeRefundGateway#crearRefund(String,
     * String)} runs after {@link StripeRefundOutboxSelectionService#seleccionarPendientes()} commits.
     * Repeated external calls retain the same Stripe idempotency key.</p>
     */
    public void procesarPendientes() {
        for (StripeRefundOutbox orden : selectionService.seleccionarPendientes()) {
            procesar(orden);
        }
    }

    /**
     * Submits one selected order and records either Stripe acceptance or its retryable failure.
     *
     * @param orden pending durable order selected by the locking transaction
     */
    private void procesar(StripeRefundOutbox orden) {
        ZonedDateTime ahora = ZonedDateTime.now();
        try {
            stripeRefundGateway.crearRefund(orden.getPaymentIntentId(), orden.getIdempotencyKey());
            orden.registrarSolicitudAceptada(ahora);
        } catch (StripeException exception) {
            orden.registrarFallo(exception.getMessage(), ahora);
        }
        stripeRefundOutboxRepository.save(orden);
    }
}
