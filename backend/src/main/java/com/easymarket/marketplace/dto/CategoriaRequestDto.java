package com.easymarket.marketplace.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * DTO de solicitud HTTP para la creación y edición de categorías raíz (Story 4, spec.md).
 *
 * @param nombre nombre único de la categoría raíz
 */
public record CategoriaRequestDto(
        @NotBlank(message = "El nombre de la categoría no puede estar vacío")
        String nombre
) {
}
