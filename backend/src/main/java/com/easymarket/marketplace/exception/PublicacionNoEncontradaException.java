package com.easymarket.marketplace.exception;

/**
 * Excepción lanzada cuando no se encuentra una publicación en el sistema por su identificador único.
 */
public class PublicacionNoEncontradaException extends RuntimeException {

    /**
     * Construye una nueva excepción con el mensaje de error especificado.
     *
     * @param message mensaje descriptivo del fallo por publicación inexistente
     */
    public PublicacionNoEncontradaException(String message) {
        super(message);
    }
}
