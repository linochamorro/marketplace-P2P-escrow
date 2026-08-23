package com.easymarket.marketplace.controller;

import com.easymarket.marketplace.dto.LoginRequestDto;
import com.easymarket.marketplace.model.Rol;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.LoginAttemptRepository;
import com.easymarket.marketplace.repository.UsuarioRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas de integración del despacho ERROR hacia {@code /error} (PHA12TSK02; plan.md,
 * sección PHA12 — fila "Errores internos honestos"; Registro de anomalías 2026-08-23 de
 * {@code ESTADO_PROYECTO.md}).
 *
 * <p><strong>Defecto verificado.</strong> Cuando una excepción NO manejada escapa de un
 * controlador, el contenedor servlet realiza un dispatch de tipo ERROR hacia {@code /error}.
 * Por defecto {@code OncePerRequestFilter.shouldNotFilterErrorDispatch()} devuelve
 * {@code true}, por lo que {@code JwtAuthenticationFilter} se OMITE en ese segundo pase: la
 * petición a {@code /error} llega sin autenticación, {@code anyRequest().authenticated()} de
 * {@code SecurityConfig} la rechaza y el cliente recibe un <strong>403 Forbidden vacío</strong>
 * que enmascara el error interno real (diagnóstico empírico del 2026-08-23: un DELETE con
 * violación de FK respondía 403 sin cuerpo). La corrección bajo prueba es el override
 * {@code shouldNotFilterErrorDispatch() = false} en {@code JwtAuthenticationFilter}, de modo
 * que el dispatch ERROR conserve autenticación y el cliente reciba el código real (500) con
 * el cuerpo JSON estándar de Spring Boot.</p>
 *
 * <p><strong>Mecánica elegida (declarada como decisión).</strong> Se usa
 * {@link WebEnvironment#RANDOM_PORT} con servidor embebido real y el {@link HttpClient}
 * nativo del JDK contra el puerto inyectado con {@link LocalServerPort}, siguiendo la
 * recomendación explícita de la documentación de Spring Boot 4.1 ("when testing the MVC layer
 * for exception handling or lower-level concerns like custom error pages, you might need to
 * start a fully running server"). Motivo: el dispatch ERROR es un mecanismo del CONTENEDOR
 * servlet (el error page registrado redirige a {@code /error} cuando la excepción escapa);
 * MockMvc no lo ejercita — una excepción no manejada en MockMvc se propaga al test como
 * {@code ServletException} sin pasar jamás por {@code /error} —, por lo que solo un servidor
 * real reproduce fielmente el camino defectuoso y su corrección. Se usa el {@code HttpClient}
 * del JDK porque {@code TestRestTemplate} no existe en los módulos de Spring Boot 4.1 que
 * incluye este proyecto y agregar una dependencia nueva al {@code pom.xml} queda fuera del
 * alcance declarado de la tarea. PostgreSQL real vía Testcontainers y flujo de login HTTP real
 * ({@code POST /auth/login}) para obtener una cookie {@code jwt} emitida de verdad, según el
 * patrón establecido de la suite.</p>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(ErroresInternosIntegrationTests.ControladorErrorInterno.class)
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=clave-secreta-pruebas-error-interno-minimo-32-caracteres",
    "ADMIN_EMAIL=admin.seed@easymarket.com",
    "ADMIN_PASSWORD_HASH=$2a$10$R9h/cIPz0gi.URNNxrkh2OPST9/PgBkqquzi.Ss7KIUgO2t0jWMUW"
})
class ErroresInternosIntegrationTests {

    /** Contenedor PostgreSQL real usado por Flyway y por el contexto arrancado del test. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Email de la identidad de prueba usada por el escenario de login real. */
    private static final String EMAIL_PRUEBA = "usuario.error-interno@easymarket.com";

    /** Contraseña plana de la identidad de prueba (persistida hasheada con BCrypt). */
    private static final String PASSWORD_RAW = "PasswordSeguro123";

    /** Ruta del endpoint de prueba que dispara el error interno no manejado (solo fuentes de test). */
    private static final String RUTA_ERROR_INTERNO = "/prueba-error-interno";

    /** Puerto aleatorio donde arrancó el servidor embebido real de esta corrida. */
    @LocalServerPort
    private int puertoServidor;

    /** Cliente HTTP real del JDK usado para hablar con el servidor embebido. */
    private HttpClient clienteHttp;

    /** Repositorio de intentos de login, limpiado para aislar el rate limiting entre escenarios. */
    @Autowired
    private LoginAttemptRepository loginAttemptRepository;

