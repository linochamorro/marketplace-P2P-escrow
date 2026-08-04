package com.easymarket.marketplace.exception;

/**
 * Excepción lanzada cuando el precio de una publicación no cumple la restricción de ser un entero mayor a cero.
 */
public class PrecioInvalidoException extends RuntimeException {

    /**
     * Construye una nueva excepción con el mensaje de error especificado.
     *
     * @param message mensaje descriptivo del fallo por precio inválido
     */
    public PrecioInvalidoException(String message) {
        super(message);
    }
}
