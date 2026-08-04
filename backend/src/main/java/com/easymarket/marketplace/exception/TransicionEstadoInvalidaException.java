package com.easymarket.marketplace.exception;

/**
 * Excepción lanzada cuando se intenta realizar una transición de estado no permitida en la máquina de estados de publicación.
 */
public class TransicionEstadoInvalidaException extends RuntimeException {

    /**
     * Construye una nueva excepción con el mensaje especificado.
     *
     * @param message mensaje descriptivo del error de transición inválida
     */
    public TransicionEstadoInvalidaException(String message) {
        super(message);
    }
}
