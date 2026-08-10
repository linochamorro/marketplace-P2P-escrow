package com.easymarket.marketplace.dto;

/**
 * Cuerpo opcional para registrar el motivo textual libre de un reclamo de comprador.
 *
 * <p>La ausencia completa del cuerpo y un valor {@code null} para {@code motivo} representan el
 * mismo reclamo sin motivo textual. Story 6d no hace obligatorio ese texto, por lo que el
 * controlador delega ambos casos como {@code null} a {@code ReclamoService}.</p>
 *
 * @param motivo texto libre del reclamo, o {@code null} cuando no se proporciona
 */
public record ReclamoRequestDto(String motivo) {
}
