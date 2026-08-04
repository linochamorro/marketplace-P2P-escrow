package com.easymarket.marketplace.exception;

/**
 * Excepción de dominio lanzada cuando un usuario intenta comprar su propia publicación
 * (auto-compra), violando la regla {@code transacción.comprador_id != publicación.usuario_id}
 * de la Story 5 de {@code spec.md}.
 */
public class AutoCompraNoPermitidaException extends RuntimeException {

    /**
     * Construye una nueva excepción con el mensaje de error especificado.
     *
     * @param message mensaje descriptivo del rechazo de auto-compra
     */
    public AutoCompraNoPermitidaException(String message) {
        super(message);
    }
}
