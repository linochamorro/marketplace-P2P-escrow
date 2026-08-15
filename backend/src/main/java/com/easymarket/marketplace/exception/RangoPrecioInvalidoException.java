package com.easymarket.marketplace.exception;

/**
 * Excepción lanzada cuando los límites de un rango de precio son incompatibles.
 *
 * <p>Representa específicamente el caso en que un límite mínimo es mayor que el límite máximo;
 * no valida el precio individual de una publicación.</p>
 */
public class RangoPrecioInvalidoException extends RuntimeException {

    /**
     * Construye una excepción para un rango de precio invertido.
     *
     * @param message mensaje descriptivo del rango inválido
     */
    public RangoPrecioInvalidoException(String message) {
        super(message);
    }
}
