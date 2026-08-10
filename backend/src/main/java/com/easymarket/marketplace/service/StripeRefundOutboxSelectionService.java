package com.easymarket.marketplace.service;

import com.easymarket.marketplace.model.StripeRefundOutbox;
import com.easymarket.marketplace.repository.StripeRefundOutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Performs the short PostgreSQL locking transaction used to select refund orders.
 */
@Service
public class StripeRefundOutboxSelectionService {

    private final StripeRefundOutboxRepository stripeRefundOutboxRepository;

    /**
     * Creates the service that acquires pending refund orders.
     *
     * @param stripeRefundOutboxRepository repository that executes the PostgreSQL locking query
     */
    public StripeRefundOutboxSelectionService(StripeRefundOutboxRepository stripeRefundOutboxRepository) {
        this.stripeRefundOutboxRepository = stripeRefundOutboxRepository;
    }

    /**
     * Selects currently pending orders in a transaction limited to the database lock acquisition.
     *
     * <p>The returned orders are processed after this method commits; in particular, no Stripe call
     * occurs within this database transaction.</p>
     *
     * @return orders selected with {@code FOR UPDATE SKIP LOCKED}
     */
    @Transactional
    public List<StripeRefundOutbox> seleccionarPendientes() {
        return stripeRefundOutboxRepository.findPendientesForUpdateSkipLocked();
    }
}
