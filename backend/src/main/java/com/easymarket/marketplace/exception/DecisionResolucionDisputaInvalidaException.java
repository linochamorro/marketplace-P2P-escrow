package com.easymarket.marketplace.exception;

/**
 * Indica que una resolución de disputa no recibió una de las decisiones binarias de Story 9.
 */
public class DecisionResolucionDisputaInvalidaException extends RuntimeException {

    /**
     * Construye la excepción con el motivo del rechazo de la decisión.
     *
     * @param message explicación apta para traducir en la capa de errores de dominio
     */
    public DecisionResolucionDisputaInvalidaException(String message) {
        super(message);
    }
}
