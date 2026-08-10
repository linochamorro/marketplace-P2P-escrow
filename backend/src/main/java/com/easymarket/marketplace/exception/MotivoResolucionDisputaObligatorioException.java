package com.easymarket.marketplace.exception;

/**
 * Indica que una resolución administrativa de disputa no contiene el motivo auditable de Story 9.
 */
public class MotivoResolucionDisputaObligatorioException extends RuntimeException {

    /**
     * Construye la excepción con el motivo del rechazo de la resolución.
     *
     * @param message explicación apta para traducir en la capa de errores de dominio
     */
    public MotivoResolucionDisputaObligatorioException(String message) {
        super(message);
    }
}
