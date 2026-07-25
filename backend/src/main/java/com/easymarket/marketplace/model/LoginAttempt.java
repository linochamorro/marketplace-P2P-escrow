package com.easymarket.marketplace.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import jakarta.persistence.UniqueConstraint;

import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * Entidad JPA que representa los intentos de inicio de sesión por combinación email + IP.
 *
 * <p>Mapea la tabla {@code login_attempts} para el seguimiento de fallos y bloqueos
 * temporales o permanentes según las reglas de rate limiting de la Story 0b de {@code spec.md}.</p>
 */
@Entity
@Table(
    name = "login_attempts",
    uniqueConstraints = @UniqueConstraint(name = "uk_login_attempts_email_ip", columnNames = {"email", "ip"})
)
public class LoginAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "ip", nullable = false)
    private String ip;

    @Column(name = "intentos", nullable = false)
    private int intentos;

    @Column(name = "bloqueado_hasta")
    private ZonedDateTime bloqueadoHasta;

    /**
     * Constructor por defecto requerido por JPA.
     */
    public LoginAttempt() {
    }

    /**
     * Constructor con todos los campos obligatorios.
     *
     * @param email correo electrónico intentado
     * @param ip dirección IP de origen
     * @param intentos número de intentos fallidos acumulados
     * @param bloqueadoHasta marca de tiempo hasta la cual la combinación está bloqueada (opcional)
     */
    public LoginAttempt(String email, String ip, int intentos, ZonedDateTime bloqueadoHasta) {
        this.email = email;
        this.ip = ip;
        this.intentos = intentos;
        this.bloqueadoHasta = bloqueadoHasta;
    }

    /**
     * Obtiene el identificador único del registro.
     *
     * @return ID del registro
     */
    public Long getId() {
        return id;
    }

    /**
     * Establece el identificador único del registro.
     *
     * @param id ID del registro
     */
    public void setId(Long id) {
        this.id = id;
    }

    /**
     * Obtiene el email asociado al intento de login.
     *
     * @return email registrado
     */
    public String getEmail() {
        return email;
    }

    /**
     * Establece el email asociado al intento.
     *
     * @param email correo electrónico
     */
    public void setEmail(String email) {
        this.email = email;
    }

    /**
     * Obtiene la dirección IP de origen.
     *
     * @return dirección IP
     */
    public String getIp() {
        return ip;
    }

    /**
     * Establece la dirección IP de origen.
     *
     * @param ip dirección IP
     */
    public void setIp(String ip) {
        this.ip = ip;
    }

    /**
     * Obtiene el contador de intentos fallidos acumulados.
     *
     * @return cantidad de intentos fallidos
     */
    public int getIntentos() {
        return intentos;
    }

    /**
     * Establece el contador de intentos fallidos acumulados.
     *
     * @param intentos cantidad de intentos
     */
    public void setIntentos(int intentos) {
        this.intentos = intentos;
    }

    /**
     * Obtiene la fecha y hora hasta la cual el acceso está bloqueado.
     *
     * @return fecha y hora de expiración del bloqueo, o {@code null} si no está bloqueado
     */
    public ZonedDateTime getBloqueadoHasta() {
        return bloqueadoHasta;
    }

    /**
     * Establece la fecha y hora hasta la cual el acceso está bloqueado.
     *
     * @param bloqueadoHasta marca de tiempo de fin del bloqueo
     */
    public void setBloqueadoHasta(ZonedDateTime bloqueadoHasta) {
        this.bloqueadoHasta = bloqueadoHasta;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LoginAttempt that = (LoginAttempt) o;
        return Objects.equals(id, that.id) && Objects.equals(email, that.email) && Objects.equals(ip, that.ip);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, email, ip);
    }
}
