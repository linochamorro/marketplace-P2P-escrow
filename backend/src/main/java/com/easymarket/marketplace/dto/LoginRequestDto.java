package com.easymarket.marketplace.dto;

import java.util.Objects;

/**
 * Objeto de transferencia de datos (DTO) para las solicitudes de inicio de sesión (login).
 */
public class LoginRequestDto {

    private String email;
    private String password;

    /**
     * Constructor por defecto.
     */
    public LoginRequestDto() {
    }

    /**
     * Constructor con todos los campos obligatorios.
     *
     * @param email correo electrónico del usuario
     * @param password contraseña en texto plano
     */
    public LoginRequestDto(String email, String password) {
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
     * @param email email del usuario
     */
    public void setEmail(String email) {
        this.email = email;
    }

    /**
     * Obtiene la contraseña en texto plano.
     *
     * @return contraseña del usuario
     */
    public String getPassword() {
        return password;
    }

    /**
     * Establece la contraseña.
     *
     * @param password contraseña del usuario
     */
    public void setPassword(String password) {
        this.password = password;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LoginRequestDto that = (LoginRequestDto) o;
        return Objects.equals(email, that.email) && Objects.equals(password, that.password);
    }

    @Override
    public int hashCode() {
        return Objects.hash(email, password);
    }
}
