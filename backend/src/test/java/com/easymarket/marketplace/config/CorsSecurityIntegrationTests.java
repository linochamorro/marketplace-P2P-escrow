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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test de integración TDD para la configuración CORS en {@link SecurityConfig}.
 *
 * <p>Verifica los requerimientos de la tarea PHA02TSK18:
 * <ul>
 *   <li>Petición cross-origin con origen permitido y credenciales retorne headers CORS apropiados.</li>
 *   <li>Petición cross-origin con origen no permitido sea rechazada sin cabeceras Access-Control-Allow-Origin.</li>
 * </ul>
 * </p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=clave-secreta-para-pruebas-de-integracion-cors-min-32-chars",
    "app.cors.allowed-origins=http://localhost:3000,https://easymarket.vercel.app"
})
class CorsSecurityIntegrationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private WebApplicationContext webApplicationContext;

    /**
     * Verifica que una petición preflight (OPTIONS) o con header Origin permitido incluya
     * {@code Access-Control-Allow-Origin} y {@code Access-Control-Allow-Credentials: true}.
     *
     * @throws Exception si ocurre un error durante la simulación de MockMvc
     */
    @Test
    @DisplayName("Petición cross-origin con origen autorizado debe retornar headers CORS y allowCredentials=true")
    void cors_OrigenPermitido_RetornaHeadersCorsYCredentials() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        mockMvc.perform(options("/health")
                        .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    /**
     * Verifica que una petición cross-origin con un origen no permitido no contenga los headers de autorización CORS.
     *
     * @throws Exception si ocurre un error durante la simulación de MockMvc
     */
    @Test
    @DisplayName("Petición cross-origin con origen no autorizado debe ser rechazada (sin Access-Control-Allow-Origin)")
    void cors_OrigenNoPermitido_RechazaCors() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        mockMvc.perform(options("/health")
                        .header(HttpHeaders.ORIGIN, "http://sitio-malicioso.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
}
