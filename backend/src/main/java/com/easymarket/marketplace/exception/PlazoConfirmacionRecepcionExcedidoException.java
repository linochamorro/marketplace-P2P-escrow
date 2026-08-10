package com.easymarket.marketplace.exception;

/**
 * Excepción lanzada cuando una confirmación de recepción no puede probar que ocurrió dentro de
 * las 48 horas posteriores a la entrega.
 *
 * <p>También cubre una transacción entregada sin {@code fecha_entregado}: sin ese timestamp no es
 * posible comprobar el plazo exigido por la Story 6c, por lo que no se liberan fondos.</p>
 */
public class PlazoConfirmacionRecepcionExcedidoException extends RuntimeException {

    /**
     * Construye la excepción con el detalle del plazo inválido.
     *
     * @param message mensaje descriptivo de la fecha de entrega o del vencimiento detectado
     */
    public PlazoConfirmacionRecepcionExcedidoException(String message) {
        super(message);
    }
}
