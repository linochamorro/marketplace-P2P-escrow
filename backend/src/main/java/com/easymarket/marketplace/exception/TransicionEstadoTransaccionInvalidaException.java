package com.easymarket.marketplace.exception;

/**
 * Excepción lanzada cuando se solicita una transición no permitida por la máquina de estados de
 * una transacción.
 */
public class TransicionEstadoTransaccionInvalidaException extends RuntimeException {

    /**
     * Construye la excepción con el detalle del estado origen y destino no permitidos.
     *
     * @param message mensaje descriptivo de la transición inválida
     */
    public TransicionEstadoTransaccionInvalidaException(String message) {
        super(message);
    }
}
