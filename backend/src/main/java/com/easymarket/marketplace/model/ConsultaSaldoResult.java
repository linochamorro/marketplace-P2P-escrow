package com.easymarket.marketplace.model;

import java.util.List;

/**
 * Resultado de la consulta del saldo interno disponible de un vendedor.
 *
 * <p>El saldo es el valor cacheado en centavos de {@link Usuario}; los movimientos son el detalle
 * append-only asociado al mismo vendedor. El contrato no recalcula ni corrige el saldo desde el
 * ledger y no establece orden para el detalle.</p>
 *
 * @param saldoDisponible saldo cacheado actual del vendedor en centavos
 * @param movimientos movimientos append-only asociados al vendedor consultado
 */
public record ConsultaSaldoResult(long saldoDisponible, List<MovimientoSaldo> movimientos) {
}
