package com.easymarket.marketplace.exception;

/**
 * Excepción lanzada cuando se intenta eliminar definitivamente una publicación que no se encuentra
 * en estado rechazada (Story 3, spec.md).
 *
 * <p>Solo una publicación {@code rechazada} puede ser eliminada de forma definitiva por su
 * propietario; cualquier otro estado (aprobada, pendiente_revisión, cambios_solicitados u oculta)
 * rechaza la eliminación para no destruir publicaciones vigentes o en tránsito de moderación.</p>
 */
public class PublicacionNoEliminableException extends RuntimeException {

    /**
     * Construye una nueva excepción con el mensaje especificado.
     *
     * @param message mensaje descriptivo del rechazo de eliminación por estado no eliminable
     */
    public PublicacionNoEliminableException(String message) {
        super(message);
    }
}