package com.easymarket.marketplace.exception;

/**
 * Excepción lanzada cuando una transición de estado de publicación exige un motivo obligatorio y este no es provisto o es nulo/vacío.
 */
public class MotivoRequeridoException extends RuntimeException {

    /**
     * Construye una nueva excepción con el mensaje especificado.
     *
     * @param message mensaje descriptivo del motivo faltante
     */
    public MotivoRequeridoException(String message) {
        super(message);
    }
}