    /** Repositorio de usuarios, limpiado y repoblado con la identidad del escenario. */
    @Autowired
    private UsuarioRepository usuarioRepository;

    /** Codificador BCrypt usado para persistir la contraseña del usuario de prueba. */
    @Autowired
    private PasswordEncoder passwordEncoder;

    /** Serializador JSON usado para construir el cuerpo del login e inspeccionar respuestas. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Identidad persistida del escenario; su {@code id} debe reflejarse en {@code /usuarios/me}. */
    private Usuario usuarioPrueba;

    /**
     * Controlador de prueba declarado SOLO en fuentes de test (registrado mediante
     * {@code @Import} sobre esta clase de test, nunca en código de producción) cuyo único
     * endpoint lanza una {@link RuntimeException} no manejada para provocar el dispatch ERROR
     * real del contenedor hacia {@code /error}. La ruta es exclusiva y no colisiona con
     * ningún mapeo de producción; está protegida por {@code anyRequest().authenticated()}
     * de {@code SecurityConfig} (no hay {@code permitAll} para ella).
     */
    @RestController
    static class ControladorErrorInterno {

        /**
         * Endpoint de prueba protegido que siempre falla con una excepción no manejada.
         *
         * @return nunca retorna normalmente: siempre lanza {@link RuntimeException}
         */
        @GetMapping(RUTA_ERROR_INTERNO)
        public String dispararErrorInterno() {
            throw new RuntimeException("fallo interno deliberado de prueba (PHA12TSK02)");
        }
    }

    /**
     * Prepara el escenario por cada test: crea el cliente HTTP del JDK, aísla las tablas de
     * identidad y persiste el usuario de prueba con contraseña BCrypt válida para habilitar el
     * login HTTP real.
     */
    @BeforeEach
    void setUp() {
        clienteHttp = HttpClient.newHttpClient();
        loginAttemptRepository.deleteAll();
        usuarioRepository.deleteAll();

        usuarioPrueba = usuarioRepository.save(new Usuario(
                EMAIL_PRUEBA,
                passwordEncoder.encode(PASSWORD_RAW),
                Rol.USUARIO,
                0L,
                ZonedDateTime.now(ZoneId.of("America/Lima"))
        ));
    }

