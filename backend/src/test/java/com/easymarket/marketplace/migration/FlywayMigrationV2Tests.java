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
 * Test de migración Flyway para la adición de constraint único en {@code login_attempts} (V2).
 *
 * <p>Verifica que la migración V2 aplica el constraint de unicidad en {@code (email, ip)}
 * sobre PostgreSQL real mediante Testcontainers.</p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=test-secret-key-32-chars-minimum-ok"
})
class FlywayMigrationV2Tests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Combinación (email, ip) en login_attempts debe ser única por constraint V2")
    void testLoginAttemptsEmailIpUniqueConstraint() {
        jdbcTemplate.update(
            "INSERT INTO login_attempts (email, ip, intentos, bloqueado_hasta) VALUES (?, ?, ?, NULL)",
            "test@example.com", "192.168.1.1", 1
        );

        assertThatThrownBy(() ->
            jdbcTemplate.update(
                "INSERT INTO login_attempts (email, ip, intentos, bloqueado_hasta) VALUES (?, ?, ?, NULL)",
                "test@example.com", "192.168.1.1", 2
            )
        ).isInstanceOf(Exception.class);
    }
}
