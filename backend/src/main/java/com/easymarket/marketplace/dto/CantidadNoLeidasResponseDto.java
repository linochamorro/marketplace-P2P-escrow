package com.easymarket.marketplace.dto;

/**
 * DTO de respuesta HTTP para el contador de notificaciones no leídas del usuario autenticado
 * (PHA15TSK05, plan.md §Notificaciones fila "Contador de pendientes").
 *
 * <p>Es servido por {@code GET /notificaciones/no-leidas/count} con la forma exacta
 * {@code {"cantidad": N}} exigida por el contrato. La cantidad cuenta exclusivamente las
 * notificaciones accionables del rol del JWT con {@code leida=false}; no expone el
 * destinatario porque la identidad se resuelve en backend (constitution, principio 7) y no
 * acepta parámetros del cliente.</p>
 *
 * @param cantidad número de notificaciones accionables no leídas del usuario autenticado;
 *                 {@code 0} cuando no tiene ninguna (respuesta válida, no error)
 */
public record CantidadNoLeidasResponseDto(long cantidad) {
}
