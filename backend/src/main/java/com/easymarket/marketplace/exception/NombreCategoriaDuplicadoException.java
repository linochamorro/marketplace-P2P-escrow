package com.easymarket.marketplace.exception;

/**
 * Excepción lanzada cuando se intenta crear o editar una categoría o subcategoría
 * con un nombre que viola la restricción de unicidad por nivel jerárquico.
 */
public class NombreCategoriaDuplicadoException extends RuntimeException {

    /**
     * Construye una nueva excepción con el mensaje de error especificado.
     *
     * @param message mensaje descriptivo del error de nombre duplicado
     */
    public NombreCategoriaDuplicadoException(String message) {
        super(message);
    }
}
