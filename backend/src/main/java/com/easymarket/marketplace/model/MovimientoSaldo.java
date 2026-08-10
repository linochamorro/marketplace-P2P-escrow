package com.easymarket.marketplace.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.ZonedDateTime;

/**
 * Registro append-only de un movimiento en el saldo disponible de un vendedor.
 *
 * <p>Mapea {@code movimientos_saldo}, creada por V9. El monto se expresa en centavos como entero
 * de 64 bits. Esta tarea registra créditos positivos al confirmar la recepción; futuras tareas
 * pueden definir otros movimientos sin modificar registros ya persistidos.</p>
 */
@Entity
@Table(name = "movimientos_saldo")
public class MovimientoSaldo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaccion_id", nullable = false)
    private Transaccion transaccion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vendedor_id", nullable = false)
    private Usuario vendedor;

    @Column(name = "monto", nullable = false)
    private long monto;

    @Column(name = "created_at", nullable = false)
    private ZonedDateTime createdAt;

    /**
     * Constructor por defecto requerido por JPA.
     */
    public MovimientoSaldo() {
    }

    /**
     * Construye un movimiento de saldo asociado a su transacción y vendedor.
     *
     * @param transaccion transacción que origina el movimiento
     * @param vendedor vendedor cuyo saldo se modifica
     * @param monto monto en centavos; positivo para un crédito
     * @param createdAt instante de creación del registro append-only
     */
    public MovimientoSaldo(Transaccion transaccion, Usuario vendedor, long monto, ZonedDateTime createdAt) {
        this.transaccion = transaccion;
        this.vendedor = vendedor;
        this.monto = monto;
        this.createdAt = createdAt;
    }

    /**
     * Obtiene el identificador persistente del movimiento.
     *
     * @return ID del movimiento, o {@code null} antes de persistirlo
     */
    public Long getId() {
        return id;
    }

    /**
     * Obtiene la transacción que originó el movimiento.
     *
     * @return transacción asociada
     */
    public Transaccion getTransaccion() {
        return transaccion;
    }

    /**
     * Obtiene el vendedor acreditado o afectado por el movimiento.
     *
     * @return vendedor asociado
     */
    public Usuario getVendedor() {
        return vendedor;
    }

    /**
     * Obtiene el monto entero en centavos del movimiento.
     *
     * @return monto del movimiento
     */
    public long getMonto() {
        return monto;
    }

    /**
     * Obtiene el instante de creación del registro append-only.
     *
     * @return fecha y hora de creación
     */
    public ZonedDateTime getCreatedAt() {
        return createdAt;
    }
}
