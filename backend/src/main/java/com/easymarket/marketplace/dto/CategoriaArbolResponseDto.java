package com.easymarket.marketplace.dto;

import com.easymarket.marketplace.model.Categoria;

import java.util.List;

/**
 * DTO de respuesta HTTP para la exposición del árbol completo de categorías y sus subcategorías anidadas (Story 4, spec.md).
 *
 * <p>Compatible con el tipo de datos esperado en la UI frontend ({@code CategoriaItem}).</p>
 *
 * @param id identificador único de la categoría raíz
 * @param nombre nombre de la categoría raíz
 * @param subcategorias lista de subcategorías anidadas asociadas a la categoría
 */
public record CategoriaArbolResponseDto(
        Long id,
        String nombre,
        List<SubcategoriaItemResponseDto> subcategorias
) {
    /**
     * DTO interno para representar cada subcategoría anidada dentro del árbol.
     *
     * @param id identificador único de la subcategoría
     * @param nombre nombre de la subcategoría
     */
    public record SubcategoriaItemResponseDto(
            Long id,
            String nombre
    ) {
    }
}