    /**
     * Criterio (a): un usuario AUTENTICADO que dispara un error interno no manejado recibe el
     * código HTTP REAL (500) con el cuerpo JSON estándar de Spring Boot, NO un 403 vacío.
     *
     * <p>Es el corazón de PHA12TSK02: antes del fix, el dispatch ERROR hacia {@code /error}
     * llegaba sin autenticación (filtro JWT omitido) y {@code anyRequest().authenticated()}
     * respondía 403 sin cuerpo, enmascarando el fallo. Tras el fix, el filtro corre también en
     * el dispatch ERROR, conserva la autenticación y el {@code BasicErrorController} renderiza
     * su respuesta JSON estándar ({@code timestamp}, {@code status}, {@code error}, {@code path})
     * con el estado original 500.</p>
     *
     * @throws Exception si la comunicación HTTP o el parseo JSON del cuerpo de respuesta fallan
     */
    @Test
    @DisplayName("usuario autenticado que dispara error no manejado recibe 500 con cuerpo JSON estándar (no 403 vacío)")
    void usuarioAutenticado_ConErrorNoManejado_Recibe500ConCuerpoJsonEstandar() throws Exception {
        String tokenJwt = iniciarSesion();

        HttpResponse<String> respuesta = enviarGet(RUTA_ERROR_INTERNO, tokenJwt);

        assertThat(respuesta.statusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(respuesta.headers().firstValue("Content-Type"))
                .isPresent()
                .hasValueSatisfying(tipo -> assertThat(
                        MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(tipo))).isTrue());

        JsonNode cuerpo = objectMapper.readTree(respuesta.body());
        assertThat(cuerpo.hasNonNull("timestamp")).isTrue();
        assertThat(cuerpo.get("status").asInt()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(cuerpo.get("error").asText()).isEqualTo("Internal Server Error");
        assertThat(cuerpo.get("path").asText()).isEqualTo(RUTA_ERROR_INTERNO);
    }

    /**
     * Criterio (b) — regresión: un usuario autenticado en un endpoint protegido NORMAL sigue
     * recibiendo su respuesta correcta. Se usa {@code GET /usuarios/me} (PHA06TSK04), ruta real
     * cubierta por {@code anyRequest().authenticated()}, verificando que la identidad devuelta
     * refleja los claims del JWT del usuario de prueba. Garantiza que la corrección del dispatch
     * ERROR no altera el comportamiento normal autenticado.
     *
     * @throws Exception si la comunicación HTTP o el parseo JSON del cuerpo de respuesta fallan
     */
    @Test
    @DisplayName("usuario autenticado en endpoint protegido normal sigue recibiendo su respuesta correcta (regresión)")
    void usuarioAutenticado_EndpointProtegidoNormal_RecibeRespuestaCorrecta() throws Exception {
        String tokenJwt = iniciarSesion();

        HttpResponse<String> respuesta = enviarGet("/usuarios/me", tokenJwt);

        assertThat(respuesta.statusCode()).isEqualTo(HttpStatus.OK.value());

        JsonNode cuerpo = objectMapper.readTree(respuesta.body());
        assertThat(cuerpo.get("id").asLong()).isEqualTo(usuarioPrueba.getId());
        assertThat(cuerpo.get("email").asText()).isEqualTo(EMAIL_PRUEBA);
        assertThat(cuerpo.get("rol").asText()).isEqualTo(Rol.USUARIO.name());
    }

    /**
     * Criterio (c): una petición SIN cookie a un endpoint protegido sigue recibiendo 403 — la
     * barrera de seguridad NO se relaja con la corrección (en particular, no se agregó
     * {@code permitAll("/error")} ni cambió regla alguna de {@code SecurityConfig}). Debe
     * cumplirse antes y después del fix.
     *
     * @throws Exception si la comunicación HTTP o el parseo JSON del cuerpo de respuesta fallan
     */
    @Test
    @DisplayName("petición sin cookie a endpoint protegido sigue recibiendo 403 (barrera no relajada)")
    void peticionSinCookie_EndpointProtegido_Recibe403() throws Exception {
        HttpResponse<String> respuesta = enviarGet("/usuarios/me", null);

        assertThat(respuesta.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    /**
     * Ejecuta el flujo real de login HTTP ({@code POST /auth/login}) contra el servidor embebido
     * y extrae el token de la cookie {@code jwt} emitida, de modo que las peticiones posteriores
     * usan un token generado de verdad por el sistema (patrón de la suite).
     *
     * @return el valor del token JWT emitido en la cookie {@code jwt} del login
     * @throws IOException si ocurre un error de E/S durante la petición HTTP
     * @throws InterruptedException si el hilo es interrumpido durante la petición HTTP
     */
    private String iniciarSesion() throws IOException, InterruptedException {
        HttpRequest peticionLogin = HttpRequest.newBuilder()
                .uri(URI.create(urlBase() + "/auth/login"))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"" + EMAIL_PRUEBA + "\",\"password\":\"" + PASSWORD_RAW + "\"}"))
                .build();

        HttpResponse<Void> respuestaLogin = clienteHttp.send(peticionLogin, HttpResponse.BodyHandlers.discarding());
        assertThat(respuestaLogin.statusCode()).isEqualTo(HttpStatus.OK.value());

        Optional<String> setCookie = respuestaLogin.headers().firstValue("Set-Cookie");
        assertThat(setCookie).isPresent();
        String valorCookie = setCookie.orElseThrow();
        assertThat(valorCookie).startsWith("jwt=");

        int finToken = valorCookie.indexOf(';');
        String token = finToken == -1 ? valorCookie.substring("jwt=".length())
                : valorCookie.substring("jwt=".length(), finToken);
        assertThat(token).isNotBlank();
        return token;
    }

    /**
     * Envía una petición GET al servidor embebido, opcionalmente autenticada con la cookie
     * {@code jwt}.
     *
     * @param ruta ruta relata del recurso solicitado (ej. {@code /usuarios/me})
     * @param tokenJwt token JWT para el header {@code Cookie}, o {@code null} para una petición sin cookie
     * @return la respuesta HTTP completa con cuerpo leído como cadena
     * @throws IOException si ocurre un error de E/S durante la petición HTTP
     * @throws InterruptedException si el hilo es interrumpido durante la petición HTTP
     */
    private HttpResponse<String> enviarGet(String ruta, String tokenJwt) throws IOException, InterruptedException {
        HttpRequest.Builder constructor = HttpRequest.newBuilder().uri(URI.create(urlBase() + ruta)).GET();
        if (tokenJwt != null) {
            constructor.header("Cookie", "jwt=" + tokenJwt);
        }
        return clienteHttp.send(constructor.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Construye la URL base del servidor embebido a partir del puerto aleatorio inyectado.
     *
     * @return URL base local, por ejemplo {@code http://localhost:49152}
     */
    private String urlBase() {
        return "http://localhost:" + puertoServidor;
    }
}
