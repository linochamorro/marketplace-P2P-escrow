package com.easymarket.marketplace.exception;

/**
 * Excepción lanzada cuando se intenta editar los campos de una publicación cuyo estado no permite edición directa.
 */
public class EstadoPublicacionNoEditableException extends RuntimeException {

    /**
     * Construye una nueva excepción con el mensaje especificado.
     *
     * @param message mensaje descriptivo del error de estado no editable
     */
    public EstadoPublicacionNoEditableException(String message) {
        super(message);
    }
}
