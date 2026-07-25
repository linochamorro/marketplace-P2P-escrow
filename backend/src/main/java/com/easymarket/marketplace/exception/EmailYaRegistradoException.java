package com.easymarket.marketplace.exception;

/**
 * Excepción lanzada cuando se intenta registrar un usuario con un email que ya existe en el sistema.
 */
public class EmailYaRegistradoException extends RuntimeException {

    /**
     * Construye una nueva excepción con el mensaje de error especificado.
     *
     * @param message mensaje descriptivo del fallo por email duplicado
     */
    public EmailYaRegistradoException(String message) {
        super(message);
    }
}
