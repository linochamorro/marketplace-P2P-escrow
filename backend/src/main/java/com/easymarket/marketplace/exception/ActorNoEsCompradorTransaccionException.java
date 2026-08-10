package com.easymarket.marketplace.exception;

/**
 * Excepción lanzada cuando un actor distinto del comprador de una transacción intenta confirmar
 * su recepción.
 */
public class ActorNoEsCompradorTransaccionException extends RuntimeException {

    /**
     * Construye la excepción con el detalle del actor no autorizado.
     *
     * @param message mensaje descriptivo del fallo de autorización de dominio
     */
    public ActorNoEsCompradorTransaccionException(String message) {
        super(message);
    }
}
