package com.easymarket.marketplace.service;

import com.easymarket.marketplace.model.Usuario;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * Servicio de dominio responsable de la generación y validación de JSON Web Tokens (JWT).
 *
 * <p>Aplica las especificaciones de autenticación definidas en {@code plan.md}:
 * <ul>
 *   <li>Firma HMAC-SHA utilizando una clave secreta configurada.</li>
 *   <li>Expiración por defecto de 24 horas (86,400,000 milisegundos).</li>
 *   <li>Inclusión del email en el sujeto (subject) y del id y rol en los claims del token.</li>
 * </ul>
 * </p>
 */
@Service
public class JwtService {

    private final String secret;
    private final long expirationMs;

    /**
     * Constructor con inyección de propiedades para la configuración del servicio JWT.
     *
     * @param secret clave secreta de firma JWT (definida en app.jwt.secret)
     * @param expirationMs tiempo de expiración del token en milisegundos (definido en app.jwt.expiration-ms)
     */
    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiration-ms:86400000}") long expirationMs
    ) {
        this.secret = secret;
        this.expirationMs = expirationMs;
    }

    /**
     * Genera un token JWT firmado para el usuario especificado.
     *
     * @param usuario entidad de usuario autenticado
     * @return representación en cadena del token JWT generado
     */
    public String generarToken(Usuario usuario) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        Date ahora = new Date();
        Date expiracion = new Date(ahora.getTime() + expirationMs);

        return Jwts.builder()
                .subject(usuario.getEmail())
                .claim("id", usuario.getId())
                .claim("rol", usuario.getRol().name())
                .issuedAt(ahora)
                .expiration(expiracion)
                .signWith(key)
                .compact();
    }

    /**
     * Obtiene los claims contenidos en un token JWT.
     *
     * @param token cadena JWT a decodificar y validar
     * @return los {@link Claims} extraídos del token
     */
    public Claims obtenerClaims(String token) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
