package com.easymarket.marketplace.exception;

/**
 * Excepción lanzada cuando se intenta eliminar una categoría o subcategoría
 * que posee publicaciones asociadas.
 */
public class CategoriaConPublicacionesException extends RuntimeException {

    /**
     * Construye una nueva excepción con el mensaje de error especificado.
     *
     * @param message mensaje descriptivo del rechazo de eliminación
     */
    public CategoriaConPublicacionesException(String message) {
        super(message);
    }
}
