package com.easymarket.marketplace.exception;

/**
 * Indica que una cancelación fue solicitada sin el motivo obligatorio de Story 7.
 */
public class MotivoCancelacionObligatorioException extends RuntimeException {

    /**
     * Construye la excepción con un mensaje apto para la capa que traduce errores de dominio.
     *
     * @param message explicación del motivo de rechazo
     */
    public MotivoCancelacionObligatorioException(String message) {
        super(message);
    }
}
