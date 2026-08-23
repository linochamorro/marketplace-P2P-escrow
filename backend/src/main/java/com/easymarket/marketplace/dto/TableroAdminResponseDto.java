package com.easymarket.marketplace.dto;

/**
 * DTO de respuesta HTTP para {@code GET /admin/tablero} (PHA06TSK07; Story 13 de spec.md;
 * plan.md, "Tablero administrativo").
 *
 * <p>Contiene exclusivamente las métricas operativas y financieras definidas por plan.md, todas
 * obtenidas por agregaciones de lectura sobre el esquema existente (nunca por recálculo ni
 * escritura en el ledger). El dinero se expresa siempre como entero en centavos
 * ({@code long}, constitution principio 3); las sumas vacías se serializan como {@code 0}
 * porque las consultas aplican {@code COALESCE(SUM(...), 0)}, por lo que ningún campo llega
 * {@code null} al JSON.</p>
 *
 * @param publicacionesPendientes conteo de publicaciones en estado {@code pendiente_revisión}
 * @param disputasAbiertas conteo de transacciones en estado {@code disputa}
 * @param cuentasBloqueadas conteo de cuentas reales con bloqueo permanente (algún registro
 *                          {@code login_attempts.intentos >= 12})
 * @param volumenEscrowCentavos suma de {@code precio_snapshot} de transacciones en
 *                              {@code reservada}/{@code enviado}/{@code entregado}/{@code disputa}
 * @param fondosLiberadosCentavos suma de movimientos positivos del ledger {@code movimientos_saldo}
 * @param transaccionesFinalizadas conteo de transacciones en
 *                                 {@code recibido}/{@code recibido_sin_respuesta}/{@code completada}
 */
public record TableroAdminResponseDto(
        long publicacionesPendientes,
        long disputasAbiertas,
        long cuentasBloqueadas,
        long volumenEscrowCentavos,
        long fondosLiberadosCentavos,
        long transaccionesFinalizadas
) {
}
