package com.easymarket.marketplace.exception;

/**
 * Señala una inconsistencia de datos cuando falta la cuenta ADMIN única requerida por una operación de dominio.
 *
 * <p>La provisión de esa cuenta ocurre fuera del registro público según el plan del proyecto. La excepción evita
 * completar parcialmente una operación que exige notificar al administrador.</p>
 */
public class AdministradorNoEncontradoException extends RuntimeException {

    /**
     * Construye la excepción con el detalle de la inconsistencia encontrada.
     *
     * @param mensaje descripción de la cuenta ADMIN ausente
     */
    public AdministradorNoEncontradoException(String mensaje) {
        super(mensaje);
    }
}
