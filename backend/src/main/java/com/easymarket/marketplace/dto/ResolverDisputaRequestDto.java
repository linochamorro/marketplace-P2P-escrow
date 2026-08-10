package com.easymarket.marketplace.dto;

/**
 * Cuerpo obligatorio para resolver una disputa administrativa (Story 9, spec.md).
 *
 * <p>Expone la decisión binaria como texto y el motivo auditable. La decisión se traduce en la
 * capa de presentación con {@code ResolucionDisputa.valueOf(...)}: una cadena que no pertenece al
 * enum se rechaza en el controlador con {@code DecisionResolucionDisputaInvalidaException}; la
 * ausencia del campo y {@code null} se delegan como {@code null} al dominio, que valida y rechaza
 * con el mismo código 400 (la obligatoriedad pertenece al dominio). El motivo se delega sin
 * validación propia al servicio, que rechaza el cuerpo ausente, {@code null}, la cadena vacía y
 * los blancos con {@code MotivoResolucionDisputaObligatorioException} antes de escribir.</p>
 *
 * <p>No declara validaciones propias: la obligatoriedad de ambos campos pertenece al dominio
 * (constitución, principio 6 — sin scope creep en la capa de presentación).</p>
 *
 * @param decision texto de la decisión binaria ({@code A_FAVOR_VENDEDOR} o
 *                 {@code A_FAVOR_COMPRADOR}), o {@code null} cuando el cuerpo está ausente o el
 *                 campo llega nulo para delegar la validación al dominio
 * @param motivo texto obligatorio que audita la resolución, o {@code null} cuando el cuerpo está
 *               ausente o el campo llega nulo para delegar la validación al dominio
 */
public record ResolverDisputaRequestDto(String decision, String motivo) {
}