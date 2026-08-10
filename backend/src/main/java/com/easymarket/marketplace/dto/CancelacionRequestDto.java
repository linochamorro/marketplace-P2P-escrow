package com.easymarket.marketplace.dto;

/**
 * Cuerpo obligatorio para cancelar una transacción (Story 7, spec.md).
 *
 * <p>Expose únicamente el motivo de cancelación. A diferencia de
 * {@link ReclamoRequestDto} (Story 6d, motivo libre no obligatorio), el motivo aquí es
 * obligatorio sin excepción: el cuerpo ausente, {@code null}, la cadena vacía y los
 * blancos se delegan al servicio de dominio {@code CancelacionTransaccionService}, que
 * rechaza la operación con {@code MotivoCancelacionObligatorioException} antes de escribir.</p>
 *
 * <p>No declara validaciones propias: la obligatoriedad del motivo pertenece al dominio
 * (constitución, principio 6 — sin scope creep en la capa de presentación).</p>
 *
 * @param motivo motivo obligatorio de la cancelación, o {@code null} cuando el cuerpo
 *               está ausente o el campo llega nulo
 */
public record CancelacionRequestDto(String motivo) {
}