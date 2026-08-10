package com.easymarket.marketplace.repository;

import com.easymarket.marketplace.model.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repositorio JPA para la entidad {@link IdempotencyKey} (tabla {@code idempotency_keys}, migración
 * V8, PHA03TSK02).
 *
 * <p>Provee acceso a las claves de idempotencia de compra para el mecanismo de guardia contra
 * doble-submit (plan.md, secciones "Idempotencia de compra (doble-submit)" y "Flujo de compra y
 * reserva de stock (PHA03)"). El método {@code findById} (heredado de {@link JpaRepository}) se
 * usa en {@link com.easymarket.marketplace.service.IdempotenciaCompraService} para buscar la key
 * por su UUID. El método {@code findByPaymentIntentId} se usa en PHA03TSK08 (procesamiento de
 * webhooks) para localizar la fila de la key por su {@code payment_intent_id} al llegar el evento
 * {@code payment_intent.succeeded} y poblar el {@code transaccion_id}.</p>
 */
@Repository
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, String> {

    /**
     * Busca una clave de idempotencia por el ID del PaymentIntent de Stripe asociado.
     *
     * <p>Este método se usa en PHA03TSK08 (procesamiento de webhooks Stripe) para localizar la
     * fila de idempotencia cuando llega {@code payment_intent.succeeded}, de modo que se pueda
     * poblar el {@code transaccion_id} sobre la misma fila (plan.md, "Flujo de compra y reserva
     * de stock (PHA03)": "transaccion_id se puebla recién cuando el webhook payment_intent.succeeded
     * crea la transacción").</p>
     *
     * @param paymentIntentId ID del PaymentIntent de Stripe
     * @return un {@link Optional} conteniendo la entidad si existe, o vacío si no
     */
    Optional<IdempotencyKey> findByPaymentIntentId(String paymentIntentId);

    /**
     * Busca la correlación de compra que el webhook vinculó a una transacción ya creada.
     *
     * @param transaccionId ID de la transacción cuya correlación Stripe se necesita
     * @return clave de idempotencia con PaymentIntent, o vacío si no existe
     */
    Optional<IdempotencyKey> findByTransaccionId(Long transaccionId);
}
