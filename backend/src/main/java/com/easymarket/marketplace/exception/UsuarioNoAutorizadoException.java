package com.easymarket.marketplace.exception;

/**
 * Excepción lanzada cuando un usuario intenta operar sobre un recurso que no le pertenece.
 *
 * <p>Corresponde a {@link NotificacionService#marcarComoLeida} (PHA09TSK05) cuando el
 * usuario autenticado no coincide con el destinatario de la notificación. El controlador
 * traduce esta excepción a HTTP 403 Forbidden.</p>
 */
public class UsuarioNoAutorizadoException extends RuntimeException {

    /**
     * Construye la excepción con mensaje descriptivo.
     *
     * @param mensaje detalle del error (p. ej. "El usuario 5 no es el destinatario de la notificación 42")
     */
    public UsuarioNoAutorizadoException(String mensaje) {
        super(mensaje);
    }
}