package com.easymarket.marketplace.exception;

/**
 * Excepción lanzada cuando se intenta realizar un desbloqueo administrativo sobre una cuenta/IP
 * que no se encuentra en estado de bloqueo permanente (Story 0c, spec.md).
 */
public class CuentaNoBloqueadaPermanentementeException extends RuntimeException {

    /**
     * Construye una nueva excepción con el mensaje especificado.
     *
     * @param message mensaje explicativo del rechazo de la operación
     */
    public CuentaNoBloqueadaPermanentementeException(String message) {
        super(message);
    }
}
