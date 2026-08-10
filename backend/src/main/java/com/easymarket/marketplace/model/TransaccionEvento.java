package com.easymarket.marketplace.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
 * Registro de auditoría append-only de una transición de estado de transacción.
 *
 * <p>Mapea {@code transaccion_eventos}, creada por V10. PostgreSQL rechaza toda actualización o
 * eliminación mediante su trigger, por lo que la entidad únicamente se crea y persiste desde el
 * servicio de dominio que realiza la transición, dentro de la misma transacción de base de datos.
 * El actor es nulo solo para transiciones ejecutadas por jobs del sistema.</p>
 */
@Entity
@Table(name = "transaccion_eventos")
public class TransaccionEvento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaccion_id", nullable = false)
    private Transaccion transaccion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id")
    private Usuario actor;

    @Convert(converter = EstadoTransaccionConverter.class)
    @Column(name = "estado_origen", nullable = false, length = 50)
    private EstadoTransaccion estadoOrigen;

    @Convert(converter = EstadoTransaccionConverter.class)
    @Column(name = "estado_destino", nullable = false, length = 50)
    private EstadoTransaccion estadoDestino;

    @Column(name = "motivo", columnDefinition = "TEXT")
    private String motivo;

    @Column(name = "created_at", nullable = false)
    private ZonedDateTime createdAt;

    /**
     * Constructor por defecto requerido por JPA.
     */
    public TransaccionEvento() {
    }

    /**
     * Construye un evento inmutable para una transición de transacción ya validada.
     *
     * @param transaccion transacción cuya historia se registra
     * @param actor usuario responsable, o {@code null} cuando el sistema ejecuta la transición
     * @param estadoOrigen estado previo a la transición
     * @param estadoDestino estado resultante de la transición
     * @param motivo motivo de negocio, o {@code null} si la transición no lo requiere
     * @param createdAt instante de creación del evento
     */
    public TransaccionEvento(Transaccion transaccion, Usuario actor, EstadoTransaccion estadoOrigen,
                              EstadoTransaccion estadoDestino, String motivo, ZonedDateTime createdAt) {
        this.transaccion = transaccion;
        this.actor = actor;
        this.estadoOrigen = estadoOrigen;
        this.estadoDestino = estadoDestino;
        this.motivo = motivo;
        this.createdAt = createdAt;
    }

    /**
     * Obtiene el identificador persistente del evento.
     *
     * @return ID del evento, o {@code null} antes de persistirlo
     */
    public Long getId() {
        return id;
    }

    /**
     * Obtiene la transacción cuya transición fue registrada.
     *
     * @return transacción auditada
     */
    public Transaccion getTransaccion() {
        return transaccion;
    }

    /**
     * Obtiene el actor responsable de la transición.
     *
     * @return usuario actor, o {@code null} para una transición del sistema
     */
    public Usuario getActor() {
        return actor;
    }

    /**
     * Obtiene el estado anterior a la transición.
     *
     * @return estado origen auditado
     */
    public EstadoTransaccion getEstadoOrigen() {
        return estadoOrigen;
    }

    /**
     * Obtiene el estado resultante de la transición.
     *
     * @return estado destino auditado
     */
    public EstadoTransaccion getEstadoDestino() {
        return estadoDestino;
    }

    /**
     * Obtiene el motivo de negocio asociado al evento.
     *
     * @return motivo, o {@code null} cuando no aplica
     */
    public String getMotivo() {
        return motivo;
    }

    /**
     * Obtiene el instante en que se creó el registro append-only.
     *
     * @return fecha y hora de creación del evento
     */
    public ZonedDateTime getCreatedAt() {
        return createdAt;
    }
}
