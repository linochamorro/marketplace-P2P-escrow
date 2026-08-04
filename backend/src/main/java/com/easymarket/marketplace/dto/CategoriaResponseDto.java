package com.easymarket.marketplace.dto;

import com.easymarket.marketplace.model.Categoria;

/**
 * DTO de respuesta HTTP para la exposición de categorías raíz en el API (Story 4, spec.md).
 *
 * @param id identificador único de la categoría
 * @param nombre nombre de la categoría
 */
public record CategoriaResponseDto(
        Long id,
        String nombre
) {
    /**
     * Mapea una entidad {@link Categoria} a su representación DTO de respuesta.
     *
     * @param categoria entidad JPA de origen
     * @return DTO {@link CategoriaResponseDto} resultante
     */
    public static CategoriaResponseDto fromEntity(Categoria categoria) {
        return new CategoriaResponseDto(categoria.getId(), categoria.getNombre());
    }
}
