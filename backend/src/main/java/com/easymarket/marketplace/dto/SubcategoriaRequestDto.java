package com.easymarket.marketplace.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * DTO de solicitud HTTP para la creación y edición de subcategorías (Story 4, spec.md).
 *
 * @param nombre nombre único de la subcategoría dentro de la categoría padre
 */
public record SubcategoriaRequestDto(
        @NotBlank(message = "El nombre de la subcategoría no puede estar vacío")
        String nombre
) {
}
