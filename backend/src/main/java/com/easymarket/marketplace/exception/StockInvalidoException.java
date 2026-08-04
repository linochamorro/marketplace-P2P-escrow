package com.easymarket.marketplace.exception;

/**
 * Excepción lanzada cuando el stock especificado al crear una publicación es menor a 1 unidad.
 */
public class StockInvalidoException extends RuntimeException {

    /**
     * Construye una nueva excepción con el mensaje de error especificado.
     *
     * @param message mensaje descriptivo del fallo por stock inválido
     */
    public StockInvalidoException(String message) {
        super(message);
    }
}
