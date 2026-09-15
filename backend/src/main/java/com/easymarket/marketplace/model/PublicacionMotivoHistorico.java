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
 * Registro canónico append-only del motivo de una moderación que mueve una publicación a
 * {@code cambios_solicitados} o {@code rechazada} (PHA06TSK05; Story 3, spec.md; plan.md,
 * "Histórico de motivos de moderación").
 *
 * <p>Mapea {@code publicacion_motivos_historicos} tras V17. La inserción ocurre en la misma
 * transacción de base de datos que el cambio de estado de la moderación (constitución, principio
 * 1); PostgreSQL impide actualizar o eliminar sus filas mediante trigger (principio 2). La
 * publicación puede ser {@code null} si fue eliminada con la acción referencial
 * {@code ON DELETE SET NULL}, en cuyo caso el motivo histórico sobrevive sin la referencia.</p>
 */
@Entity
@Table(name = "publicacion_motivos_historicos")
public class PublicacionMotivoHistorico {

    /** Identificador persistente del registro histórico. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Publicación moderada, o {@code null} cuando fue eliminada (ON DELETE SET NULL). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "publicacion_id")
    private Publicacion publicacion;

    /** Acción de moderación ejecutada: {@code CAMBIOS_SOLICITADOS} o {@code RECHAZADA}. */
    @Column(name = "accion", nullable = false, length = 50)
    private String accion;

    /** Motivo literal informado por el admin al moderar, obligatorio por dominio y esquema. */
    @Column(name = "motivo", nullable = false, columnDefinition = "TEXT")
    private String motivo;

    /** Usuario admin moderador responsable de la transición. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_id", nullable = false)
    private Usuario actor;

    /** Instante de inserción del registro de auditoría. */
    @Column(name = "created_at", nullable = false)
    private ZonedDateTime createdAt;

    /**
     * Constructor por defecto requerido por JPA.
     */
    public PublicacionMotivoHistorico() {
    }

    /**
     * Construye el registro histórico de una moderación que exige motivo.
     *
     * @param publicacion publicación moderada, o {@code null} si corresponde a una ya eliminada
     * @param actor admin moderador responsable de la transición
     * @param accion acción de moderación ejecutada ({@code CAMBIOS_SOLICITADOS} o {@code RECHAZADA})
     * @param motivo motivo literal no blanco informado por el admin
     * @param createdAt instante actual de inserción del registro
     */
    public PublicacionMotivoHistorico(Publicacion publicacion, Usuario actor, String accion, String motivo, ZonedDateTime createdAt) {
        this.publicacion = publicacion;
        this.actor = actor;
        this.accion = accion;
        this.motivo = motivo;
        this.createdAt = createdAt;
    }

    /**
     * Obtiene el identificador persistente del registro histórico.
     *
     * @return ID del registro, o {@code null} antes de persistirlo
     */
    public Long getId() {
        return id;
    }

    /**
     * Obtiene la publicación moderada.
     *
     * @return publicación cuyo motivo quedó auditado, o {@code null} si fue eliminada
     */
    public Publicacion getPublicacion() {
        return publicacion;
    }

    /**
     * Obtiene la acción de moderación ejecutada.
     *
     * @return literal {@code CAMBIOS_SOLICITADOS} o {@code RECHAZADA}
     */
    public String getAccion() {
        return accion;
    }

    /**
     * Obtiene el motivo literal informado por el admin.
     *
     * @return motivo de la moderación
     */
    public String getMotivo() {
        return motivo;
    }

    /**
     * Obtiene el admin moderador responsable.
     *
     * @return usuario actor del registro histórico
     */
    public Usuario getActor() {
        return actor;
    }

    /**
     * Obtiene el instante de inserción del registro.
     *
     * @return fecha y hora de creación
     */
    public ZonedDateTime getCreatedAt() {
        return createdAt;
    }
}
