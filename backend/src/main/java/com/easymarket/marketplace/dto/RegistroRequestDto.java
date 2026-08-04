package com.easymarket.marketplace.dto;

import java.util.Objects;

/**
 * Objeto de transferencia de datos (DTO) para la solicitud de registro de usuarios ({@code POST /auth/registro}).
 */
public class RegistroRequestDto {

    private String email;
    private String password;

    /**
     * Constructor por defecto requerido para deserialización JSON.
     */
    public RegistroRequestDto() {
    }

    /**
     * Constructor con todos los campos obligatorios.
     *
     * @param email correo electrónico del nuevo usuario
     * @param password contraseña en texto plano
     */
    public RegistroRequestDto(String email, String password) {
        this.email = email;
        this.password = password;
    }

    /**
     * Obtiene el correo electrónico ingresado.
     *
     * @return email del usuario
     */
    public String getEmail() {
        return email;
    }

    /**
     * Establece el correo electrónico.
     *
     * @param email correo electrónico
     */
    public void setEmail(String email) {
        this.email = email;
    }

    /**
     * Obtiene la contraseña en texto plano.
     *
     * @return contraseña
     */
    public String getPassword() {
        return password;
    }

    /**
     * Establece la contraseña en texto plano.
     *
     * @param password contraseña
     */
    public void setPassword(String password) {
        this.password = password;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RegistroRequestDto that = (RegistroRequestDto) o;
        return Objects.equals(email, that.email) && Objects.equals(password, that.password);
    }

    @Override
    public int hashCode() {
        return Objects.hash(email, password);
    }
}
