package com.easymarket.marketplace.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Configuración de seguridad HTTP para la aplicación EasyMarket.
 *
 * <p>En PHA00, configura un {@link SecurityFilterChain} que permite el acceso
 * público sin autenticación al endpoint {@code /health} para la verificación de
 * despliegue (Railway). En PHA01 define el bean {@link PasswordEncoder} con
 * {@link BCryptPasswordEncoder} (factor por defecto) según las decisiones de {@code plan.md}.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

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
     * Configura las reglas de autorización y filtros de la cadena de seguridad HTTP.
     *
     * @param http objeto {@link HttpSecurity} utilizado para construir las reglas de seguridad
     * @return el {@link SecurityFilterChain} configurado
     * @throws Exception si ocurre un error durante el armado de la configuración HTTP
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/health").permitAll()
            );

        return http.build();
    }
}
