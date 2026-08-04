package com.easymarket.marketplace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * DTO de solicitud para la moderación administrativa de publicaciones (Story 2, spec.md).
 *
 * @param accion la acción tomada por el admin ('aprobar', 'solicitar-cambios', 'rechazar')
 * @param motivo motivo opcional u obligatorio dependiendo de la acción (requerido para 'solicitar-cambios' y 'rechazar')
 */
public record ModeracionPublicacionRequestDto(
        @NotBlank(message = "La acción es obligatoria")
        @Pattern(regexp = "^(aprobar|solicitar-cambios|rechazar)$", message = "La acción debe ser 'aprobar', 'solicitar-cambios' o 'rechazar'")
        String accion,

        String motivo
) {
}
