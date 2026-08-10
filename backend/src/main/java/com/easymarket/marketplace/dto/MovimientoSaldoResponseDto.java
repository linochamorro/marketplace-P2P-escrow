package com.easymarket.marketplace.dto;

import com.easymarket.marketplace.model.MovimientoSaldo;

import java.time.ZonedDateTime;

/**
 * DTO de respuesta HTTP para un movimiento del detalle de saldo (Story 12, spec.md).
 *
 * <p>Mapea un {@link MovimientoSaldo} del ledger append-only. El monto se expresa en centavos
 * como entero (constitution, principio 3). {@code transaccionId} es siempre no nulo: la columna
 * {@code movimientos_saldo.transaccion_id} es {@code NOT NULL} (V9) y cada movimiento proviene de
 * una transacción de origen. No expone vendedor ni transacción completos.</p>
 *
 * @param id identificador persistente del movimiento
 * @param monto monto entero en centavos del movimiento
 * @param createdAt instante en el que se creó el registro append-only
 * @param transaccionId ID de la transacción que origina el movimiento
 */
public record MovimientoSaldoResponseDto(
        Long id,
        long monto,
        ZonedDateTime createdAt,
        Long transaccionId
) {
    /**
     * Mapea una entidad JPA {@link MovimientoSaldo} a su representación DTO de respuesta.
     *
     * @param movimiento entidad JPA del ledger append-only
     * @return DTO {@link MovimientoSaldoResponseDto} resultante
     */
    public static MovimientoSaldoResponseDto fromEntity(MovimientoSaldo movimiento) {
        return new MovimientoSaldoResponseDto(
                movimiento.getId(),
                movimiento.getMonto(),
                movimiento.getCreatedAt(),
                movimiento.getTransaccion().getId()
        );
    }
}