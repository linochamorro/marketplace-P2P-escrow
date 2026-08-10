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
 * In-app notification projection addressed to one user.
 *
 * <p>This entity maps the {@code notificaciones} convenience projection from V9. V14 optionally
 * associates a notification with its originating transaction for daily open-transaction notices.
 * It is not the canonical append-only transaction-event log and does not represent a transaction
 * state change.</p>
 */
@Entity
@Table(name = "notificaciones")
public class Notificacion {

    /** Persistent notification identifier. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** User receiving the in-app notification. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    /** Optional transaction whose daily notice originated this projection, introduced by V14. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaccion_id")
    private Transaccion transaccion;

    /** Literal notification content displayed to the recipient. */
    @Column(name = "mensaje", nullable = false, columnDefinition = "TEXT")
    private String mensaje;

    /** Stable notification category used by the future notification center. */
    @Column(name = "tipo", nullable = false, length = 100)
    private String tipo;

    /** Whether the recipient has read the notification. */
    @Column(name = "leida", nullable = false)
    private boolean leida;

    /** Instant at which the notification projection was created. */
    @Column(name = "created_at", nullable = false)
    private ZonedDateTime createdAt;

    /**
     * Default constructor required by JPA.
     */
    public Notificacion() {
    }

    /**
     * Creates an unread in-app notification for one recipient.
     *
     * @param usuario recipient user
     * @param mensaje literal message presented to the recipient
     * @param tipo stable notification category
     * @param createdAt instant at which the projection is created
     */
    public Notificacion(Usuario usuario, String mensaje, String tipo, ZonedDateTime createdAt) {
        this(usuario, null, mensaje, tipo, createdAt);
    }

    /**
     * Creates an unread in-app notification optionally associated with a transaction.
     *
     * @param usuario recipient user
     * @param transaccion originating transaction, or {@code null} for a non-transaction notice
     * @param mensaje literal message presented to the recipient
     * @param tipo stable notification category
     * @param createdAt instant at which the projection is created
     */
    public Notificacion(Usuario usuario, Transaccion transaccion, String mensaje, String tipo,
                        ZonedDateTime createdAt) {
        this.usuario = usuario;
        this.transaccion = transaccion;
        this.mensaje = mensaje;
        this.tipo = tipo;
        this.leida = false;
        this.createdAt = createdAt;
    }

    /**
     * Gets the persistent notification identifier.
     *
     * @return notification identifier, or {@code null} before persistence
     */
    public Long getId() {
        return id;
    }

    /**
     * Gets the transaction optionally associated with this notification.
     *
     * @return originating transaction, or {@code null} when this is not a transaction notice
     */
    public Transaccion getTransaccion() {
        return transaccion;
    }

    /**
     * Gets the recipient of this notification.
     *
     * @return recipient user
     */
    public Usuario getUsuario() {
        return usuario;
    }

    /**
     * Gets the literal notification content displayed to the recipient.
     *
     * @return notification message
     */
    public String getMensaje() {
        return mensaje;
    }

    /**
     * Gets whether the recipient has read the notification.
     *
     * @return {@code true} when the recipient has read the notification, {@code false} otherwise
     */
    public boolean isLeida() {
        return leida;
    }

    /**
     * Gets the stable notification category.
     *
     * @return notification type
     */
    public String getTipo() {
        return tipo;
    }

    /**
     * Gets the instant at which this projection was created.
     *
     * @return notification creation instant
     */
    public ZonedDateTime getCreatedAt() {
        return createdAt;
    }
}
