package com.easymarket.marketplace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica el bootstrap local completo de EasyMarket sin declarar un perfil activo en el test.
 *
 * <p>La ausencia deliberada de {@code @ActiveProfiles("dev")} es parte del contrato que se
 * prueba: Spring Boot debe seleccionar {@code dev} mediante {@code spring.profiles.default},
 * aplicar las migraciones estándar y cargar el seed repeatable de {@code db/dev}. El contexto
 * web aleatorio permite comprobar además el endpoint real {@code GET /health}, no únicamente el
 * controller aislado.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class DevBootstrapIntegrationTests {

    /** Contenedor PostgreSQL real usado por Flyway y por el contexto arrancado del test. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Entorno de Spring usado para verificar el perfil predeterminado efectivo. */
    @Autowired
    private Environment environment;

    /** Cliente JDBC usado para verificar las filas creadas por las migraciones y el seed demo. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Puerto aleatorio del servidor embebido arrancado por {@link SpringBootTest}. */
    @LocalServerPort
    private int localServerPort;

    /**
     * Comprueba que el arranque sin {@code @ActiveProfiles} activa efectivamente {@code dev}, que
     * Flyway aplicó el esquema y el seed completo, y que el health check público responde sobre
     * el contexto web arrancado.
     *
     * @throws Exception si la solicitud HTTP al servidor embebido no puede completarse
     */
    @Test
    @DisplayName("El arranque sin perfil explícito usa dev, carga seed y expone /health")
    void bootstrapSinPerfilExplicito_activaDevCargaSeedYRespondeHealth() throws Exception {
        assertThat(environment.getDefaultProfiles())
                .as("dev debe ser el perfil predeterminado configurado")
                .contains("dev");
        assertThat(environment.acceptsProfiles(Profiles.of("dev")))
                .as("el entorno arrancado debe aceptar el perfil dev")
                .isTrue();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM usuarios WHERE email IN (?, ?)", Integer.class,
                "vendedor@easymarket.dev", "comprador@easymarket.dev"))
                .as("el seed debe crear las dos cuentas demo")
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM categorias", Integer.class))
                .as("el seed debe crear el catálogo de categorías")
                .isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM subcategorias", Integer.class))
                .as("el seed debe crear las subcategorías demo")
                .isEqualTo(7);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM publicaciones", Integer.class))
                .as("el seed debe crear las publicaciones navegables")
                .isEqualTo(8);

        HttpRequest healthRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + localServerPort + "/health"))
                .GET()
                .build();
        HttpResponse<String> health = HttpClient.newHttpClient()
                .send(healthRequest, HttpResponse.BodyHandlers.ofString());

        assertThat(health.statusCode()).isEqualTo(HttpStatus.OK.value());
        assertThat(health.body()).contains("\"status\":\"UP\"");
    }
}
