package com.easymarket.marketplace.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests de integración TDD de los headers de seguridad HTTP emitidos por el backend
 * (PHA16TSK08; plan.md, sección "PHA16 — Estabilización de producción y seguridad",
 * fila "Headers de seguridad"; informe de auditoría 2026-09-13, mejora B1).
 *
 * <p><strong>Contrato bajo prueba.</strong> Toda respuesta del backend — tanto de éxito (200)
 * como de rechazo de la cadena de seguridad (403 por falta de autenticación y 403 del
 * {@code OriginValidationFilter} de PHA16TSK05) — debe incluir exactamente estos 4 headers
 * con estos valores literales, configurados vía {@code http.headers(...)} en
 * {@link SecurityConfig#securityFilterChain}:</p>
 * <ul>
 *   <li>{@code X-Content-Type-Options: nosniff}</li>
 *   <li>{@code X-Frame-Options: DENY}</li>
 *   <li>{@code Referrer-Policy: strict-origin-when-cross-origin}</li>
 *   <li>{@code Permissions-Policy: camera=(), microphone=(), geolocation=()}</li>
 * </ul>
 *
 * <p><strong>Mecánica (precedentes PHA12TSK02 y PHA16TSK05).</strong> MockMvc construido con
 * {@code .apply(springSecurity())} para ejercitar la cadena de filtros REAL de
 * {@code SecurityConfig}; PostgreSQL real vía Testcontainers; sin header {@code Origin} en
 * los escenarios 200/403-protegida para no interferir con el {@code CorsFilter} preexistente
 * (PHA02TSK18, fuera de scope: prohibido tocar CORS).</p>
 *
 * <p><strong>Red genuino.</strong> Sin el bloque {@code headers(...)} en
 * {@code SecurityConfig}, los headers están ausentes y cada test falla nombrando el header
 * ausente (aserción por header, no por status).</p>
 *
 * <p><strong>Alcance.</strong> Solo se asertan los 4 headers de la fila de la tarea. Otros
 * headers que Spring Security emite por defecto (ej. {@code Cache-Control}) no se asertan ni
 * se modifican. Cero cambios en autorización, CORS, filtros, CSRF y cookies.</p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=clave-secreta-para-pruebas-de-integracion-headers-min-32-chars",
    "app.cors.allowed-origins=http://localhost:3000"
})
class SecurityHeadersIntegrationTests {

    /** Contenedor PostgreSQL real usado por Flyway y por el contexto arrancado del test. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Valor exacto esperado del header {@code X-Content-Type-Options} (fila PHA16TSK08). */
    private static final String VALOR_NOSNIFF = "nosniff";

    /** Valor exacto esperado del header {@code X-Frame-Options} (fila PHA16TSK08). */
    private static final String VALOR_DENY = "DENY";

    /** Valor exacto esperado del header {@code Referrer-Policy} (fila PHA16TSK08). */
    private static final String VALOR_REFERRER_POLICY = "strict-origin-when-cross-origin";

    /**
     * Valor exacto esperado del header {@code Permissions-Policy} (fila PHA16TSK08:
     * "mínima" — deshabilita cámara, micrófono y geolocalización, APIs que el
     * marketplace no usa en ningún flujo; ver Artifact para la justificación).
     */
    private static final String VALOR_PERMISSIONS_POLICY = "camera=(), microphone=(), geolocation=()";

    /** Contexto web de la aplicación usado para construir el MockMvc con la cadena real. */
    @Autowired
    private WebApplicationContext webApplicationContext;

    /**
     * Respuesta 200 (ruta pública {@code GET /health}) incluye los 4 headers exactos.
     *
     * @throws Exception si la interacción MockMvc falla
     */
    @Test
    @DisplayName("GET /health (200) incluye los 4 headers de seguridad exactos")
    void respuesta200_IncluyeLosCuatroHeadersExactos() throws Exception {
        mockMvc().perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", VALOR_NOSNIFF))
                .andExpect(header().string("X-Frame-Options", VALOR_DENY))
                .andExpect(header().string("Referrer-Policy", VALOR_REFERRER_POLICY))
                .andExpect(header().string("Permissions-Policy", VALOR_PERMISSIONS_POLICY));
    }

    /**
     * Respuesta 403 (ruta protegida {@code GET /usuarios/me} sin cookie JWT) incluye los 4
     * headers exactos: los headers salen también en rechazos, no solo en éxito.
     *
     * @throws Exception si la interacción MockMvc falla
     */
    @Test
    @DisplayName("GET /usuarios/me sin cookie (403) incluye los 4 headers de seguridad exactos")
    void respuesta403SinCookie_IncluyeLosCuatroHeadersExactos() throws Exception {
        mockMvc().perform(get("/usuarios/me"))
                .andExpect(status().isForbidden())
                .andExpect(header().string("X-Content-Type-Options", VALOR_NOSNIFF))
                .andExpect(header().string("X-Frame-Options", VALOR_DENY))
                .andExpect(header().string("Referrer-Policy", VALOR_REFERRER_POLICY))
                .andExpect(header().string("Permissions-Policy", VALOR_PERMISSIONS_POLICY));
    }

    /**
     * Rechazo 403 del {@code OriginValidationFilter} de PHA16TSK05
     * ({@code POST /auth/logout} con {@code Origin} ajeno) incluye los 4 headers exactos:
     * la interacción entre el filtro de origen y los writers de headers queda fijada por
     * escrito (los headers salen también en sus 403).
     *
     * @throws Exception si la interacción MockMvc falla
     */
    @Test
    @DisplayName("POST /auth/logout con Origin ajeno (403 del filtro de origen) incluye los 4 headers exactos")
    void rechazo403DeFiltroOrigen_IncluyeLosCuatroHeadersExactos() throws Exception {
        mockMvc().perform(post("/auth/logout")
                        .header(HttpHeaders.ORIGIN, "https://sitio-malicioso.com"))
                .andExpect(status().isForbidden())
                .andExpect(header().string("X-Content-Type-Options", VALOR_NOSNIFF))
                .andExpect(header().string("X-Frame-Options", VALOR_DENY))
                .andExpect(header().string("Referrer-Policy", VALOR_REFERRER_POLICY))
                .andExpect(header().string("Permissions-Policy", VALOR_PERMISSIONS_POLICY));
    }

    /**
     * Construye un MockMvc con la cadena de seguridad REAL de la aplicación
     * (patrón de {@code CorsSecurityIntegrationTests} y
     * {@code OriginValidationFilterIntegrationTests}).
     *
     * @return cliente MockMvc con {@code springSecurity()} aplicado
     */
    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }
}
