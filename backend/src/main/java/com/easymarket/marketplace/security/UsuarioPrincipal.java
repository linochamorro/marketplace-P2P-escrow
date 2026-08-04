package com.easymarket.marketplace.security;

import com.easymarket.marketplace.model.Rol;

/**
 * Representa la identidad autenticada de un usuario dentro del contexto de seguridad de Spring Security.
 *
 * <p>Almacena de forma inmutable el ID, el correo electrónico y el rol extraídos del token JWT.</p>

 * @param id identificador único del usuario
 * @param email correo electrónico del usuario
 * @param rol rol asignado al usuario
 */
public record UsuarioPrincipal(Long id, String email, Rol rol) {
}
