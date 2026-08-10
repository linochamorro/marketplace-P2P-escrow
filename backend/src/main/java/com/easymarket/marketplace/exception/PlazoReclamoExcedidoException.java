package com.easymarket.marketplace.exception;

/**
 * Excepción lanzada cuando un reclamo no puede probar que ocurrió dentro de las 48 horas
 * posteriores a la entrega.
 *
 * <p>También cubre una transacción entregada sin {@code fecha_entregado}: sin ese timestamp no es
 * posible comprobar la ventana exigida por la Story 6d, por lo que no se transiciona a disputa.</p>
 */
public class PlazoReclamoExcedidoException extends RuntimeException {

    /**
     * Construye la excepción con el detalle del plazo inválido.
     *
     * @param message mensaje descriptivo de la fecha de entrega o del vencimiento detectado
     */
    public PlazoReclamoExcedidoException(String message) {
        super(message);
    }
}
