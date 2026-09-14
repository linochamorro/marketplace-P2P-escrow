package com.easymarket.marketplace.config;

import com.easymarket.marketplace.security.JwtAuthenticationFilter;
import com.easymarket.marketplace.security.OriginValidationFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Configuración de seguridad HTTP, CORS y autorización por rol para la aplicación EasyMarket.
 *
 * <p>Integra el filtro {@link JwtAuthenticationFilter} para la autenticación basada en cookies HTTP
 * y la autorización estricta de rutas administrativas (Story 0c, spec.md):
 * <ul>
 *   <li>Sesiones apátridas (stateless).</li>
 *   <li>Soporte CORS con credenciales para intercambio de cookies httpOnly en cross-origin.</li>
 *   <li>Rutas públicas abiertas: {@code /health}, {@code /auth/**} (registro y login) y
 *       {@code POST /webhooks/stripe} (webhook de Stripe — se autentica por firma HMAC del
 *       header {@code Stripe-Signature}, no por cookie JWT; PHA03TSK10).</li>
 *   <li>Rutas administrativas protegidas exigiendo rol {@code ADMIN} (ej. {@code /admin/**}).</li>
 *   <li>Soporte para anotaciones de método {@code @PreAuthorize}.</li>
 *   <li>Headers de seguridad en toda respuesta ({@code X-Content-Type-Options},
 *       {@code X-Frame-Options: DENY}, {@code Referrer-Policy} y
 *       {@code Permissions-Policy} mínima; PHA16TSK08).</li>
 * </ul>
 * </p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final OriginValidationFilter originValidationFilter;
    private final String allowedOriginsRaw;

    /**
     * Constructor con inyección de los filtros de seguridad y lista de orígenes CORS autorizados.
     *
     * @param jwtAuthenticationFilter filtro HTTP para la validación de tokens JWT
     * @param originValidationFilter filtro HTTP de validación de {@code Origin}/{@code Referer}
     *                               para métodos no seguros (PHA16TSK05)
     * @param allowedOriginsRaw cadena con orígenes CORS permitidos separados por coma (inyectada desde {@code app.cors.allowed-origins})
     */
    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          OriginValidationFilter originValidationFilter,
                          @Value("${app.cors.allowed-origins:http://localhost:3000}") String allowedOriginsRaw) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.originValidationFilter = originValidationFilter;
        this.allowedOriginsRaw = allowedOriginsRaw;
    }

    /**
     * Provee el codificador de contraseñas {@link PasswordEncoder} basado en BCrypt con factor por defecto.
     *
     * @return la instancia de {@link BCryptPasswordEncoder}
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Define la configuración de CORS para la aplicación web.
     *
     * <p>Configura {@code allowCredentials(true)} obligatoriamente para la transmisión
     * de la cookie {@code jwt} httpOnly y restringe los orígenes a la lista explícita
     * proveniente de la variable de entorno configurada.</p>
     *
     * @return el origen de configuración de CORS {@link CorsConfigurationSource}
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        List<String> allowedOrigins = Arrays.stream(allowedOriginsRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Cache-Control", "Content-Type", "Idempotency-Key"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * Configura las reglas de autorización, CORS, headers de seguridad y filtros de la cadena HTTP.
     *
     * <p>PHA16TSK05 registra {@code originValidationFilter} ANTES del {@code CorsFilter} (y por
     * tanto antes que {@code JwtAuthenticationFilter}) mediante un único
     * {@code addFilterBefore}, sin tocar ninguna regla de {@code authorizeHttpRequests}, ni CORS,
     * ni cookies, ni CSRF: el filtro solo rechaza (403 JSON) o deja pasar, nunca autentica ni
     * autoriza.</p>
     *
     * <p>PHA16TSK08 agrega el bloque {@code headers(...)} con los 4 headers de la fila de la
     * tarea (plan.md "PHA16 — Estabilización de producción y seguridad", fila "Headers de
     * seguridad"; mejora B1 de la auditoría 2026-09-13). Se emiten en TODA respuesta de la
     * cadena — éxito (200) y rechazos (403) — porque {@code HeaderWriterFilter} envuelve la
     * respuesta antes que los filtros registrados con {@code addFilterBefore} la rechacen.
     * No se altera ninguna otra configuración: autorización, CORS, filtros, CSRF y cookies
     * quedan intactos, y los demás writers por defecto de Spring Security no se deshabilitan.</p>
     *
     * @param http objeto {@link HttpSecurity} utilizado para construir las reglas de seguridad
     * @return el {@link SecurityFilterChain} configurado
     * @throws Exception si ocurre un error durante el armado de la configuración HTTP
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(Customizer.withDefaults())
            .csrf(AbstractHttpConfigurer::disable)
            .headers(headers -> headers
                // PHA16TSK08: fija el valor por defecto de Spring (nosniff) por escrito.
                .contentTypeOptions(Customizer.withDefaults())
                // PHA16TSK08: DENY — ninguna página del marketplace admite ser embebida en
                // iframes (no existe flujo de embed propio; Stripe usa sus propios iframes
                // cross-origin, no afectados por este header).
                .frameOptions(frame -> frame.deny())
                // PHA16TSK08: valor literal exigido por la fila de la tarea.
                .referrerPolicy(referrer -> referrer.policy(
                        ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                // PHA16TSK08: mínima — deshabilita cámara, micrófono y geolocalización, APIs
                // que ningún flujo del marketplace usa (verificado por búsqueda en el frontend:
                // cero usos de getUserMedia/geolocation fuera de comentarios de tests E2E sobre
                // los iframes de Stripe), por lo que no rompe nada.
                .permissionsPolicy(permissions -> permissions.policy(
                        "camera=(), microphone=(), geolocation=()")))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/health", "/auth/**").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/categorias").permitAll()
                // Webhook de Stripe público: Stripe no envía cookie JWT; la autenticación del
                // emisor es la firma HMAC del header Stripe-Signature (PHA03TSK10, plan.md).
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/webhooks/stripe").permitAll()
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .requestMatchers(org.springframework.http.HttpMethod.PATCH, "/publicaciones/*/moderar").hasRole("ADMIN")
                .requestMatchers(org.springframework.http.HttpMethod.PATCH, "/disputas/*/resolver").hasRole("ADMIN")
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/categorias/**").hasRole("ADMIN")
                .requestMatchers(org.springframework.http.HttpMethod.PUT, "/categorias/**").hasRole("ADMIN")
                .requestMatchers(org.springframework.http.HttpMethod.DELETE, "/categorias/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .addFilterBefore(originValidationFilter, org.springframework.web.filter.CorsFilter.class)
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
