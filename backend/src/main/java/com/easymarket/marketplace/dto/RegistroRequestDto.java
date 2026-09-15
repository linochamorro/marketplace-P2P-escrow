package com.easymarket.marketplace.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.util.Objects;

/**
 * Objeto de transferencia de datos (DTO) para la solicitud de registro de usuarios ({@code POST /auth/registro}).
 *
 * <p>La validación de entrada (PHA16TSK06; informe de auditoría 2026-09-13, hallazgo A5)
 * rechaza con HTTP 400 los cuerpos sin email o sin password y el email con formato
 * inválido, antes de que el dominio aplique sus propias reglas (política de contraseña
 * ≥8 caracteres, unicidad de email). Los mensajes están en español, consistentes con
 * el precedente de {@code CategoriaRequestDto}.</p>
 */
public class RegistroRequestDto {

    /**
     * Correo electrónico del nuevo usuario.
     *
     * <p>No puede ser nulo, vacío ni estar compuesto solo por blancos
     * ({@code @NotBlank}), y debe tener formato de email válido ({@code @Email}).</p>
     */
    @NotBlank(message = "El email no puede estar vacío")
    @Email(message = "El email debe tener un formato válido")
    private String email;

    /**
     * Contraseña en texto plano del nuevo usuario.
     *
     * <p>No puede ser nula, vacía ni estar compuesta solo por blancos
     * ({@code @NotBlank}). La longitud mínima (≥8 caracteres) la valida el
     * dominio en {@code RegistroService}, no este DTO.</p>
     */
    @NotBlank(message = "La contraseña no puede estar vacía")
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
