package com.easymarket.marketplace.migration;

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

/**
 * Verifica en PostgreSQL real que V18 agrega la columna {@code imagen_filename} a
 * {@code publicaciones}.
 *
 * <p>La columna es nullable (las publicaciones previas no tienen imagen asignada) y no
 * introduce restricciones CHECK sobre la extensión: el dominio decide el formato al momento
 * de asignar la imagen, no el esquema.</p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=test-secret-key-32-chars-minimum-ok"
})
class FlywayMigrationV18Tests {

    /** Instancia PostgreSQL real que Flyway migra para validar el contrato persistente. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Cliente JDBC usado para comprobar el esquema sin lógica de aplicación intermedia. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Verifica que la migración V18 crea la columna {@code imagen_filename} de tipo
     * {@code varchar(255)} y nullable en la tabla {@code publicaciones}.
     */
    @Test
    @DisplayName("Debe crear la columna imagen_filename nullable en publicaciones")
    void testCreaColumnaImagenFilenameNullable() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_name = 'publicaciones' AND column_name = 'imagen_filename' "
                + "AND data_type = 'character varying' AND character_maximum_length = 255 AND is_nullable = 'YES'",
            Integer.class
        );
        assertThat(count).isEqualTo(1);
    }
}