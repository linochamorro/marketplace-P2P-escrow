package com.easymarket.marketplace.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * Entidad JPA que representa una clave de idempotencia de compra (doble-submit) (Story 5,
 * spec.md; plan.md, secciones "Idempotencia de compra (doble-submit)" y "Flujo de compra y
 * reserva de stock (PHA03)").
 *
 * <p>Mapea la tabla {@code idempotency_keys} creada por la migración V8 (PHA03TSK02): la clave
 * primaria es la {@code key} (UUID v4 generada por el frontend al montar el botón de "Comprar").
 * La columna {@code transaccion_id} es nullable porque se puebla en dos momentos distintos:
 * primero solo {@code payment_intent_id} (cuando {@code POST /compras} crea el PaymentIntent),
 * y luego {@code transaccion_id} cuando el webhook {@code payment_intent.succeeded} crea la
 * transacción (plan.md: "el payment intent en vuelo no tiene representación como transacción,
 * solo como fila en idempotency_keys con payment_intent_id"). La FK a {@code transacciones} es
 * nullable por diseño: una fila con {@code transaccion_id = NULL} indica "pago en vuelo".</p>
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey {

    @Id
    @Column(name = "key", length = 36, nullable = false)
    private String key;

    @Column(name = "transaccion_id")
    private Long transaccionId;

    @Column(name = "payment_intent_id", length = 255)
    private String paymentIntentId;

    @Column(name = "timestamp", nullable = false)
    private ZonedDateTime timestamp;

    /**
     * Constructor por defecto requerido por JPA.
     */
    public IdempotencyKey() {
    }

    /**
     * Constructor para crear una nueva clave de idempotencia con el {@code paymentIntentId}
     * y el timestamp actual, sin {@code transaccionId} (fase "pago en vuelo").
     *
     * @param key             clave UUID v4 generada por el frontend
     * @param paymentIntentId ID del PaymentIntent de Stripe creado en {@code POST /compras}
     * @param timestamp       marca temporal de la creación de la key
     */
    public IdempotencyKey(String key, String paymentIntentId, ZonedDateTime timestamp) {
        this.key = key;
        this.paymentIntentId = paymentIntentId;
        this.timestamp = timestamp;
    }

    /**
     * Obtiene la clave UUID de idempotencia (PK natural de la tabla).
     *
     * @return clave UUID v4 generada por el frontend
     */
    public String getKey() {
        return key;
    }

    /**
     * Establece la clave UUID de idempotencia.
     *
     * @param key clave UUID v4 generada por el frontend
     */
    public void setKey(String key) {
        this.key = key;
    }

    /**
     * Obtiene el ID de la transacción asociada, si ya se completó la compra.
     *
     * @return ID de la transacción, o {@code null} si la compra aún no se completó
     *         (fase "pago en vuelo")
     */
    public Long getTransaccionId() {
        return transaccionId;
    }

    /**
     * Establece el ID de la transacción asociada (poblado por el webhook
     * {@code payment_intent.succeeded}, PHA03TSK08).
     *
     * @param transaccionId ID de la transacción creada por el webhook
     */
    public void setTransaccionId(Long transaccionId) {
        this.transaccionId = transaccionId;
    }

    /**
     * Obtiene el ID del PaymentIntent de Stripe asociado a esta operación.
     *
     * @return ID del PaymentIntent de Stripe
     */
    public String getPaymentIntentId() {
        return paymentIntentId;
    }

    /**
     * Establece el ID del PaymentIntent de Stripe asociado.
     *
     * @param paymentIntentId ID del PaymentIntent de Stripe
     */
    public void setPaymentIntentId(String paymentIntentId) {
        this.paymentIntentId = paymentIntentId;
    }

    /**
     * Obtiene la marca temporal de creación de la clave de idempotencia.
     *
     * @return marca temporal de creación
     */
    public ZonedDateTime getTimestamp() {
        return timestamp;
    }

    /**
     * Establece la marca temporal de creación de la clave de idempotencia.
     *
     * @param timestamp marca temporal de creación
     */
    public void setTimestamp(ZonedDateTime timestamp) {
        this.timestamp = timestamp;
    }

    /**
     * Compara esta clave con otro objeto usando la clave primaria natural (UUID).
     *
     * @param o objeto a comparar contra esta clave
     * @return {@code true} si ambos objetos son la misma instancia o si son claves con el
     *         mismo UUID; {@code false} en cualquier otro caso
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        IdempotencyKey that = (IdempotencyKey) o;
        return Objects.equals(key, that.key);
    }

    /**
     * Calcula el hash basado en la clave primaria natural (UUID).
     *
     * @return hash de la clave UUID de esta idempotency key
     */
    @Override
    public int hashCode() {
        return Objects.hash(key);
    }
}