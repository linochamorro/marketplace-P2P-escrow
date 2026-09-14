package com.easymarket.marketplace.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Objects;

/**
 * Objeto de transferencia de datos (DTO) para las solicitudes de inicio de sesión (login).
 *
 * <p>La validación de entrada (PHA16TSK06; informe de auditoría 2026-09-13, hallazgo A5)
 * rechaza con HTTP 400 los cuerpos sin email o sin password, antes de la verificación
 * de credenciales y del rate limiting. Esto cambia deliberadamente el contrato de error
 * de {@code POST /auth/login} con cuerpo vacío o campos en blanco de 401 a 400. Los
 * mensajes están en español, consistentes con el precedente de {@code CategoriaRequestDto}.</p>
 */
public class LoginRequestDto {

    /**
     * Correo electrónico del usuario.
     *
     * <p>No puede ser nulo, vacío ni estar compuesto solo por blancos
     * ({@code @NotBlank}). No se exige formato de email: unas credenciales con
     * formato válido pero inexistentes siguen respondiendo 401 genérico.</p>
     */
    @NotBlank(message = "El email no puede estar vacío")
    private String email;

    /**
     * Contraseña en texto plano del usuario.
     *
     * <p>No puede ser nula, vacía ni estar compuesta solo por blancos
     * ({@code @NotBlank}).</p>
     */
    @NotBlank(message = "La contraseña no puede estar vacía")
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
