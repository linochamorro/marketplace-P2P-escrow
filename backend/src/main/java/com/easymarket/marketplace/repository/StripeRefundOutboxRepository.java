package com.easymarket.marketplace.repository;

import com.easymarket.marketplace.model.StripeRefundOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * Repositorio de las órdenes durables de reembolso que un procesador Stripe posterior consumirá.
 */
@Repository
public interface StripeRefundOutboxRepository extends JpaRepository<StripeRefundOutbox, Long> {

    /**
     * Obtiene y bloquea las órdenes pendientes sin esperar las que otro procesador ya bloqueó.
     *
     * @return órdenes pendientes bloqueadas exclusivamente para la transacción invocadora
     */
    @Query(value = "SELECT * FROM stripe_refund_outbox WHERE estado = 'PENDIENTE' FOR UPDATE SKIP LOCKED",
        nativeQuery = true)
    java.util.List<StripeRefundOutbox> findPendientesForUpdateSkipLocked();
}
