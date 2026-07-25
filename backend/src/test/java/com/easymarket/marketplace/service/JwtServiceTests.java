package com.easymarket.marketplace.service;

import com.easymarket.marketplace.model.Rol;
import com.easymarket.marketplace.model.Usuario;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas unitarias para {@link JwtService}.
 *
 * <p>Verifica la generación de tokens JWT, firma con clave secreta y lectura de claims
 * conforme a las especificaciones de {@code plan.md}.</p>
 */
class JwtServiceTests {

    private static final String SECRET_TEST = "clave-secreta-para-pruebas-unitarias-minimo-32-caracteres";
    private static final long EXPIRATION_MS_TEST = 86400000L; // 24h

    private JwtService jwtService;
    private Usuario usuario;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(SECRET_TEST, EXPIRATION_MS_TEST);

        usuario = new Usuario(
                "vendedor@easymarket.com",
                "$2a$10$dummyHashValueForTestOnly",
                Rol.USUARIO,
                1000L,
                ZonedDateTime.now(ZoneId.of("America/Lima"))
        );
        usuario.setId(42L);
    }

    @Test
    @DisplayName("generarToken() produce un token firmado con subject, id y rol correctos")
    void generarToken_CreaTokenValidoConClaimsCorrectos() {
        // Act
        String token = jwtService.generarToken(usuario);

        // Assert
        assertThat(token).isNotNull().isNotBlank();

        Claims claims = jwtService.obtenerClaims(token);
        assertThat(claims.getSubject()).isEqualTo("vendedor@easymarket.com");
        assertThat(claims.get("id", Long.class)).isEqualTo(42L);
        assertThat(claims.get("rol", String.class)).isEqualTo("USUARIO");
        assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());
    }
}
