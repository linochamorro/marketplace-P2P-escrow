package com.easymarket.marketplace.exception;

/**
 * Excepción lanzada cuando se intenta modificar la categoría o subcategoría de una publicación que se encuentra en estado aprobada.
 */
public class CategoriaPublicacionInmutableException extends RuntimeException {

    /**
     * Construye una nueva excepción con el mensaje especificado.
     *
     * @param message mensaje descriptivo del error de inmutabilidad de categoría/subcategoría
     */
    public CategoriaPublicacionInmutableException(String message) {
        super(message);
    }
}
