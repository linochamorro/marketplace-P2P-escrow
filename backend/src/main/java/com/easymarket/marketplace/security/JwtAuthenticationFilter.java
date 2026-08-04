package com.easymarket.marketplace.security;

import com.easymarket.marketplace.model.Rol;
import com.easymarket.marketplace.service.JwtService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Filtro HTTP de autenticación JWT para Spring Security.
 *
 * <p>Interceptor que se ejecuta una vez por cada solicitud HTTP:
 * <ul>
 *   <li>Lee la cookie HTTP de sesión nombrada {@code jwt}.</li>
 *   <li>Valida la firma y expiración del token mediante {@link JwtService}.</li>
 *   <li>Extrae los claims {@code subject} (email), {@code id} y {@code rol}.</li>
 *   <li>Puebla el {@link SecurityContextHolder} con una instancia de {@link UsuarioPrincipal} y la autoridad {@code ROLE_<ROL>}.</li>
 * </ul>
 * </p>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String COOKIE_JWT_NAME = "jwt";
    private final JwtService jwtService;

    /**
     * Constructor con inyección del servicio JWT.
     *
     * @param jwtService servicio de generación y lectura de tokens JWT
     */
    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        extraerCookieJwt(request).ifPresent(token -> {
            try {
                Claims claims = jwtService.obtenerClaims(token);
                String email = claims.getSubject();
                Long id = claims.get("id", Long.class);
                String rolStr = claims.get("rol", String.class);

                if (email != null && id != null && rolStr != null) {
                    Rol rol = Rol.valueOf(rolStr);
                    UsuarioPrincipal principal = new UsuarioPrincipal(id, email, rol);
                    SimpleGrantedAuthority authority = new SimpleGrantedAuthority("ROLE_" + rol.name());

                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            principal,
                            null,
                            List.of(authority)
                    );
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (Exception e) {
                // Token inválido o expirado: limpiar contexto y permitir que la cadena de seguridad responda 401/403
                SecurityContextHolder.clearContext();
            }
        });

        filterChain.doFilter(request, response);
    }

    /**
     * Extrae el valor de la cookie "jwt" de la solicitud HTTP.
     *
     * @param request solicitud HTTP recibida
     * @return {@link Optional} con el valor de la cookie JWT si existe, o vacío en caso contrario
     */
    private Optional<String> extraerCookieJwt(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(c -> COOKIE_JWT_NAME.equals(c.getName()))
                .map(Cookie::getValue)
                .filter(v -> v != null && !v.isBlank())
                .findFirst();
    }
}
