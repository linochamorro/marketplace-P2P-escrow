package com.easymarket.marketplace.config;

import com.easymarket.marketplace.security.JwtAuthenticationFilter;
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
 * </ul>
 * </p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final String allowedOriginsRaw;

    /**
     * Constructor con inyección del filtro de autenticación JWT y lista de orígenes CORS autorizados.
     *
     * @param jwtAuthenticationFilter filtro HTTP para la validación de tokens JWT
     * @param allowedOriginsRaw cadena con orígenes CORS permitidos separados por coma (inyectada desde {@code app.cors.allowed-origins})
     */
    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          @Value("${app.cors.allowed-origins:http://localhost:3000}") String allowedOriginsRaw) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
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
     * Configura las reglas de autorización, CORS y filtros de la cadena de seguridad HTTP.
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
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/health", "/auth/**").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/categorias").permitAll()
                // Webhook de Stripe público: Stripe no envía cookie JWT; la autenticación del
                // emisor es la firma HMAC del header Stripe-Signature (PHA03TSK10, plan.md).
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/webhooks/stripe").permitAll()
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .requestMatchers(org.springframework.http.HttpMethod.PATCH, "/publicaciones/*/moderar").hasRole("ADMIN")
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/categorias/**").hasRole("ADMIN")
                .requestMatchers(org.springframework.http.HttpMethod.PUT, "/categorias/**").hasRole("ADMIN")
                .requestMatchers(org.springframework.http.HttpMethod.DELETE, "/categorias/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
