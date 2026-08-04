package com.easymarket.marketplace.exception;

/**
 * Excepción de dominio lanzada cuando el endpoint {@code POST /compras} (PHA03TSK09, Story 5,
 * spec.md) recibe una petición sin el header obligatorio {@code Idempotency-Key} o con el
 * header vacío.
 *
 * <p>El header es el mecanismo de guardia contra doble-submit definido en plan.md
 * ("Idempotencia de compra (doble-submit)") y en la coreografía de compra ("Flujo de compra
 * y reserva de stock (PHA03)"): sin él, el frontend no puede garantizar que un doble clic
 * no duplique la compra, por lo que la petición se rechaza con HTTP 400 Bad Request.</p>
 */
public class IdempotencyKeyRequeridaException extends RuntimeException {

    /**
     * Construye una nueva excepción con el mensaje descriptivo de la omisión del header.
     */
    public IdempotencyKeyRequeridaException() {
        super("El header 'Idempotency-Key' es obligatorio en POST /compras");
    }
}
