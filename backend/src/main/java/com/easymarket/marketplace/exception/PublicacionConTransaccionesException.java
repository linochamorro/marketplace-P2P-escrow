package com.easymarket.marketplace.exception;

/**
 * Excepción de dominio lanzada cuando se intenta eliminar una publicación que tiene al menos
 * una transacción asociada.
 *
 * <p>Decisión de negocio de Lino 2026-08-23 (plan.md, "PHA12 — Eliminación de publicaciones
 * con transacciones asociadas"): una publicación referenciada por {@code transacciones.publicacion_id}
 * (FK {@code fk_transacciones_publicacion}, V7) NO es eliminable. El handler correspondiente en
 * {@code GlobalExceptionHandler} la mapea a HTTP 409 Conflict con el mensaje en el cuerpo JSON.</p>
 */
public class PublicacionConTransaccionesException extends RuntimeException {

    /**
     * Construye la excepción con un mensaje descriptivo apto para el usuario final.
     *
     * @param message mensaje descriptivo del motivo del rechazo
     */
    public PublicacionConTransaccionesException(String message) {
        super(message);
    }
}
