package com.easymarket.marketplace.dto;

import com.easymarket.marketplace.model.Subcategoria;

/**
 * DTO de respuesta HTTP para la exposición de subcategorías en el API (Story 4, spec.md).
 *
 * @param id identificador único de la subcategoría
 * @param nombre nombre de la subcategoría
 * @param categoriaId identificador de la categoría raíz padre
 */
public record SubcategoriaResponseDto(
        Long id,
        String nombre,
        Long categoriaId
) {
    /**
     * Mapea una entidad {@link Subcategoria} a su representación DTO de respuesta.
     *
     * @param subcategoria entidad JPA de origen
     * @return DTO {@link SubcategoriaResponseDto} resultante
     */
    public static SubcategoriaResponseDto fromEntity(Subcategoria subcategoria) {
        return new SubcategoriaResponseDto(
                subcategoria.getId(),
                subcategoria.getNombre(),
                subcategoria.getCategoria().getId()
        );
    }
}
