package com.easymarket.marketplace.exception;

/**
 * Excepción lanzada cuando no se encuentra una categoría o subcategoría requerida por su identificador.
 */
public class CategoriaNoEncontradaException extends RuntimeException {

    /**
     * Construye una nueva excepción con el mensaje de error especificado.
     *
     * @param message mensaje descriptivo de entidad no encontrada
     */
    public CategoriaNoEncontradaException(String message) {
        super(message);
    }
}
