package com.easymarket.marketplace.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * DTO de solicitud HTTP para la creación de publicaciones de venta (Story 1, spec.md).
 *
 * <p>No incluye {@code usuarioId} (el vendedor se determina desde la identidad autenticada JWT)
 * ni {@code estado} (que inicia obligatoriamente en {@code PENDIENTE_REVISION}).</p>
 *
 * @param precio precio del producto en centavos de moneda entera (ej. 1500000 = $15,000.00)
 * @param stock cantidad de unidades disponibles para la venta (mínimo 1)
 * @param categoriaId identificador de la categoría raíz seleccionada
 * @param subcategoriaId identificador de la subcategoría seleccionada
 * @param descripcion texto explicativo con los detalles del producto en venta
 * @param imagenFilename nombre de archivo de imagen opcional (sin prefijo de URL)
 */
public record PublicacionRequestDto(
        @NotNull(message = "El precio es obligatorio")
        @Min(value = 1, message = "El precio debe ser estrictamente mayor a 0")
        Long precio,

        @NotNull(message = "El stock es obligatorio")
        @Min(value = 1, message = "El stock inicial debe ser de al menos 1 unidad")
        Integer stock,

        @NotNull(message = "La categoría es obligatoria")
        Long categoriaId,

        @NotNull(message = "La subcategoría es obligatoria")
        Long subcategoriaId,

        @NotBlank(message = "La descripción no puede estar vacía")
        String descripcion,

        String imagenFilename
) {
}
