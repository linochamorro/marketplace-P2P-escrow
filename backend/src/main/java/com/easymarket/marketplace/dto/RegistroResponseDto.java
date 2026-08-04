package com.easymarket.marketplace.dto;

import com.easymarket.marketplace.model.Rol;
import com.easymarket.marketplace.model.Usuario;

import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * Objeto de transferencia de datos (DTO) para la respuesta exitosa de registro ({@code POST /auth/registro}).
 *
 * <p>Expone únicamente los datos seguros del usuario registrado, excluyendo deliberadamente
 * la contraseña o su hash por motivos de seguridad.</p>
 */
public class RegistroResponseDto {

    private Long id;
    private String email;
    private Rol rol;
    private long saldoDisponible;
    private ZonedDateTime createdAt;

    /**
     * Constructor por defecto requerido para serialización JSON.
     */
    public RegistroResponseDto() {
    }

    /**
     * Constructor con todos los campos del DTO de respuesta.
     *
     * @param id identificador único del usuario
     * @param email correo electrónico registrado
     * @param rol rol asignado en la plataforma
     * @param saldoDisponible saldo inicial en centavos
     * @param createdAt fecha y hora de creación de la cuenta
     */
    public RegistroResponseDto(Long id, String email, Rol rol, long saldoDisponible, ZonedDateTime createdAt) {
        this.id = id;
        this.email = email;
        this.rol = rol;
        this.saldoDisponible = saldoDisponible;
        this.createdAt = createdAt;
    }

    /**
     * Método de fábrica para mapear una entidad {@link Usuario} a un {@link RegistroResponseDto}.
     *
     * @param usuario entidad de dominio
     * @return instancia de DTO seguro para respuestas HTTP
     */
    public static RegistroResponseDto fromEntity(Usuario usuario) {
        return new RegistroResponseDto(
                usuario.getId(),
                usuario.getEmail(),
                usuario.getRol(),
                usuario.getSaldoDisponible(),
                usuario.getCreatedAt()
        );
    }

    /**
     * Obtiene el identificador único del usuario.
     *
     * @return ID del usuario
     */
    public Long getId() {
        return id;
    }

    /**
     * Establece el ID del usuario.
     *
     * @param id ID del usuario
     */
    public void setId(Long id) {
        this.id = id;
    }

    /**
     * Obtiene el correo electrónico.
     *
     * @return email del usuario
     */
    public String getEmail() {
        return email;
    }

    /**
     * Establece el correo electrónico.
     *
     * @param email email del usuario
     */
    public void setEmail(String email) {
        this.email = email;
    }

    /**
     * Obtiene el rol asignado.
     *
     * @return rol del usuario
     */
    public Rol getRol() {
        return rol;
    }

    /**
     * Establece el rol asignado.
     *
     * @param rol rol del usuario
     */
    public void setRol(Rol rol) {
        this.rol = rol;
    }

    /**
     * Obtiene el saldo disponible en centavos.
     *
     * @return saldo disponible
     */
    public long getSaldoDisponible() {
        return saldoDisponible;
    }

    /**
     * Establece el saldo disponible en centavos.
     *
     * @param saldoDisponible saldo disponible
     */
    public void setSaldoDisponible(long saldoDisponible) {
        this.saldoDisponible = saldoDisponible;
    }

    /**
     * Obtiene la fecha y hora de creación.
     *
     * @return fecha y hora de creación
     */
    public ZonedDateTime getCreatedAt() {
        return createdAt;
    }

    /**
     * Establece la fecha y hora de creación.
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
        RegistroResponseDto that = (RegistroResponseDto) o;
        return saldoDisponible == that.saldoDisponible &&
                Objects.equals(id, that.id) &&
                Objects.equals(email, that.email) &&
                rol == that.rol &&
                Objects.equals(createdAt, that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, email, rol, saldoDisponible, createdAt);
    }
}
