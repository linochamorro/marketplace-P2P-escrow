package com.easymarket.marketplace.exception;

/**
 * Indica que el actor no está autorizado por la máquina de estados para cancelar una transacción.
 */
public class ActorNoAutorizadoParaCancelarTransaccionException extends RuntimeException {

    /**
     * Construye la excepción con el detalle de la autorización denegada.
     *
     * @param message explicación del motivo de rechazo
     */
    public ActorNoAutorizadoParaCancelarTransaccionException(String message) {
        super(message);
    }
}
