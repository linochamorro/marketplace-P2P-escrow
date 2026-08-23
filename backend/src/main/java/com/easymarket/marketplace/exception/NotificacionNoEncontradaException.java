package com.easymarket.marketplace.exception;

/**
 * Excepción lanzada cuando una notificación in-app no existe.
 *
 * <p>Corresponde al endpoint {@code PATCH /notificaciones/{id}/leer} (PHA09TSK05) y a
 * {@link NotificacionService#marcarComoLeida}. El controlador traduce esta excepción a
 * HTTP 404 Not Found.</p>
 */
public class NotificacionNoEncontradaException extends RuntimeException {

    /**
     * Construye la excepción con mensaje descriptivo.
     *
     * @param mensaje detalle del error (p. ej. "Notificación con ID 42 no encontrada")
     */
    public NotificacionNoEncontradaException(String mensaje) {
        super(mensaje);
    }
}