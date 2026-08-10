package com.easymarket.marketplace.exception;

/**
 * Excepción lanzada cuando no se encuentra una transacción por su identificador persistente.
 */
public class TransaccionNoEncontradaException extends RuntimeException {

    /**
     * Construye la excepción con el detalle de la transacción inexistente.
     *
     * @param message mensaje descriptivo del identificador no encontrado
     */
    public TransaccionNoEncontradaException(String message) {
        super(message);
    }
}
