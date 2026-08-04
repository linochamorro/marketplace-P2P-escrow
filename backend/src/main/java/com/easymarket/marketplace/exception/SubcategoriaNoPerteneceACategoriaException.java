package com.easymarket.marketplace.exception;

/**
 * Excepción lanzada cuando se intenta asociar una subcategoría con una categoría raíz a la cual no pertenece.
 */
public class SubcategoriaNoPerteneceACategoriaException extends RuntimeException {

    /**
     * Construye una nueva excepción con el mensaje de error especificado.
     *
     * @param message mensaje descriptivo del fallo por incoherencia padre-hijo
     */
    public SubcategoriaNoPerteneceACategoriaException(String message) {
        super(message);
    }
}
