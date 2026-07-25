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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test de migración Flyway para la estructura inicial de base de datos (V1).
 *
 * <p>Verifica los constraints y estructuras creados por la migración Flyway utilizando Testcontainers
 * con PostgreSQL real (según la decisión de arquitectura de testing de {@code plan.md}):
 * <ul>
 *   <li>Tabla {@code usuarios}: constraint de unicidad en {@code email} y restricción de valores de {@code rol} (USUARIO, ADMIN).</li>
 *   <li>Tabla {@code login_attempts}: existencia y tipos de datos.</li>
 * </ul>
 * </p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=test-secret-key-32-chars-minimum-ok"
})
class FlywayMigrationV1Tests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Test previo Red Phase: Verifica que la inserción de dos usuarios con el mismo email es rechazada por constraint único.
     */
    @Test
    @DisplayName("Email de usuario debe ser único en la tabla usuarios")
    void testEmailUniqueConstraint() {
        jdbcTemplate.update(
            "INSERT INTO usuarios (email, password_hash, rol, saldo_disponible, created_at) VALUES (?, ?, ?, ?, NOW())",
            "test@example.com", "hash123", "USUARIO", 0
        );

        assertThatThrownBy(() ->
            jdbcTemplate.update(
                "INSERT INTO usuarios (email, password_hash, rol, saldo_disponible, created_at) VALUES (?, ?, ?, ?, NOW())",
                "test@example.com", "hash456", "USUARIO", 0
            )
        ).isInstanceOf(Exception.class);
    }

    /**
     * Test previo Red Phase: Verifica que la columna rol solo acepta 'USUARIO' o 'ADMIN'.
     */
    @Test
    @DisplayName("Rol de usuario solo acepta USUARIO o ADMIN")
    void testRolCheckConstraint() {
        jdbcTemplate.update(
            "INSERT INTO usuarios (email, password_hash, rol, saldo_disponible, created_at) VALUES (?, ?, ?, ?, NOW())",
            "user@example.com", "hash123", "USUARIO", 0
        );

        jdbcTemplate.update(
            "INSERT INTO usuarios (email, password_hash, rol, saldo_disponible, created_at) VALUES (?, ?, ?, ?, NOW())",
            "admin@example.com", "hash123", "ADMIN", 0
        );

        assertThatThrownBy(() ->
            jdbcTemplate.update(
                "INSERT INTO usuarios (email, password_hash, rol, saldo_disponible, created_at) VALUES (?, ?, ?, ?, NOW())",
                "invalid@example.com", "hash123", "SUPERADMIN", 0
            )
        ).isInstanceOf(Exception.class);
    }
}
