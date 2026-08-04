package com.easymarket.marketplace.exception;

/**
 * Excepción de dominio lanzada cuando se intenta usar una {@code Idempotency-Key} que ya existe
 * en la tabla {@code idempotency_keys} pero sin una transacción asociada ({@code transaccion_id}
 * es {@code null}), indicando que la operación de compra ya está en progreso (fase "pago en
 * vuelo" del plan.md, "Flujo de compra y reserva de stock (PHA03)") y no debe duplicarse.
 *
 * <p>Esta excepción forma parte del mecanismo de guardia contra doble-submit (Story 5, spec.md;
 * plan.md, "Idempotencia de compra (doble-submit)"): el segundo intento con la misma clave recibe
 * este error que el controlador {@code POST /compras} (PHA03TSK09) mapea a un código HTTP 409
 * Conflict.</p>
 */
public class IdempotencyKeyDuplicadaException extends RuntimeException {

    /**
     * Construye una nueva excepción con un mensaje descriptivo que incluye la clave duplicada.
     *
     * @param idempotencyKey la clave UUID de idempotencia que ya fue usada en una operación
     *                       en progreso
     */
    public IdempotencyKeyDuplicadaException(String idempotencyKey) {
        super("La clave de idempotencia '" + idempotencyKey + "' ya está siendo utilizada en una operación en progreso");
    }
}