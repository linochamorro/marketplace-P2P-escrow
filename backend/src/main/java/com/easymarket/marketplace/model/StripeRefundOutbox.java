package com.easymarket.marketplace.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.ZonedDateTime;

/**
 * Orden durable y mutable que un procesador posterior enviará a Stripe para reembolsar una cancelación.
 */
@Entity
@Table(name = "stripe_refund_outbox")
public class StripeRefundOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "transaccion_id", nullable = false)
    private Transaccion transaccion;

    @Column(name = "payment_intent_id", nullable = false, length = 255)
    private String paymentIntentId;

    @Column(name = "idempotency_key", nullable = false, length = 255)
    private String idempotencyKey;

    @Column(name = "estado", nullable = false, length = 20)
    private String estado;

    @Column(name = "intentos", nullable = false)
    private int intentos;

    @Column(name = "ultimo_error")
    private String ultimoError;

    @Column(name = "created_at", nullable = false)
    private ZonedDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private ZonedDateTime updatedAt;

    /** Constructor por defecto requerido por JPA. */
    public StripeRefundOutbox() {
    }

    /**
     * Construye una orden pendiente de reembolso asociada a una cancelación ya validada.
     *
     * @param transaccion transacción cancelada que originó la orden
     * @param paymentIntentId identificador Stripe que se reembolsará posteriormente
     * @param idempotencyKey clave determinista usada por Stripe durante reintentos
     * @param createdAt instante de creación de la orden
     */
    public StripeRefundOutbox(Transaccion transaccion, String paymentIntentId, String idempotencyKey,
                              ZonedDateTime createdAt) {
        this.transaccion = transaccion;
        this.paymentIntentId = paymentIntentId;
        this.idempotencyKey = idempotencyKey;
        this.estado = "PENDIENTE";
        this.intentos = 0;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    /**
     * Obtiene el ID persistente de la orden.
     *
     * @return ID persistente de la orden, o {@code null} antes de insertarla
     */
    public Long getId() { return id; }

    /**
     * Obtiene la transacción cancelada que originó la orden.
     *
     * @return transacción cancelada que originó la orden
     */
    public Transaccion getTransaccion() { return transaccion; }

    /**
     * Obtiene el PaymentIntent que el procesador deberá reembolsar.
     *
     * @return ID del PaymentIntent que el procesador deberá reembolsar
     */
    public String getPaymentIntentId() { return paymentIntentId; }

    /**
     * Obtiene la clave idempotente persistida para la solicitud a Stripe.
     *
     * @return clave idempotente determinista para la solicitud a Stripe
     */
    public String getIdempotencyKey() { return idempotencyKey; }

    /**
     * Obtiene el estado operativo de la orden.
     *
     * @return estado operativo de la orden
     */
    public String getEstado() { return estado; }

    /**
     * Obtiene la cantidad de intentos de ejecución registrados.
     *
     * @return cantidad de intentos de ejecución registrados
     */
    public int getIntentos() { return intentos; }

    /**
     * Obtiene el último error devuelto por Stripe, si hubo uno.
     *
     * @return mensaje del último error, o {@code null} si la última solicitud fue aceptada
     */
    public String getUltimoError() { return ultimoError; }

    /**
     * Obtiene el instante de inserción de la orden durable.
     *
     * @return instante en que se insertó la orden durable
     */
    public ZonedDateTime getCreatedAt() { return createdAt; }

    /**
     * Obtiene el instante de la última actualización operativa de la orden.
     *
     * @return instante de la última actualización operativa de la orden
     */
    public ZonedDateTime getUpdatedAt() { return updatedAt; }

    /**
     * Registra la aceptación de la solicitud de refund por Stripe.
     *
     * @param ahora instante en que Stripe aceptó la solicitud
     */
    public void registrarSolicitudAceptada(ZonedDateTime ahora) {
        this.estado = "SOLICITADO";
        this.intentos++;
        this.ultimoError = null;
        this.updatedAt = ahora;
    }

    /**
     * Registra un fallo de Stripe sin cambiar la orden de su estado reintentable.
     *
     * @param mensajeError mensaje proporcionado por Stripe
     * @param ahora instante en que ocurrió el fallo
     */
    public void registrarFallo(String mensajeError, ZonedDateTime ahora) {
        this.intentos++;
        this.ultimoError = mensajeError;
        this.updatedAt = ahora;
    }
}
