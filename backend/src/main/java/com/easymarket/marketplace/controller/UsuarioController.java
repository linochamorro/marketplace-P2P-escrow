package com.easymarket.marketplace.controller;

import com.easymarket.marketplace.dto.UsuarioMeResponseDto;
import com.easymarket.marketplace.security.UsuarioPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint REST de identidad mínima de sesión ({@code GET /usuarios/me}, PHA06TSK04;
 * Stories 0b y 11 de spec.md).
 *
 * <p>Devuelve la identidad del usuario autenticado obtenida exclusivamente del
 * {@link UsuarioPrincipal} resuelto por el filtro JWT: {@code id}, {@code email} y {@code rol}.
 * El endpoint no consulta la base de datos — el JWT es la fuente de verdad de la identidad de
 * sesión (plan.md, sección PHA06) — y es la fuente para la navegación y las vistas condicionadas
 * por rol del frontend, sin sustituir la autorización backend. La ruta queda protegida por
 * {@code anyRequest().authenticated()} de {@code SecurityConfig} (sin requestMatcher nuevo).
 * Convive con {@code GET /usuarios/me/saldo} de {@link SaldoController}: rutas distintas, sin
 * conflicto de mapeo.</p>
 */
@RestController
@RequestMapping("/usuarios/me")
public class UsuarioController {

    /**
     * Endpoint REST {@code GET /usuarios/me} para consultar la identidad mínima del usuario
     * autenticado.
     *
     * <p>Responde 200 OK con el DTO {@link UsuarioMeResponseDto} construido a partir de
     * {@code principal.id()}, {@code principal.email()} y {@code principal.rol().name()} — sin
     * campos adicionales y sin acceso a repositorios o servicios. Si la petición carece de cookie
     * JWT válida, la cadena de seguridad la rechaza con 403 antes de llegar aquí.</p>
     *
     * @param principal identidad del usuario autenticado mediante JWT
     * @return {@link ResponseEntity} con código HTTP 200 OK y el DTO de identidad mínima
     */
    @GetMapping
    public ResponseEntity<UsuarioMeResponseDto> obtenerMiIdentidad(
            @AuthenticationPrincipal UsuarioPrincipal principal
    ) {
        return ResponseEntity.ok(UsuarioMeResponseDto.fromPrincipal(principal));
    }
}
