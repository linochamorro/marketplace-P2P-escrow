package com.easymarket.marketplace.exception;

/**
 * Excepción lanzada cuando una contraseña no cumple con la política de seguridad mínima (≥8 caracteres).
 */
public class PasswordInvalidaException extends RuntimeException {

    /**
     * Construye una nueva excepción con el mensaje de error especificado.
     *
     * @param message mensaje descriptivo del fallo de política de contraseña
     */
    public PasswordInvalidaException(String message) {
        super(message);
    }
}
