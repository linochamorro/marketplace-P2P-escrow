package com.easymarket.marketplace.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * Entidad JPA que representa una acción de auditoría administrativa registrada en el sistema.
 *
 * <p>Es un registro inmutable append-only que almacena la acción ejecutada por un administrador,
 * el usuario afectado (si aplica) y detalles contextuales de la operación (Story 0c, plan.md).</p>
 */
@Entity
@Table(name = "admin_acciones")
public class AdminAccion {

    private static final ZoneId ZONA_PERU = ZoneId.of("America/Lima");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "admin_id", nullable = false)
    private Long adminId;

    @Column(name = "accion", nullable = false, length = 50)
    private String accion;

    @Column(name = "usuario_afectado_id")
    private Long usuarioAfectadoId;

    @Column(name = "detalle")
    private String detalle;

    @Column(name = "created_at", nullable = false)
    private ZonedDateTime createdAt;

    /**
     * Constructor sin argumentos requerido por JPA.
     */
    public AdminAccion() {
    }

    /**
     * Constructor principal para la creación de un nuevo registro de auditoría administrativa.
     *
     * @param adminId ID del administrador que realiza la acción
     * @param accion identificador o nombre de la acción realizada (p. ej., "DESBLOQUEO_CUENTA")
     * @param usuarioAfectadoId ID del usuario afectado por la acción (opcional/nullable)
     * @param detalle descripción contextual detallada de la acción realizada
     */
    public AdminAccion(Long adminId, String accion, Long usuarioAfectadoId, String detalle) {
        this.adminId = adminId;
        this.accion = accion;
        this.usuarioAfectadoId = usuarioAfectadoId;
        this.detalle = detalle;
        this.createdAt = ZonedDateTime.now(ZONA_PERU);
    }

    /**
     * Constructor secundario que permite especificar una fecha y hora explícita.
     *
     * @param adminId ID del administrador que realiza la acción
     * @param accion identificador o nombre de la acción realizada
     * @param usuarioAfectadoId ID del usuario afectado por la acción
     * @param detalle descripción contextual detallada
     * @param createdAt fecha y hora de creación de la marca de auditoría
     */
    public AdminAccion(Long adminId, String accion, Long usuarioAfectadoId, String detalle, ZonedDateTime createdAt) {
        this.adminId = adminId;
        this.accion = accion;
        this.usuarioAfectadoId = usuarioAfectadoId;
        this.detalle = detalle;
        this.createdAt = createdAt;
    }

    /**
     * Obtiene el identificador único del registro de auditoría.
     *
     * @return ID único del registro
     */
    public Long getId() {
        return id;
    }

    /**
     * Establece el identificador único del registro de auditoría.
     *
     * @param id ID único
     */
    public void setId(Long id) {
        this.id = id;
    }

    /**
     * Obtiene el ID del administrador que ejecutó la acción.
     *
     * @return ID del admin
     */
    public Long getAdminId() {
        return adminId;
    }

    /**
     * Establece el ID del administrador que ejecutó la acción.
     *
     * @param adminId ID del admin
     */
    public void setAdminId(Long adminId) {
        this.adminId = adminId;
    }

    /**
     * Obtiene el código/nombre de la acción ejecutada.
     *
     * @return nombre de la acción
     */
    public String getAccion() {
        return accion;
    }

    /**
     * Establece el código/nombre de la acción ejecutada.
     *
     * @param accion nombre de la acción
     */
    public void setAccion(String accion) {
        this.accion = accion;
    }

    /**
     * Obtiene el ID del usuario afectado por la acción administrativa.
     *
     * @return ID del usuario afectado o {@code null} si no aplica
     */
    public Long getUsuarioAfectadoId() {
        return usuarioAfectadoId;
    }

    /**
     * Establece el ID del usuario afectado por la acción administrativa.
     *
     * @param usuarioAfectadoId ID del usuario afectado
     */
    public void setUsuarioAfectadoId(Long usuarioAfectadoId) {
        this.usuarioAfectadoId = usuarioAfectadoId;
    }

    /**
     * Obtiene el detalle contextual de la acción.
     *
     * @return detalle explicativo
     */
    public String getDetalle() {
        return detalle;
    }

    /**
     * Establece el detalle contextual de la acción.
     *
     * @param detalle detalle explicativo
     */
    public void setDetalle(String detalle) {
        this.detalle = detalle;
    }

    /**
     * Obtiene la fecha y hora de creación de la marca de auditoría.
     *
     * @return fecha y hora de creación
     */
    public ZonedDateTime getCreatedAt() {
        return createdAt;
    }

    /**
     * Establece la fecha y hora de creación de la marca de auditoría.
     *
     * @param createdAt fecha y hora de creación
     */
    public void setCreatedAt(ZonedDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AdminAccion that = (AdminAccion) o;
        return Objects.equals(id, that.id)
                && Objects.equals(adminId, that.adminId)
                && Objects.equals(accion, that.accion)
                && Objects.equals(usuarioAfectadoId, that.usuarioAfectadoId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, adminId, accion, usuarioAfectadoId);
    }
}
