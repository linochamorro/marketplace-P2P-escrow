package com.easymarket.marketplace.exception;

/**
 * Excepción de dominio lanzada cuando se intenta comprar una publicación sin stock disponible
 * (stock &lt; 1), violando la precondición "hay stock disponible ≥ 1" de la Story 5 de
 * {@code spec.md} y del flujo de compra de {@code plan.md}.
 */
public class StockAgotadoException extends RuntimeException {

    /**
     * Construye una nueva excepción con el mensaje de error especificado.
     *
     * @param message mensaje descriptivo del rechazo por stock agotado
     */
    public StockAgotadoException(String message) {
        super(message);
    }
}
