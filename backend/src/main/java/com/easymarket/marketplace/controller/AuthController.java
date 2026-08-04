package com.easymarket.marketplace.controller;

import com.easymarket.marketplace.dto.LoginRequestDto;
import com.easymarket.marketplace.dto.RegistroRequestDto;
import com.easymarket.marketplace.dto.RegistroResponseDto;
import com.easymarket.marketplace.exception.CredencialesInvalidasException;
import com.easymarket.marketplace.exception.CuentaBloqueadaException;
import com.easymarket.marketplace.exception.EmailYaRegistradoException;
import com.easymarket.marketplace.exception.PasswordInvalidaException;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.service.LoginService;
import com.easymarket.marketplace.service.RateLimitingService;
import com.easymarket.marketplace.service.RegistroService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
 * <p>Aplica las reglas de transporte de sesión y registro de {@code plan.md} y las Stories 0 y 0b de {@code spec.md}:
 * <ul>
 *   <li>Integración con {@link RegistroService} para la creación de cuentas de usuario.</li>
 *   <li>Integración con {@link RateLimitingService} respetando el contrato de orden de invocación en login.</li>
 *   <li>Resolución de IP resiliente priorizando {@code X-Real-IP} (sanitizado por proxies de borde
 *       como Railway/Cloudflare) y como alternativa el primer token de {@code X-Forwarded-For}.</li>
 *   <li>Respuesta con cookie HTTP {@code httpOnly; Secure; SameSite=None} para el token JWT en login.</li>
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
    private final RegistroService registroService;

    /**
     * Constructor con inyección de los servicios de login, rate limiting y registro.
     *
     * @param loginService servicio de autenticación de credenciales y emisión JWT
     * @param rateLimitingService servicio de rate limiting por combinación email + IP
     * @param registroService servicio de creación de cuentas de usuario
     */
    public AuthController(
            LoginService loginService,
            RateLimitingService rateLimitingService,
            RegistroService registroService
    ) {
        this.loginService = loginService;
        this.rateLimitingService = rateLimitingService;
        this.registroService = registroService;
    }

    /**
     * Endpoint REST {@code POST /auth/registro} para el registro público de nuevos usuarios.
     *
     * <p>Sigue las reglas de la Story 0 de {@code spec.md}:
     * <ul>
     *   <li>Invoca {@link RegistroService#registrarUsuario(String, String)} para validar contraseña y unicidad de correo.</li>
     *   <li>Responde con código HTTP 201 Created y el DTO seguro {@link RegistroResponseDto} sin hash de contraseña.</li>
     *   <li>No aplica rate limiting ni genera cookie JWT (Story 0 no requiere login automático).</li>
     * </ul>
     * </p>
     *
     * @param registroRequest DTO con las credenciales enviadas (email y password)
     * @return {@link ResponseEntity} con código HTTP 201 y los datos del usuario creado
     * @throws EmailYaRegistradoException si el email ya existe (mapeado a HTTP 409 por {@link GlobalExceptionHandler})
     * @throws PasswordInvalidaException si la contraseña no cumple la política (mapeado a HTTP 400 por {@link GlobalExceptionHandler})
     */
    @PostMapping("/registro")
    public ResponseEntity<RegistroResponseDto> registrar(@RequestBody RegistroRequestDto registroRequest) {
        Usuario usuarioCreado = registroService.registrarUsuario(
                registroRequest.getEmail(),
                registroRequest.getPassword()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(RegistroResponseDto.fromEntity(usuarioCreado));
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
