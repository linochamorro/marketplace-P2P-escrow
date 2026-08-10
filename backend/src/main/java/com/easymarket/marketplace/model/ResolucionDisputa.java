package com.easymarket.marketplace.model;

/**
 * Decisión binaria que un administrador puede tomar para resolver una disputa de Story 9.
 *
 * <p>La decisión no admite reembolso parcial: {@link #A_FAVOR_VENDEDOR} completa la transacción y
 * libera su monto al ledger interno; {@link #A_FAVOR_COMPRADOR} la cancela y ordena su reembolso
 * mediante la outbox durable.</p>
 */
public enum ResolucionDisputa {

    /** Completa la transacción y acredita el precio snapshot al vendedor. */
    A_FAVOR_VENDEDOR,

    /** Cancela la transacción, restaura una unidad y ordena el refund al comprador. */
    A_FAVOR_COMPRADOR
}
