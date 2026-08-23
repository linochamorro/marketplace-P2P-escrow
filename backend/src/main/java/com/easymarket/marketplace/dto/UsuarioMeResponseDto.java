package com.easymarket.marketplace.dto;

import com.easymarket.marketplace.security.UsuarioPrincipal;

/**
 * DTO de respuesta HTTP para la identidad mínima de sesión ({@code GET /usuarios/me},
 * PHA06TSK04; Stories 0b y 11 de spec.md).
 *
 * <p>Expone exclusivamente {@code id}, {@code email} y {@code rol} obtenidos del
 * {@link UsuarioPrincipal} autenticado por JWT — sin hash de contraseña, saldo ni ningún otro
 * atributo de la entidad. Es la fuente de identidad para la navegación y las vistas condicionadas
 * por rol del frontend, y no sustituye la autorización backend (plan.md, sección PHA06).</p>
 *
 * @param id identificador único del usuario autenticado (claim {@code id} del JWT)
 * @param email correo electrónico del usuario autenticado (claim {@code subject} del JWT)
 * @param rol nombre del rol del usuario autenticado, por ejemplo {@code "USUARIO"} o
 *            {@code "ADMIN"} (claim {@code rol} del JWT, serializado como {@code name()} para
 *            consistencia con {@link PublicacionResponseDto#estado})
 */
public record UsuarioMeResponseDto(Long id, String email, String rol) {

    /**
     * Mapea el principal autenticado a su representación DTO de identidad mínima.
     *
     * <p>La identidad proviene íntegramente del {@link UsuarioPrincipal} resuelto por el filtro
     * JWT; el endpoint no consulta la base de datos (el JWT es la fuente de verdad de la
     * identidad de sesión, plan.md, sección PHA06).</p>
     *
     * @param principal identidad autenticada resuelta desde el token JWT; no debe ser {@code null}
     * @return DTO {@link UsuarioMeResponseDto} con {@code id}, {@code email} y {@code rol} del
     *         principal
     */
    public static UsuarioMeResponseDto fromPrincipal(UsuarioPrincipal principal) {
        return new UsuarioMeResponseDto(principal.id(), principal.email(), principal.rol().name());
    }
}
