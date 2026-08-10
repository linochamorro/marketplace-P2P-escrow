package com.easymarket.marketplace.dto;

/**
 * Cuerpo opcional para registrar una prueba textual al marcar una transacción como entregada.
 *
 * <p>La ausencia completa del cuerpo y un valor {@code null} para
 * {@code descripcionPruebaEntrega} representan la misma operación: una entrega sin prueba
 * descriptiva, permitida por la Story 6b.</p>
 *
 * @param descripcionPruebaEntrega texto opcional de prueba de entrega, o {@code null} si no se
 *                                 proporciona
 */
public record PruebaEntregaRequestDto(String descripcionPruebaEntrega) {
}
