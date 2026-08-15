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
 * Registro canónico append-only de la creación de una publicación.
 *
 * <p>Mapea {@code publicacion_eventos} tras V16. El esquema solo admite el tipo literal
 * {@code CREADA}; PostgreSQL impide actualizar o eliminar sus filas mediante trigger. La publicación
 * puede ser {@code null} si fue eliminada con la acción referencial {@code ON DELETE SET NULL}.</p>
 */
@Entity
@Table(name = "publicacion_eventos")
public class PublicacionEvento {

    /** Identificador persistente del evento. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Publicación cuya creación queda auditada, o {@code null} cuando fue eliminada. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "publicacion_id")
    private Publicacion publicacion;

    /** Usuario vendedor que creó la publicación. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_id", nullable = false)
    private Usuario actor;

    /** Tipo canónico del evento, limitado a CREADA por V15. */
    @Column(name = "tipo", nullable = false, length = 50)
    private String tipo;

    /** Detalle opcional del evento, sin semántica adicional en esta tarea. */
    @Column(name = "detalle", columnDefinition = "TEXT")
    private String detalle;

    /** Instante de creación del registro de auditoría. */
    @Column(name = "created_at", nullable = false)
    private ZonedDateTime createdAt;

    /**
     * Constructor por defecto requerido por JPA.
     */
    public PublicacionEvento() {
    }

    /**
     * Construye el único tipo de evento admitido para la creación de una publicación.
     *
     * @param publicacion publicación persistida cuya creación se audita, o {@code null} si fue eliminada
     * @param actor vendedor responsable de crear la publicación
     * @param createdAt instante actual de inserción del registro
     */
    public PublicacionEvento(Publicacion publicacion, Usuario actor, ZonedDateTime createdAt) {
        this.publicacion = publicacion;
        this.actor = actor;
        this.tipo = "CREADA";
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
     * Obtiene la publicación auditada.
     *
     * @return publicación cuya creación se registró, o {@code null} si fue eliminada
     */
    public Publicacion getPublicacion() {
        return publicacion;
    }

    /**
     * Obtiene el vendedor responsable de la creación.
     *
     * @return usuario actor del evento
     */
    public Usuario getActor() {
        return actor;
    }

    /**
     * Obtiene el tipo canónico del evento.
     *
     * @return literal {@code CREADA}
     */
    public String getTipo() {
        return tipo;
    }

    /**
     * Obtiene el detalle opcional del evento.
     *
     * @return detalle, o {@code null} cuando no se informó
     */
    public String getDetalle() {
        return detalle;
    }

    /**
     * Obtiene el instante de creación del registro.
     *
     * @return fecha y hora de creación
     */
    public ZonedDateTime getCreatedAt() {
        return createdAt;
    }
}
