package com.easymarket.marketplace.dto;

import jakarta.validation.constraints.NotNull;

/**
 * DTO de solicitud HTTP para la corrección de la clasificación de una publicación propia
 * (Story 3, spec.md; PHA06TSK05).
 *
 * <p>El cuerpo es exclusivamente la nueva pareja {@code categoriaId}/{@code subcategoriaId}:
 * el dominio cerrado de PHA06TSK03 no recibe precio, stock ni descripción en la corrección
 * (plan.md, "Corrección de publicación (Story 3)", enmendado 2026-08-15), por lo que agregarlos
 * aquí sería scope creep. Ambos campos son obligatorios: su ausencia produce HTTP 400 por la
 * validación {@code @Valid} del controlador, con el mismo patrón de {@link PublicacionRequestDto}.</p>
 *
 * @param categoriaId identificador de la nueva categoría raíz (obligatorio)
 * @param subcategoriaId identificador de la nueva subcategoría, que debe pertenecer a la categoría (obligatorio)
 */
public record CorregirPublicacionRequestDto(
        @NotNull(message = "La categoría es obligatoria")
        Long categoriaId,

        @NotNull(message = "La subcategoría es obligatoria")
        Long subcategoriaId
) {
}
