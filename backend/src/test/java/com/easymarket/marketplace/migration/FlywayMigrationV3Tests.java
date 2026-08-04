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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test de migración Flyway para la creación de la tabla {@code admin_acciones} (V3).
 *
 * <p>Verifica que la migración V3 crea la tabla, índices y claves foráneas correctamente
 * sobre PostgreSQL real mediante Testcontainers (Story 0c, plan.md).</p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=test-secret-key-32-chars-minimum-ok"
})
class FlywayMigrationV3Tests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Tabla admin_acciones debe permitir inserciones válidas y respetar FK contra usuarios")
    void testAdminAccionesTableAndConstraints() {
        jdbcTemplate.update(
            "INSERT INTO usuarios (email, password_hash, rol, saldo_disponible) VALUES (?, ?, ?, ?)",
            "admin@example.com", "hash", "ADMIN", 0
        );
        Long adminId = jdbcTemplate.queryForObject("SELECT id FROM usuarios WHERE email = 'admin@example.com'", Long.class);

        jdbcTemplate.update(
            "INSERT INTO usuarios (email, password_hash, rol, saldo_disponible) VALUES (?, ?, ?, ?)",
            "user@example.com", "hash", "USUARIO", 0
        );
        Long userId = jdbcTemplate.queryForObject("SELECT id FROM usuarios WHERE email = 'user@example.com'", Long.class);

        jdbcTemplate.update(
            "INSERT INTO admin_acciones (admin_id, accion, usuario_afectado_id, detalle) VALUES (?, ?, ?, ?)",
            adminId, "DESBLOQUEO_CUENTA", userId, "Desbloqueo permanente de cuenta para email: user@example.com"
        );

        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM admin_acciones WHERE admin_id = ?", Integer.class, adminId);
        assertThat(count).isEqualTo(1);

        assertThatThrownBy(() ->
            jdbcTemplate.update(
                "INSERT INTO admin_acciones (admin_id, accion, usuario_afectado_id, detalle) VALUES (?, ?, ?, ?)",
                9999L, "DESBLOQUEO_CUENTA", userId, "Test FK invalida"
            )
        ).isInstanceOf(Exception.class);
    }
}
