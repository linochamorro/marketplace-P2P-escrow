package com.easymarket.marketplace.dto;

/**
 * DTO de respuesta HTTP para {@code GET /admin/usuarios/bloqueados} (PHA06TSK07; Story 0c de
 * spec.md; plan.md, "Lecturas administrativas").
 *
 * <p>Expone exclusivamente los datos mínimos para desbloquear una cuenta: {@code usuarioId} es el
 * identificador que consume {@code POST /admin/usuarios/{id}/desbloquear} y {@code email} el dato
 * que la UI administrativa muestra para identificar la cuenta. No incluye IPs, conteos ni marcas
 * de bloqueo porque el contrato de plan.md limita la respuesta a "los datos mínimos para
 * desbloqueo".</p>
 *
 * @param usuarioId identificador persistente de la cuenta real con bloqueo permanente
 * @param email correo electrónico de la cuenta real con bloqueo permanente
 */
public record UsuarioBloqueadoResponseDto(Long usuarioId, String email) {
}
