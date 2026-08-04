package com.easymarket.marketplace.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test de migración Flyway para el seed de la cuenta administrativa única (V6).
 *
 * <p>Verifica que la migración V6 provisiona correctamente la cuenta admin única mediante
 * placeholders inyectados en tiempo de ejecución sobre PostgreSQL real con Testcontainers (Story 0, spec.md):
 * <ul>
 *   <li>La cuenta administrativa existe en la tabla {@code usuarios} con {@code rol = 'ADMIN'}.</li>
 *   <li>El email y el hash de contraseña coinciden exactamente con los placeholders inyectados.</li>
 *   <li>Se garantiza la unicidad e idempotencia mediante {@code ON CONFLICT (email) DO NOTHING}.</li>
 * </ul>
 * </p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    // Fixtures de prueba aislados exclusivamente para la suite de Testcontainers
    "spring.flyway.placeholders.admin_email=admin.test@easymarket.com",
    "spring.flyway.placeholders.admin_password_hash=$2a$10$R9h/cIPz0gi.URNNXRkh2OPST9/PgBkqquzi.Ss7KIUgO2t0jWMUW",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=test-secret-key-32-chars-minimum-ok"
})
class FlywayMigrationV6Tests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Verifica que tras la migración V6 existe exactamente un usuario con rol ADMIN
     * cuyos datos de email y password_hash coinciden con los placeholders inyectados.
     */
    @Test
    @DisplayName("Debe existir la cuenta administrativa única con rol ADMIN tras el seed de V6")
    void testAdminUsuarioExisteTrasSeed() {
        List<Map<String, Object>> usuariosAdmin = jdbcTemplate.queryForList(
            "SELECT email, password_hash, rol FROM usuarios WHERE rol = 'ADMIN'"
        );

        assertThat(usuariosAdmin).hasSize(1);

        Map<String, Object> admin = usuariosAdmin.get(0);
        assertThat(admin.get("email")).isEqualTo("admin.test@easymarket.com");
        assertThat(admin.get("password_hash")).isEqualTo("$2a$10$R9h/cIPz0gi.URNNXRkh2OPST9/PgBkqquzi.Ss7KIUgO2t0jWMUW");
        assertThat(admin.get("rol")).isEqualTo("ADMIN");
    }

    /**
     * Verifica la idempotencia de V6 comprobando que un intento posterior de insertar
     * el mismo email admin no genera errores por la cláusula ON CONFLICT DO NOTHING.
     */
    @Test
    @DisplayName("Debe mantener la unicidad del email admin e ignorar inserciones duplicadas con ON CONFLICT DO NOTHING")
    void testIdempotenciaSeedAdmin() {
        jdbcTemplate.update(
            "INSERT INTO usuarios (email, password_hash, rol) VALUES ('admin.test@easymarket.com', 'otro_hash', 'ADMIN') ON CONFLICT (email) DO NOTHING"
        );

        Integer countAdmin = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM usuarios WHERE email = 'admin.test@easymarket.com'", Integer.class
        );
        assertThat(countAdmin).isEqualTo(1);
    }
}
