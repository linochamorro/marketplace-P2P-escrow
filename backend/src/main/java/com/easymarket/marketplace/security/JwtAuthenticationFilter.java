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
     * Indica que este filtro SÍ debe ejecutarse también durante los despachos (dispatches) de
     * tipo ERROR hacia {@code /error}, devolviendo deliberadamente {@code false} para anular el
     * valor por defecto {@code true} de {@link OncePerRequestFilter}.
     *
     * <p><strong>Problema que resuelve.</strong> Cuando una excepción no manejada escapa de un
     * controlador, el contenedor servlet realiza un segundo pase (dispatch ERROR) hacia
     * {@code /error}. Con el comportamiento por defecto ({@code shouldNotFilterErrorDispatch()}
     * = {@code true}), este filtro se omitía en ese segundo pase: la petición a {@code /error}
     * llegaba sin autenticación al {@code SecurityConfig} y la regla
     * {@code anyRequest().authenticated()} respondía <strong>403 Forbidden sin cuerpo</strong>,
     * enmascarando el código real del error interno (500) ante el cliente y dificultando el
     * diagnóstico (un fallo de base de datos se presentaba como si fuera un problema de
     * autorización). Al retornar {@code false}, este filtro vuelve a validar la cookie
     * {@code jwt} durante el dispatch ERROR, conserva el contexto de autenticación y permite
     * que el mecanismo estándar de errores de Spring Boot entregue al cliente su código real
     * con el cuerpo JSON estándar ({@code BasicErrorController}).</p>
     *
     * <p>Origen: incidente documentado el 2026-08-23 (Registro de anomalías de
     * {@code ESTADO_PROYECTO.md}) — un DELETE con violación de clave foránea respondía 403
     * vacío en lugar del error real; corrección definida en plan.md, sección PHA12, fila
     * "Errores internos honestos" (decisión de Lino 2026-08-23; se descartó deliberadamente
     * agregar {@code permitAll("/error")}, de modo que ninguna regla de autorización de
     * {@code SecurityConfig} cambia y las peticiones sin cookie válida siguen recibiendo 403).
     * La cookie JWT viaja intacta en el dispatch ERROR porque el contenedor reutiliza la misma
     * solicitud.</p>
     *
     * @return siempre {@code false}: el filtro NO se omite en el dispatch de tipo ERROR
     */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
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
