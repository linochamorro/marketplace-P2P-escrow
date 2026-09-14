package com.easymarket.marketplace.security;

import com.easymarket.marketplace.dto.LoginRequestDto;
import com.easymarket.marketplace.model.Categoria;
import com.easymarket.marketplace.model.EstadoPublicacion;
import com.easymarket.marketplace.model.IdempotencyKey;
import com.easymarket.marketplace.model.LoginAttempt;
import com.easymarket.marketplace.model.Publicacion;
import com.easymarket.marketplace.model.Rol;
import com.easymarket.marketplace.model.Subcategoria;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.AdminAccionRepository;
import com.easymarket.marketplace.repository.CategoriaRepository;
import com.easymarket.marketplace.repository.IdempotencyKeyRepository;
import com.easymarket.marketplace.repository.LoginAttemptRepository;
import com.easymarket.marketplace.repository.NotificacionRepository;
import com.easymarket.marketplace.repository.ProcessedStripeEventRepository;
import com.easymarket.marketplace.repository.PublicacionRepository;
import com.easymarket.marketplace.repository.SubcategoriaRepository;
import com.easymarket.marketplace.repository.TransaccionRepository;
import com.easymarket.marketplace.repository.UsuarioRepository;
import com.easymarket.marketplace.service.RateLimitingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración TDD del filtro de validación de {@code Origin} para métodos no seguros
 * (PHA16TSK05; plan.md, sección "PHA16 — Estabilización de producción y seguridad", fila "CSRF";
 * informe de auditoría 2026-09-13, hallazgo A3; constitution principio 7).
 *
 * <p><strong>Contrato bajo prueba.</strong> Un filtro {@code OncePerRequestFilter} que, para
 * POST/PUT/PATCH/DELETE con header {@code Origin} (o {@code Referer} en fallback, según plan.md)
 * presente y ajeno a la allowlist ({@code app.cors.allowed-origins}), responde 403 sin invocar la
 * cadena; con ambos ausentes deja pasar (clientes no-browser, tests, Stripe); con ruta
 * {@code /webhooks/stripe} deja pasar siempre (exención explícita).</p>
 *
 * <p><strong>Mecánica (patrón PHA12TSK02 / PHA07TSK02).</strong> MockMvc construido con
 * {@code .apply(springSecurity())} para ejercitar la cadena de filtros REAL de
 * {@code SecurityConfig} (incluido el filtro nuevo una vez registrado); PostgreSQL real vía
 * Testcontainers; login HTTP real ({@code POST /auth/login}) para obtener cookies {@code jwt}
 * emitidas de verdad; firma HMAC manual determinista para el webhook (patrón PHA03TSK07/TSK10).</p>
 *
 * <p><strong>Allowlist del contexto de test.</strong> No se fija {@code app.cors.allowed-origins}
 * en {@code @TestPropertySource}, por lo que el filtro lee el valor por defecto
 * ({@code http://localhost:3000}, el mismo de {@code SecurityConfig} y de
 * {@code application-test.yml}): ese es el origen legítimo de los escenarios (c); el origen ajeno
 * es {@code https://sitio-malicioso.com}.</p>
 *
 * <p><strong>Política única de fixtures ADMIN (PHA12TSK06).</strong> Esta clase NO crea ninguna
 * fila ADMIN: la limpieza de {@code @BeforeEach} conserva al único administrador provisionado por
 * el contexto de test (seed V6 con {@code ADMIN_EMAIL}/{@code ADMIN_PASSWORD_HASH}) y los
 * escenarios con gate {@code hasRole("ADMIN")} inician sesión exclusivamente con esa identidad.</p>
 *
 * <p><strong>HALLAZGO 1 DEL RED (evidencia en el log de corrida + diagnóstico temporal
 * eliminado tras acreditar).</strong> La premisa literal "sin el filtro, (a)/(b) devuelven 200" NO
 * se cumple en este código: el {@code CorsFilter} preexistente de {@code SecurityConfig} ya
 * cortocircuita TODA petición con {@code Origin} ajeno (sea cual sea la ruta o el método) con 403
 * de texto plano {@code Invalid CORS request}, antes de que ningún controlador se ejecute
 * (acreditado: {@code GET /health} — ruta {@code permitAll} sin autenticación de por medio — con
 * {@code Origin} ajeno responde 403 con ese cuerpo; sin {@code Origin} responde 200
 * {@code {"status":"UP"}}). Por eso el Red de esta tarea NO es 200-vs-403 para {@code Origin}
 * ajeno, sino triple y honesto: (i) el CONTRATO explícito del filtro (403 JSON con
 * {@code {"mensaje":"Origen no permitido"}}) falta sin él; (ii) el fallback {@code Referer} de
 * plan.md sí cambia status 200→403 (único bloqueo genuinamente nuevo ante navegador); (iii) un
 * {@code Origin} ajeno-a-allowlist pero same-origin-para-CORS ({@code http://localhost} en
 * MockMvc, cuyo host es {@code localhost}) pasa el {@code CorsFilter} y SÍ devuelve 200 sin el
 * filtro — ese es el 200-vs-403 literal que aísla el veredicto propio del filtro del veredicto
 * incidental de CORS. Si el Red falla por otra causa, se corrige el test (orden del prompt).</p>
 *
 * <p><strong>Criterios de la fila PHA16TSK05 cubiertos (13 tests):</strong>
 * <ol>
 *   <li>(a) {@code POST /auth/logout} con {@code Origin} ajeno → 403 JSON con
 *       {@code {"mensaje":"Origen no permitido"}} y cookie {@code jwt} intacta (sin
 *       {@code Set-Cookie} que la limpie).</li>
 *   <li>(a2) {@code POST /auth/logout} con {@code Origin} ajeno-a-allowlist pero same-origin
 *       (el {@code CorsFilter} lo deja pasar) → 403 del filtro; sin filtro es 200 (Red literal).</li>
 *   <li>(b) {@code POST /admin/usuarios/{id}/desbloquear} con admin autenticado y {@code Origin}
 *       ajeno → 403 JSON sin mutación (cero filas en {@code admin_acciones}, bloqueo intacto).</li>
 *   <li>(b2) desbloqueo con admin autenticado y {@code Origin} same-origin-no-listado → 403 sin
 *       mutación; sin filtro es 200 con mutación (Red literal).</li>
 *   <li>(c) ambos con {@code Origin} legítimo → 200 con el contrato actual intacto.</li>
 *   <li>(d) sin header {@code Origin} → contrato actual (logout 200; desbloqueo según autorización
 *       vigente).</li>
 *   <li>(e) {@code POST /webhooks/stripe} con firma válida y sin {@code Origin} → 200 (exento);
 *       y con {@code Origin} same-origin-no-listado → 200 (la exención precede al chequeo de
 *       allowlist del filtro). Nota honesta: con {@code Origin} AJENO el {@code CorsFilter}
 *       preexistente (fuera de scope: prohibido tocar CORS) rechaza primero con 403 — la exención
 *       del filtro no puede ni debe pre-emptarlo; Stripe nunca envía {@code Origin}.</li>
 *   <li>(f) GET con {@code Origin} ajeno → respuesta ACTUAL intacta: 403 del {@code CorsFilter}
 *       (el filtro nuevo ignora métodos seguros; regresión que lo fija por escrito).</li>
 *   <li>(g) Fallback {@code Referer} de plan.md: sin {@code Origin} pero con {@code Referer} ajeno
 *       → 403; con {@code Referer} legítimo → 200.</li>
 * </ol>
 * </p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=clave-secreta-para-pruebas-de-integracion-origin-min-32-chars",
    "stripe.webhook-secret=whsec_FAKE_FOR_CONTEXT_TEST",
    "ADMIN_EMAIL=admin.seed@easymarket.com",
    "ADMIN_PASSWORD_HASH=$2a$10$ezbtTwVogv0lR8nXJuHRk.RGzMVbhLBUjDAt4zzBdJhbqR.U53k6u"
})
class OriginValidationFilterIntegrationTests {

    /** Contenedor PostgreSQL real usado por Flyway y por el contexto arrancado del test. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Origen legítimo: coincide con la allowlist por defecto ({@code app.cors.allowed-origins}). */
    private static final String ORIGEN_LEGITIMO = "http://localhost:3000";

    /** Origen ajeno: no pertenece a la allowlist y debe ser rechazado con 403 en métodos no seguros. */
    private static final String ORIGEN_AJENO = "https://sitio-malicioso.com";

    /**
     * Origen ajeno-a-allowlist pero same-origin para el {@code CorsFilter} en MockMvc (host
     * {@code localhost}, esquema {@code http}, puerto 80): el {@code CorsFilter} lo deja pasar y
     * SOLO el filtro nuevo puede rechazarlo — aísla el veredicto propio del filtro.
     */
    private static final String ORIGEN_MISMO_NO_LISTADO = "http://localhost";

    /** Mensaje exacto del cuerpo JSON 403 del filtro (contrato {@code {"mensaje": ...}} del proyecto). */
    private static final String MENSAJE_ORIGEN_NO_PERMITIDO = "Origen no permitido";

    /** Secret de prueba del webhook, consistente con {@code application-test.yml}. */
    private static final String WEBHOOK_SECRET = "whsec_FAKE_FOR_CONTEXT_TEST";

    /** Email del usuario regular de fixture (nace {@code Rol.USUARIO}, política PHA12TSK06). */
    private static final String EMAIL_REGULAR = "usuario.origin@easymarket.com";

    /** Contraseña plana compartida por los fixtures (el hash del admin seed corresponde a esta). */
    private static final String PASSWORD_RAW = "PasswordSeguro123!";

    /** IP de fixture para el bloqueo permanente del usuario regular. */
    private static final String IP_BLOQUEO = "192.168.1.50";

    /** Contexto web de la aplicación usado para construir el MockMvc con la cadena real. */
    @Autowired
    private WebApplicationContext webApplicationContext;

    /** Repositorio de usuarios (limpieza selectiva que conserva al admin único). */
    @Autowired
    private UsuarioRepository usuarioRepository;

    /** Repositorio de intentos de login (fixture de bloqueo permanente y su verificación). */
    @Autowired
    private LoginAttemptRepository loginAttemptRepository;

    /** Repositorio de auditoría admin (verifica mutación o ausencia de ella en desbloqueo). */
    @Autowired
    private AdminAccionRepository adminAccionRepository;

    /** Repositorio de categorías (fixture del webhook). */
    @Autowired
    private CategoriaRepository categoriaRepository;

    /** Repositorio de subcategorías (fixture del webhook). */
    @Autowired
    private SubcategoriaRepository subcategoriaRepository;

    /** Repositorio de publicaciones (fixture del webhook). */
    @Autowired
    private PublicacionRepository publicacionRepository;

    /** Repositorio de claves de idempotencia (fase "pago en vuelo" del webhook). */
    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    /** Repositorio de notificaciones (limpieza primera por FKs V14/V20, fix PHA15TSK04-L07). */
    @Autowired
    private NotificacionRepository notificacionRepository;

    /** Repositorio de transacciones (verificación de creación por el webhook). */
    @Autowired
    private TransaccionRepository transaccionRepository;

    /** Repositorio de eventos Stripe procesados (verificación de idempotencia del webhook). */
    @Autowired
    private ProcessedStripeEventRepository processedStripeEventRepository;

    /** Codificador BCrypt usado para persistir la contraseña del usuario regular. */
    @Autowired
    private PasswordEncoder passwordEncoder;

    /** Email del ADMIN único provisionado por el contexto de test (propiedad {@code ADMIN_EMAIL}). */
    @Value("${ADMIN_EMAIL}")
    private String adminEmail;

    /** Serializador JSON usado para construir el cuerpo del login real. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Cliente MockMvc configurado con la cadena de seguridad real de la aplicación. */
    private MockMvc mockMvc;

    /** Usuario regular de fixture (potencial afectado del desbloqueo). */
    private Usuario usuarioRegular;

    /** ADMIN único provisionado por el seed V6 (nunca creado por fixtures). */
    private Usuario usuarioAdmin;

    /**
     * Prepara el escenario: MockMvc con la cadena real, limpieza ordenada por FKs (notificaciones
     * primero; usuarios no-admin al final conservando al admin único) y repoblación del usuario
     * regular más la resolución del admin semilla.
     */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        notificacionRepository.deleteAll();
        processedStripeEventRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();
        transaccionRepository.deleteAll();
        adminAccionRepository.deleteAll();
        loginAttemptRepository.deleteAll();
        publicacionRepository.deleteAll();
        subcategoriaRepository.deleteAll();
        categoriaRepository.deleteAll();
        eliminarUsuariosSalvoAdminUnico();

        usuarioRegular = usuarioRepository.save(new Usuario(
                EMAIL_REGULAR,
                passwordEncoder.encode(PASSWORD_RAW),
                Rol.USUARIO,
                0L,
                ZonedDateTime.now(ZoneId.of("America/Lima"))));

        usuarioAdmin = obtenerAdminUnico();
    }

    /**
     * Criterio (a): {@code POST /auth/logout} con sesión real y {@code Origin} ajeno → 403 con el
     * contrato JSON explícito del filtro ({@code {"mensaje":"Origen no permitido"}}) y la cookie
     * {@code jwt} intacta (la respuesta NO incluye {@code Set-Cookie} que la limpie, porque el
     * filtro rechaza antes de que el controlador de logout se ejecute).
     *
     * <p>Red honesto (HALLAZGO 1): sin el filtro el status YA es 403 pero con el cuerpo de texto
     * plano {@code Invalid CORS request} del {@code CorsFilter} preexistente — este test falla en
     * Red en las aserciones de contrato JSON, que es exactamente lo que el filtro aporta.</p>
     *
     * @throws Exception si la interacción HTTP falla
     */
    @Test
    @DisplayName("(a) POST /auth/logout con Origin ajeno responde 403 JSON y no limpia la cookie jwt")
    void logout_ConOriginAjeno_Responde403YCookieIntacta() throws Exception {
        Cookie cookieSesion = obtenerCookieJwtPostLogin(EMAIL_REGULAR, PASSWORD_RAW);

        mockMvc.perform(post("/auth/logout")
                        .cookie(cookieSesion)
                        .header(HttpHeaders.ORIGIN, ORIGEN_AJENO))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_ORIGEN_NO_PERMITIDO));
    }

    /**
     * Criterio (a2): {@code POST /auth/logout} con {@code Origin} ajeno-a-allowlist pero
     * same-origin para el {@code CorsFilter} ({@code http://localhost} en MockMvc) → 403 del
     * filtro nuevo con la cookie intacta. Sin el filtro el {@code CorsFilter} lo deja pasar y el
     * logout responde 200 (Red literal 200-vs-403 que aísla el veredicto propio del filtro).
     *
     * @throws Exception si la interacción HTTP falla
     */
    @Test
    @DisplayName("(a2) POST /auth/logout con Origin no listado pero same-origin responde 403")
    void logout_ConOriginMismoNoListado_Responde403YCookieIntacta() throws Exception {
        Cookie cookieSesion = obtenerCookieJwtPostLogin(EMAIL_REGULAR, PASSWORD_RAW);

        mockMvc.perform(post("/auth/logout")
                        .cookie(cookieSesion)
                        .header(HttpHeaders.ORIGIN, ORIGEN_MISMO_NO_LISTADO))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_ORIGEN_NO_PERMITIDO));
    }

    /**
     * Criterio (b): {@code POST /admin/usuarios/{id}/desbloquear} con admin autenticado y
     * {@code Origin} ajeno → 403 con el contrato JSON explícito del filtro, SIN mutación: no se
     * registra fila en {@code admin_acciones} y el bloqueo permanente del afectado permanece
     * intacto (el desbloqueo NO ocurre).
     *
     * <p>Red honesto (HALLAZGO 1): sin el filtro el status YA es 403 (corte del
     * {@code CorsFilter} preexistente, luego tampoco hay mutación) — este test falla en Red en la
     * aserción de contrato JSON, que es lo que el filtro aporta.</p>
     *
     * @throws Exception si la interacción HTTP falla
     */
    @Test
    @DisplayName("(b) desbloqueo con admin autenticado y Origin ajeno responde 403 sin mutacion")
    void desbloqueo_AdminAutenticadoConOriginAjeno_Responde403SinMutacion() throws Exception {
        simularBloqueoPermanente(usuarioRegular.getEmail(), IP_BLOQUEO);
        Cookie cookieAdmin = obtenerCookieJwtPostLogin(usuarioAdmin.getEmail(), PASSWORD_RAW);

        mockMvc.perform(post("/admin/usuarios/" + usuarioRegular.getId() + "/desbloquear")
                        .cookie(cookieAdmin)
                        .header(HttpHeaders.ORIGIN, ORIGEN_AJENO))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_ORIGEN_NO_PERMITIDO));

        assertThat(adminAccionRepository.count()).isZero();
        assertThat(loginAttemptRepository.findByEmail(usuarioRegular.getEmail())).hasSize(1);
        assertThat(loginAttemptRepository.findByEmail(usuarioRegular.getEmail()).get(0).getIntentos())
                .isEqualTo(12);
    }

    /**
     * Criterio (b2): desbloqueo con admin autenticado y {@code Origin} ajeno-a-allowlist pero
     * same-origin para el {@code CorsFilter} → 403 del filtro nuevo sin mutación. Sin el filtro
     * el desbloqueo SE EJECUTA (200 con auditoría y bloqueo levantado): Red literal que prueba que
     * el filtro cierra la petición simple forzable nombrada en la fila de la tarea.
     *
     * @throws Exception si la interacción HTTP falla
     */
    @Test
    @DisplayName("(b2) desbloqueo con admin autenticado y Origin no listado pero same-origin responde 403 sin mutacion")
    void desbloqueo_AdminAutenticadoConOriginMismoNoListado_Responde403SinMutacion() throws Exception {
        simularBloqueoPermanente(usuarioRegular.getEmail(), IP_BLOQUEO);
        Cookie cookieAdmin = obtenerCookieJwtPostLogin(usuarioAdmin.getEmail(), PASSWORD_RAW);

        mockMvc.perform(post("/admin/usuarios/" + usuarioRegular.getId() + "/desbloquear")
                        .cookie(cookieAdmin)
                        .header(HttpHeaders.ORIGIN, ORIGEN_MISMO_NO_LISTADO))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_ORIGEN_NO_PERMITIDO));

        assertThat(adminAccionRepository.count()).isZero();
        assertThat(loginAttemptRepository.findByEmail(usuarioRegular.getEmail())).hasSize(1);
        assertThat(loginAttemptRepository.findByEmail(usuarioRegular.getEmail()).get(0).getIntentos())
                .isEqualTo(12);
    }

    /**
     * Criterio (c1): {@code POST /auth/logout} con {@code Origin} legítimo → 200 con el contrato
     * actual intacto (cookie expirada con {@code Max-Age=0}, PHA07TSK02).
     *
     * @throws Exception si la interacción HTTP falla
     */
    @Test
    @DisplayName("(c) POST /auth/logout con Origin legitimo responde 200 con contrato intacto")
    void logout_ConOriginLegitimo_Responde200ConContratoIntacto() throws Exception {
        Cookie cookieSesion = obtenerCookieJwtPostLogin(EMAIL_REGULAR, PASSWORD_RAW);

        mockMvc.perform(post("/auth/logout")
                        .cookie(cookieSesion)
                        .header(HttpHeaders.ORIGIN, ORIGEN_LEGITIMO))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.containsString("Max-Age=0")))
                .andExpect(jsonPath("$.mensaje").value("Sesión cerrada"));
    }

    /**
     * Criterio (c2): desbloqueo con admin autenticado y {@code Origin} legítimo → 200 con el
     * contrato actual intacto (mensaje y auditoría append-only, PHA01TSK08).
     *
     * @throws Exception si la interacción HTTP falla
     */
    @Test
    @DisplayName("(c) desbloqueo con admin autenticado y Origin legitimo responde 200")
    void desbloqueo_AdminAutenticadoConOriginLegitimo_Responde200() throws Exception {
        simularBloqueoPermanente(usuarioRegular.getEmail(), IP_BLOQUEO);
        Cookie cookieAdmin = obtenerCookieJwtPostLogin(usuarioAdmin.getEmail(), PASSWORD_RAW);

        mockMvc.perform(post("/admin/usuarios/" + usuarioRegular.getId() + "/desbloquear")
                        .cookie(cookieAdmin)
                        .header(HttpHeaders.ORIGIN, ORIGEN_LEGITIMO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mensaje").value("Cuenta desbloqueada exitosamente"));

        assertThat(adminAccionRepository.count()).isEqualTo(1);
    }

    /**
     * Criterio (d1): {@code POST /auth/logout} sin header {@code Origin} → contrato actual
     * (200 con cookie expirada; clientes no-browser y tests sin Origin siguen pasando).
     *
     * @throws Exception si la interacción HTTP falla
     */
    @Test
    @DisplayName("(d) POST /auth/logout sin Origin responde 200 con contrato actual")
    void logout_SinOrigin_Responde200ConContratoActual() throws Exception {
        mockMvc.perform(post("/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.containsString("Max-Age=0")))
                .andExpect(jsonPath("$.mensaje").value("Sesión cerrada"));
    }

    /**
     * Criterio (d2): desbloqueo con admin autenticado y sin header {@code Origin} → autorización
     * vigente intacta (200 sobre cuenta en bloqueo permanente, con auditoría).
     *
     * @throws Exception si la interacción HTTP falla
     */
    @Test
    @DisplayName("(d) desbloqueo con admin autenticado y sin Origin responde 200")
    void desbloqueo_AdminAutenticadoSinOrigin_Responde200SegunAutorizacionVigente() throws Exception {
        simularBloqueoPermanente(usuarioRegular.getEmail(), IP_BLOQUEO);
        Cookie cookieAdmin = obtenerCookieJwtPostLogin(usuarioAdmin.getEmail(), PASSWORD_RAW);

        mockMvc.perform(post("/admin/usuarios/" + usuarioRegular.getId() + "/desbloquear")
                        .cookie(cookieAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mensaje").value("Cuenta desbloqueada exitosamente"));

        assertThat(adminAccionRepository.count()).isEqualTo(1);
    }

    /**
     * Criterio (e1): {@code POST /webhooks/stripe} con firma válida y sin {@code Origin} → 200
     * (ruta exenta; Stripe no envía {@code Origin} y el emisor se autentica por firma HMAC).
     * Verifica además la transición correcta (una transacción {@code reservada}).
     *
     * @throws Exception si la interacción HTTP o el cálculo HMAC fallan
     */
    @Test
    @DisplayName("(e) webhook con firma valida y sin Origin responde 200 (exento)")
    void webhook_FirmaValidaSinOrigin_Responde200Exento() throws Exception {
        Publicacion publicacion = guardarPublicacionAprobada(2500000L, 3);
        Usuario comprador = guardarCompradorAdicional();
        String eventId = "evt_origin_exento_1";
        String paymentIntentId = "pi_origin_exento_1";
        idempotencyKeyRepository.save(
                new IdempotencyKey(UUID.randomUUID().toString(), paymentIntentId, ZonedDateTime.now()));

        String payload = construirPayloadSucceeded(
                eventId, paymentIntentId, comprador.getId(), publicacion.getId());

        mockMvc.perform(post("/webhooks/stripe")
                        .header("Stripe-Signature", generarHeaderFirmaValida(payload, WEBHOOK_SECRET))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        assertThat(transaccionRepository.count()).isEqualTo(1);
        assertThat(processedStripeEventRepository.existsById(eventId)).isTrue();
    }

    /**
     * Criterio (e2): {@code POST /webhooks/stripe} con firma válida y {@code Origin}
     * same-origin-no-listado → 200 (la exención de la ruta precede al chequeo de allowlist del
     * filtro: el filtro deja pasar aunque el origen no esté listado). Es regresión en ambos
     * sentidos (pasa en Red y en Green) y ejercita la rama de exención del filtro nuevo.
     *
     * <p>Nota honesta de scope: con {@code Origin} AJENO el {@code CorsFilter} preexistente
     * rechaza con 403 antes que cualquier filtro de la cadena — cambiar eso exigiría tocar CORS,
     * prohibido en esta tarea; Stripe nunca envía {@code Origin}, por lo que no hay impacto
     * operativo.</p>
     *
     * @throws Exception si la interacción HTTP o el cálculo HMAC fallan
     */
    @Test
    @DisplayName("(e) webhook con firma valida y Origin no listado pero same-origin responde 200 (exento)")
    void webhook_FirmaValidaConOriginMismoNoListado_Responde200Exento() throws Exception {
        Publicacion publicacion = guardarPublicacionAprobada(2500000L, 3);
        Usuario comprador = guardarCompradorAdicional();
        String eventId = "evt_origin_exento_2";
        String paymentIntentId = "pi_origin_exento_2";
        idempotencyKeyRepository.save(
                new IdempotencyKey(UUID.randomUUID().toString(), paymentIntentId, ZonedDateTime.now()));

        String payload = construirPayloadSucceeded(
                eventId, paymentIntentId, comprador.getId(), publicacion.getId());

        mockMvc.perform(post("/webhooks/stripe")
                        .header("Stripe-Signature", generarHeaderFirmaValida(payload, WEBHOOK_SECRET))
                        .header(HttpHeaders.ORIGIN, ORIGEN_MISMO_NO_LISTADO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        assertThat(transaccionRepository.count()).isEqualTo(1);
        assertThat(processedStripeEventRepository.existsById(eventId)).isTrue();
    }

    /**
     * Criterio (f): GET con {@code Origin} ajeno → respuesta ACTUAL intacta. La respuesta actual,
     * acreditada en el diagnóstico del Red, es 403 del {@code CorsFilter} preexistente (el filtro
     * nuevo solo aplica a POST/PUT/PATCH/DELETE y no altera métodos seguros). Este test fija esa
     * no-interferencia por escrito: pasa en Red y debe seguir pasando en Green.
     *
     * @throws Exception si la interacción HTTP falla
     */
    @Test
    @DisplayName("(f) GET con Origin ajeno conserva la respuesta actual (403 preexistente, filtro no interfiere)")
    void get_ConOriginAjeno_ConservaRespuestaActual() throws Exception {
        Cookie cookieSesion = obtenerCookieJwtPostLogin(EMAIL_REGULAR, PASSWORD_RAW);

        mockMvc.perform(get("/usuarios/me")
                        .cookie(cookieSesion)
                        .header(HttpHeaders.ORIGIN, ORIGEN_AJENO))
                .andExpect(status().isForbidden());
    }

    /**
     * Fallback {@code Referer} de plan.md (reconciliación con la fila, ver decisión 1 del
     * Artifact): {@code POST /auth/logout} sin {@code Origin} pero con {@code Referer} ajeno →
     * 403 (los navegadores antiguos o peticiones simples sin {@code Origin} siguen cubiertas).
     *
     * @throws Exception si la interacción HTTP falla
     */
    @Test
    @DisplayName("(g) POST /auth/logout sin Origin pero con Referer ajeno responde 403")
    void logout_SinOriginConRefererAjeno_Responde403() throws Exception {
        Cookie cookieSesion = obtenerCookieJwtPostLogin(EMAIL_REGULAR, PASSWORD_RAW);

        mockMvc.perform(post("/auth/logout")
                        .cookie(cookieSesion)
                        .header(HttpHeaders.REFERER, ORIGEN_AJENO + "/pagina-cualquiera"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_ORIGEN_NO_PERMITIDO));
    }

    /**
     * Fallback {@code Referer} de plan.md: {@code POST /auth/logout} sin {@code Origin} pero con
     * {@code Referer} legítimo (misma allowlist, derivado esquema://autoridad) → 200.
     *
     * @throws Exception si la interacción HTTP falla
     */
    @Test
    @DisplayName("(g) POST /auth/logout sin Origin pero con Referer legitimo responde 200")
    void logout_SinOriginConRefererLegitimo_Responde200() throws Exception {
        Cookie cookieSesion = obtenerCookieJwtPostLogin(EMAIL_REGULAR, PASSWORD_RAW);

        mockMvc.perform(post("/auth/logout")
                        .cookie(cookieSesion)
                        .header(HttpHeaders.REFERER, ORIGEN_LEGITIMO + "/publicaciones"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mensaje").value("Sesión cerrada"));
    }

    /**
     * Elimina los usuarios de fixtures conservando únicamente las filas con rol {@code ADMIN}
     * (política PHA12TSK06): el ADMIN único provisionado por el seed V6 sobrevive a cada limpieza.
     */
    private void eliminarUsuariosSalvoAdminUnico() {
        usuarioRepository.findAll().stream()
                .filter(usuario -> usuario.getRol() != Rol.ADMIN)
                .forEach(usuarioRepository::delete);
    }

    /**
     * Resuelve la identidad persistida del ADMIN único provisionado por el contexto de test
     * (seed V6, email leído de la propiedad {@code ADMIN_EMAIL}).
     *
     * @return la entidad persistida del único administrador
     * @throws IllegalStateException si el admin sembrado no está presente
     */
    private Usuario obtenerAdminUnico() {
        return usuarioRepository.findByEmail(adminEmail)
                .orElseThrow(() -> new IllegalStateException(
                        "El ADMIN único provisionado por el contexto (" + adminEmail + ") no está presente"));
    }

    /**
     * Realiza login autenticado mediante HTTP {@code POST /auth/login} y extrae la cookie JWT.
     *
     * @param email correo electrónico de la cuenta
     * @param password contraseña en texto plano
     * @return cookie HTTP {@code jwt} emitida por el login real
     * @throws Exception si el login falla
     */
    private Cookie obtenerCookieJwtPostLogin(String email, String password) throws Exception {
        MvcResult resultado = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequestDto(email, password))))
                .andExpect(status().isOk())
                .andReturn();

        Cookie cookieJwt = resultado.getResponse().getCookie("jwt");
        assertThat(cookieJwt).isNotNull();
        return cookieJwt;
    }

    /**
     * Persiste un intento de login en bloqueo permanente (12 intentos) para el email dado.
     *
     * @param email email de la cuenta a bloquear permanentemente
     * @param ip dirección IP de fixture del bloqueo
     */
    private void simularBloqueoPermanente(String email, String ip) {
        loginAttemptRepository.save(new LoginAttempt(
                email,
                ip,
                12,
                RateLimitingService.FECHA_BLOQUEO_PERMANENTE));
    }

    /**
     * Persiste un comprador adicional (auxiliar de fixture, {@code Rol.USUARIO}).
     *
     * @return el usuario comprador persistido
     */
    private Usuario guardarCompradorAdicional() {
        return usuarioRepository.save(new Usuario(
                "comprador.origin@easymarket.com",
                "hash",
                Rol.USUARIO,
                0L,
                ZonedDateTime.now(ZoneId.of("America/Lima"))));
    }

    /**
     * Crea una publicación aprobada con el precio (centavos) y stock dados, con su jerarquía de
     * catálogo, para el escenario del webhook (el vendedor es el usuario regular de fixture).
     *
     * @param precio precio en centavos (entero, constitution principio 3)
     * @param stock stock disponible (entero)
     * @return la publicación persistida en estado {@code APROBADA}
     */
    private Publicacion guardarPublicacionAprobada(long precio, int stock) {
        Categoria categoria = categoriaRepository.save(new Categoria("Vehículos"));
        Subcategoria subcategoria = subcategoriaRepository.save(new Subcategoria(categoria, "Autos"));
        Publicacion publicacion =
                new Publicacion(usuarioRegular, categoria, subcategoria, precio, stock, "Vehículo en venta");
        publicacion.setEstado(EstadoPublicacion.APROBADA);
        return publicacionRepository.save(publicacion);
    }

    /**
     * Construye el cuerpo JSON crudo de un evento {@code payment_intent.succeeded} con la metadata
     * del PaymentIntent conteniendo los IDs de negocio (decisión de plan.md 2026-08-03).
     *
     * @param eventId ID del evento de Stripe ({@code evt_...})
     * @param paymentIntentId ID del PaymentIntent ({@code pi_...})
     * @param compradorId ID del usuario comprador (va en la metadata)
     * @param publicacionId ID de la publicación comprada (va en la metadata)
     * @return el payload JSON como cadena (sin deserializar)
     */
    private String construirPayloadSucceeded(
            String eventId, String paymentIntentId, Long compradorId, Long publicacionId) {
        return "{\"id\":\"" + eventId + "\","
                + "\"object\":\"event\","
                + "\"type\":\"payment_intent.succeeded\","
                + "\"data\":{\"object\":{"
                + "\"id\":\"" + paymentIntentId + "\","
                + "\"object\":\"payment_intent\","
                + "\"amount\":2500000,"
                + "\"currency\":\"pen\","
                + "\"status\":\"succeeded\","
                + "\"metadata\":{"
                + "\"project\":\"easymarket\","
                + "\"comprador_id\":\"" + compradorId + "\","
                + "\"publicacion_id\":\"" + publicacionId + "\""
                + "}}}}";
    }

    /**
     * Calcula el HMAC-SHA256 de un mensaje con una clave secreta, en hexadecimal minúsculas,
     * exactamente como el SDK de Stripe lo computa (patrón PHA03TSK07).
     *
     * @param data mensaje a firmar (formato {@code timestamp + "." + payload})
     * @param key webhook secret (prefijo {@code whsec_})
     * @return la firma HMAC-SHA256 en hexadecimal minúsculas
     * @throws Exception si el algoritmo HMAC no está disponible
     */
    private String computeHmacSha256(String data, String key) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(secretKeySpec);
        byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : hmacBytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /**
     * Genera el header {@code Stripe-Signature} con firma HMAC-SHA256 manual determinista sobre
     * {@code timestamp + "." + payload}, con timestamp dentro de la tolerancia de 5 minutos del
     * SDK de Stripe.
     *
     * @param payload cuerpo crudo del webhook (JSON)
     * @param secret webhook secret usado para firmar
     * @return header completo en formato {@code t=<timestamp>,v1=<hmac>}
     * @throws Exception si falla el cálculo del HMAC
     */
    private String generarHeaderFirmaValida(String payload, String secret) throws Exception {
        long timestamp = System.currentTimeMillis() / 1000L;
        String signedPayload = timestamp + "." + payload;
        String signature = computeHmacSha256(signedPayload, secret);
        return "t=" + timestamp + ",v1=" + signature;
    }
}
