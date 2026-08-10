package com.easymarket.marketplace.dto;

import com.easymarket.marketplace.model.ConsultaSaldoResult;
import com.easymarket.marketplace.model.MovimientoSaldo;

import java.util.List;

/**
 * DTO de respuesta HTTP para la consulta de saldo del usuario autenticado (Story 12, spec.md).
 *
 * <p>Mapea un {@link ConsultaSaldoResult} de dominio tal como lo consume el panel de saldo del
 * vendedor. El {@code saldoDisponible} es el valor cacheado en centavos leído por el servicio sin
 * recalcular ni reconciliar desde el ledger; {@code movimientos} es el detalle append-only sin
 * orden contractual. No expone vendedorId/email/rol: la identidad es implícita del principal
 * autenticado (patrón PHA04TSK16).</p>
 *
 * @param saldoDisponible saldo cacheado del usuario autenticado en centavos
 * @param movimientos detalle de movimientos append-only del usuario autenticado
 */
public record SaldoResponseDto(
        long saldoDisponible,
        List<MovimientoSaldoResponseDto> movimientos
) {
    /**
     * Mapea un resultado de dominio {@link ConsultaSaldoResult} a su representación DTO de
     * respuesta.
     *
     * @param resultado resultado de dominio con saldo cacheado y movimientos del usuario
     * @return DTO {@link SaldoResponseDto} resultante
     */
    public static SaldoResponseDto fromEntity(ConsultaSaldoResult resultado) {
        List<MovimientoSaldoResponseDto> movimientos = resultado.movimientos()
                .stream()
                .map(MovimientoSaldoResponseDto::fromEntity)
                .toList();
        return new SaldoResponseDto(resultado.saldoDisponible(), movimientos);
    }
}