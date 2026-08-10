package com.easymarket.marketplace.exception;

/**
 * Indica que una transacción cancelable no tiene la correlación durable con su PaymentIntent.
 */
public class PaymentIntentTransaccionNoEncontradoException extends RuntimeException {

    /**
     * Construye la excepción con el detalle de la correlación ausente.
     *
     * @param message explicación del motivo de rechazo
     */
    public PaymentIntentTransaccionNoEncontradoException(String message) {
        super(message);
    }
}
