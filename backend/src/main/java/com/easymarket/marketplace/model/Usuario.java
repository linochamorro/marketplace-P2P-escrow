package com.easymarket.marketplace.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * Entidad JPA que representa a un usuario registrado en la plataforma EasyMarket.
 */
@Entity
@Table(name = "usuarios")
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "rol", nullable = false)
    private Rol rol;

    @Column(name = "saldo_disponible", nullable = false)
    private long saldoDisponible;

    @Column(name = "created_at", nullable = false)
    private ZonedDateTime createdAt;

    /**
     * Constructor por defecto requerido por JPA.
     */
    public Usuario() {
    }

    /**
     * Constructor con todos los campos obligatorios.
     *
     * @param email correo electrónico único del usuario
     * @param passwordHash hash BCrypt de la contraseña
     * @param rol rol del usuario (USUARIO o ADMIN)
     * @param saldoDisponible saldo disponible en centavos (entero de 64 bits)
     * @param createdAt fecha y hora de creación de la cuenta
     */
    public Usuario(String email, String passwordHash, Rol rol, long saldoDisponible, ZonedDateTime createdAt) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.rol = rol;
        this.saldoDisponible = saldoDisponible;
        this.createdAt = createdAt;
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
     * Establece el identificador único del usuario.
     *
     * @param id ID del usuario
     */
    public void setId(Long id) {
        this.id = id;
    }

    /**
     * Obtiene el email del usuario.
     *
     * @return email del usuario
     */
    public String getEmail() {
        return email;
    }

    /**
     * Establece el email del usuario.
     *
     * @param email correo electrónico
     */
    public void setEmail(String email) {
        this.email = email;
    }

    /**
     * Obtiene el hash BCrypt de la contraseña.
     *
     * @return hash de la contraseña
     */
    public String getPasswordHash() {
        return passwordHash;
    }

    /**
     * Establece el hash BCrypt de la contraseña.
     *
     * @param passwordHash hash de la contraseña
     */
    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    /**
     * Obtiene el rol asignado al usuario.
     *
     * @return rol del usuario
     */
    public Rol getRol() {
        return rol;
    }

    /**
     * Establece el rol asignado al usuario.
     *
     * @param rol rol del usuario
     */
    public void setRol(Rol rol) {
        this.rol = rol;
    }

    /**
     * Obtiene el saldo disponible en centavos (entero de 64 bits).
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
     * Obtiene la fecha y hora de creación de la cuenta.
     *
     * @return fecha y hora de creación
     */
    public ZonedDateTime getCreatedAt() {
        return createdAt;
    }

    /**
     * Establece la fecha y hora de creación de la cuenta.
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
        Usuario usuario = (Usuario) o;
        return Objects.equals(id, usuario.id) && Objects.equals(email, usuario.email);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, email);
    }
}
