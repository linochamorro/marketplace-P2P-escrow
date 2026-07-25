package com.easymarket.marketplace.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Manejador global de excepciones REST para la aplicación EasyMarket.
 *
 * <p>Mapea las excepciones de dominio a códigos de respuesta HTTP y cuerpos JSON
 * consistentes conforme a las especificaciones de {@code spec.md}:
 * <ul>
 *   <li>{@link CredencialesInvalidasException} &rarr; HTTP 401 Unauthorized (mensaje genérico).</li>
 *   <li>{@link CuentaBloqueadaException} &rarr; HTTP 429 Too Many Requests.</li>
 *   <li>{@link EmailYaRegistradoException} &rarr; HTTP 409 Conflict.</li>
 *   <li>{@link PasswordInvalidaException} &rarr; HTTP 400 Bad Request.</li>
 * </ul>
 * </p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Maneja fallos de verificación de credenciales retornando un mensaje genérico.
     *
     * @param ex excepción de credenciales inválidas
     * @return {@link ResponseEntity} con código HTTP 401 Unauthorized y cuerpo con mensaje genérico
     */
    @ExceptionHandler(CredencialesInvalidasException.class)
    public ResponseEntity<Map<String, String>> handleCredencialesInvalidas(CredencialesInvalidasException ex) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("mensaje", ex.getMessage()));
    }

    /**
     * Maneja accesos rechazados por rate limiting / bloqueo de cuenta o IP.
     *
     * @param ex excepción de cuenta bloqueada
     * @return {@link ResponseEntity} con código HTTP 429 Too Many Requests
     */
    @ExceptionHandler(CuentaBloqueadaException.class)
    public ResponseEntity<Map<String, String>> handleCuentaBloqueada(CuentaBloqueadaException ex) {
        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("mensaje", ex.getMessage()));
    }

    /**
     * Maneja intentos de registro con correo electrónico ya existente.
     *
     * @param ex excepción de email duplicado
     * @return {@link ResponseEntity} con código HTTP 409 Conflict
     */
    @ExceptionHandler(EmailYaRegistradoException.class)
    public ResponseEntity<Map<String, String>> handleEmailYaRegistrado(EmailYaRegistradoException ex) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(Map.of("mensaje", ex.getMessage()));
    }

    /**
     * Maneja violaciones a la política mínima de contraseña durante el registro.
     *
     * @param ex excepción de contraseña inválida
     * @return {@link ResponseEntity} con código HTTP 400 Bad Request
     */
    @ExceptionHandler(PasswordInvalidaException.class)
    public ResponseEntity<Map<String, String>> handlePasswordInvalida(PasswordInvalidaException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("mensaje", ex.getMessage()));
    }
}
