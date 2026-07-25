package com.easymarket.marketplace.exception;

/**
 * Excepción lanzada cuando una combinación de email y dirección IP se encuentra en estado bloqueado.
 *
 * <p>Aplica cuando el número de intentos fallidos supera los umbrales configurados (5m, 30m, 24h, permanente)
 * de acuerdo a la Story 0b de {@code spec.md}.</p>
 */
public class CuentaBloqueadaException extends RuntimeException {

    /**
     * Construye una nueva excepción con el mensaje de bloqueo especificado.
     *
     * @param message mensaje descriptivo del estado de bloqueo y tiempo restante si aplica
     */
    public CuentaBloqueadaException(String message) {
        super(message);
    }
}
