package com.easymarket.marketplace.controller;

import com.easymarket.marketplace.dto.LoginRequestDto;
import com.easymarket.marketplace.exception.CredencialesInvalidasException;
import com.easymarket.marketplace.exception.CuentaBloqueadaException;
import com.easymarket.marketplace.service.LoginService;
import com.easymarket.marketplace.service.RateLimitingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * Controller REST responsable de los endpoints de autenticación (login y registro) de EasyMarket.
 *
 * <p>Aplica las reglas de transporte de sesión de {@code plan.md} y la Story 0b de {@code spec.md}:
 * <ul>
 *   <li>Integración con {@link RateLimitingService} respetando el contrato de orden de invocación.</li>
 *   <li>Resolución de IP resiliente priorizando {@code X-Real-IP} (sanitizado por proxies de borde
 *       como Railway/Cloudflare) y como alternativa el primer token de {@code X-Forwarded-For}
 *       (patrón de Railway ingress proxy).</li>
 *   <li>Respuesta con cookie HTTP {@code httpOnly; Secure; SameSite=None} para el token JWT.</li>
 *   <li>Rechazo con código HTTP 401 y mensaje genérico en caso de credenciales erróneas.</li>
 *   <li>Rechazo con código HTTP 429 cuando la combinación email + IP supera el número de intentos.</li>
 * </ul>
 * </p>
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final LoginService loginService;
    private final RateLimitingService rateLimitingService;

    /**
     * Constructor con inyección de los servicios de login y rate limiting.
     *
     * @param loginService servicio de autenticación de credenciales y emisión JWT
     * @param rateLimitingService servicio de rate limiting por combinación email + IP
     */
    public AuthController(LoginService loginService, RateLimitingService rateLimitingService) {
        this.loginService = loginService;
        this.rateLimitingService = rateLimitingService;
    }

    /**
     * Endpoint REST {@code POST /auth/login} para el inicio de sesión de usuarios.
     *
     * <p>Sigue el contrato de orden de invocación de {@link RateLimitingService}:
     * 1. {@code evaluarAcceso()} verifica si la clave email + IP está actualmente bloqueada (lanza 429 si lo está).
     * 2. {@code loginService.login()} verifica credenciales y genera token JWT.
     * 3. En caso de fallo de credenciales, {@code registrarFallo()} incrementa intentos en rate limiting y lanza 401.
     * 4. En caso de éxito, {@code registrarExito()} reinicia intentos y setea la cookie {@code jwt} en la respuesta.
     * </p>
     *
     * @param loginRequest DTO con credenciales (email y password)
     * @param request objeto de solicitud HTTP para extraer la dirección IP
     * @return {@link ResponseEntity} con la cookie {@code jwt} en la cabecera {@code Set-Cookie} y respuesta HTTP 200
     * @throws CredencialesInvalidasException si el email o la contraseña son incorrectos
     * @throws CuentaBloqueadaException si la combinación email + IP está bloqueada
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(
            @RequestBody LoginRequestDto loginRequest,
            HttpServletRequest request
    ) {
        String ip = obtenerIpCliente(request);

        // 1. Evaluar si la clave email+IP se encuentra bloqueada (lanza CuentaBloqueadaException -> 429)
        rateLimitingService.evaluarAcceso(loginRequest.getEmail(), ip);

        String token;
        try {
            // 2. Verificar credenciales y generar JWT
            token = loginService.login(loginRequest.getEmail(), loginRequest.getPassword());
        } catch (CredencialesInvalidasException e) {
            // Registrar fallo acumulativo en rate limiting
            rateLimitingService.registrarFallo(loginRequest.getEmail(), ip);
            throw e;
        }

        // 3. Registrar login exitoso (reinicia intentos)
        rateLimitingService.registrarExito(loginRequest.getEmail(), ip);

        // 4. Crear cookie httpOnly; Secure; SameSite=None (plan.md)
        ResponseCookie jwtCookie = ResponseCookie.from("jwt", token)
                .httpOnly(true)
                .secure(true)
                .path("/")
                .sameSite("None")
                .maxAge(Duration.ofDays(1))
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, jwtCookie.toString())
                .body(Map.of("mensaje", "Inicio de sesión exitoso"));
    }

    /**
     * Extrae la dirección IP del cliente priorizando la cabecera {@code X-Real-IP} (sanitizada por
     * proxies de borde como Railway/Cloudflare) y posteriormente el primer token de {@code X-Forwarded-For}
     * (donde la arquitectura de proxy de Railway coloca la IP real del cliente).
     *
     * @param request objeto de solicitud HTTP
     * @return dirección IP de origen del cliente
     */
    private String obtenerIpCliente(HttpServletRequest request) {
        // 1. Priorizar X-Real-IP (sanitizada por proxy de borde)
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank() && !"unknown".equalsIgnoreCase(xRealIp)) {
            return xRealIp.trim();
        }

        // 2. Si no está disponible X-Real-IP, evaluar X-Forwarded-For (Railway coloca la IP del cliente en la posición 0)
        String ipHeader = request.getHeader("X-Forwarded-For");
        if (ipHeader != null && !ipHeader.isBlank() && !"unknown".equalsIgnoreCase(ipHeader)) {
            if (ipHeader.contains(",")) {
                return ipHeader.split(",")[0].trim();
            }
            return ipHeader.trim();
        }

        // 3. Fallback a dirección remota TCP
        return request.getRemoteAddr();
    }
}
