package com.easymarket.marketplace.dto;

import jakarta.validation.constraints.Min;

/**
 * DTO de solicitud para la edición parcial de una publicación por su vendedor propietario (Stories 3 y 10, spec.md).
 *
 * <p>Todos los campos son opcionales. No permite la edición de categoría o subcategoría.</p>
 *
 * @param precio nuevo precio en centavos (monto entero > 0 si se proporciona)
 * @param stock nuevo stock disponible (entero >= 0 si se proporciona)
 * @param descripcion nueva descripción del producto
 */
public record EditarPublicacionRequestDto(
        @Min(value = 1, message = "El precio debe ser un monto entero positivo mayor a cero")
        Long precio,

        @Min(value = 0, message = "El stock no puede ser negativo")
        Integer stock,

        String descripcion
) {
}
