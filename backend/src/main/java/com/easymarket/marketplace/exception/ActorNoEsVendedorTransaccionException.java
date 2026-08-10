package com.easymarket.marketplace.exception;

/**
 * Excepción lanzada cuando un actor distinto del vendedor dueño de una publicación intenta
 * transicionar la transacción asociada a ella.
 */
public class ActorNoEsVendedorTransaccionException extends RuntimeException {

    /**
     * Construye la excepción con el detalle del actor no autorizado.
     *
     * @param message mensaje descriptivo del fallo de autorización de dominio
     */
    public ActorNoEsVendedorTransaccionException(String message) {
        super(message);
    }
}
