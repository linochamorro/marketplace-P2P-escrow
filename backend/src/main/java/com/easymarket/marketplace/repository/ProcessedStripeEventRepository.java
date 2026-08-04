package com.easymarket.marketplace.repository;

import com.easymarket.marketplace.model.ProcessedStripeEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repositorio JPA para la entidad {@link ProcessedStripeEvent} (tabla
 * {@code processed_stripe_events}, migración V8, PHA03TSK02).
 *
 * <p>Provee acceso a la tabla de idempotencia de webhooks de Stripe. El método
 * {@code existsById} (heredado de {@link JpaRepository}) se usa en PHA03TSK08 para
 * verificar si un evento ya fue procesado antes de realizar el INSERT. El método
 * {@code save} registra un nuevo evento procesado. Toda operación ocurre dentro de la
 * misma transacción atómica que la transición de estado de negocio (constitución,
 * principio 1; plan.md, "Idempotencia de webhooks Stripe": "el INSERT y la transición
 * de estado correspondiente ocurren en la misma transacción atómica").</p>
 */
@Repository
public interface ProcessedStripeEventRepository extends JpaRepository<ProcessedStripeEvent, String> {
}