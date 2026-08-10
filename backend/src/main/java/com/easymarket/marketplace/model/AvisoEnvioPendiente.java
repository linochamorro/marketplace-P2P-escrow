package com.easymarket.marketplace.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.time.ZonedDateTime;

/**
 * Persistent idempotency marker linking one reserved transaction to its single 48-hour shipment
 * warning notification.
 *
 * <p>The primary key is the transaction identifier and V13 enforces a unique notification link.
 * This operational marker prevents duplicate projection rows; it is not the canonical
 * append-only transaction-event log.</p>
 */
@Entity
@Table(name = "avisos_envio_pendiente")
public class AvisoEnvioPendiente {

    /** Identifier shared with the marked transaction. */
    @Id
    @Column(name = "transaccion_id")
    private Long transaccionId;

    /** Transaction that received its one-time warning. */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "transaccion_id", nullable = false)
    private Transaccion transaccion;

    /** Notification created for the warning. */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notificacion_id", nullable = false, unique = true)
    private Notificacion notificacion;

    /** Instant at which the idempotency marker was created. */
    @Column(name = "created_at", nullable = false)
    private ZonedDateTime createdAt;

    /**
     * Default constructor required by JPA.
     */
    public AvisoEnvioPendiente() {
    }

    /**
     * Creates the marker associated with the notification emitted for one transaction.
     *
     * @param transaccion reserved transaction receiving the warning
     * @param notificacion persisted in-app notification linked to the transaction
     * @param createdAt instant at which the marker is created
     */
    public AvisoEnvioPendiente(Transaccion transaccion, Notificacion notificacion, ZonedDateTime createdAt) {
        this.transaccion = transaccion;
        this.notificacion = notificacion;
        this.createdAt = createdAt;
    }
}
