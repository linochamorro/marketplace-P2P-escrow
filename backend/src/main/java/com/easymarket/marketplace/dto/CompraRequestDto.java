package com.easymarket.marketplace.dto;

import jakarta.validation.constraints.NotNull;

/**
 * DTO de request para el endpoint {@code POST /compras} (PHA03TSK09, Story 5, spec.md).
 *
 * <p>Transporta únicamente el identificador de la publicación que el usuario autenticado
 * desea comprar. El identificador del comprador NO se acepta en el body: se obtiene de forma
 * segura de la identidad del JWT ({@code @AuthenticationPrincipal UsuarioPrincipal}), regla
 * declarada en la coreografía de compra de plan.md ("Flujo de compra y reserva de stock
 * (PHA03)").</p>
 *
 * @param publicacionId ID de la publicación a comprar (obligatorio, no nulo)
 */
public record CompraRequestDto(
        @NotNull(message = "El campo 'publicacionId' es obligatorio")
        Long publicacionId
) {
}
