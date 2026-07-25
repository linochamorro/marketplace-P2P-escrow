package com.easymarket.marketplace.exception;

/**
 * Excepción lanzada cuando la verificación de credenciales de inicio de sesión falla.
 *
 * <p>Mantiene un mensaje genérico para evitar la enumeración de usuarios en el sistema,
 * conforme al criterio de la Story 0b de {@code spec.md}.</p>
 */
public class CredencialesInvalidasException extends RuntimeException {

    /**
     * Construye una nueva excepción con el mensaje genérico de error especificado.
     *
     * @param message mensaje genérico descriptivo del fallo de autenticación
     */
    public CredencialesInvalidasException(String message) {
        super(message);
    }
}
