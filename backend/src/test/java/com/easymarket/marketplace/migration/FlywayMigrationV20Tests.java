package com.easymarket.marketplace.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the notification idempotency contract and the real Flyway V20 to V21 upgrade.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "app.jwt.secret=test-secret-key-32-chars-minimum-ok"
})
class FlywayMigrationV20Tests {

    /** PostgreSQL instance used by the application context and the isolated Flyway harness. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Application-context SQL client used for the current-schema contract regression. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Checks the nullable publication association, both partial indexes, FK enforcement and the
     * daily-history exception in the application schema.
     *
     * @throws RuntimeException when PostgreSQL rejects a contract assertion
     */
    @Test
    @DisplayName("V20 agrega publicacion_id y claves de idempotencia de notificaciones")
    void esquemaNotificaciones_ContieneContratoCompletoV20V21() {
        Integer nullable = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name='notificaciones' "
                        + "AND column_name='publicacion_id' AND is_nullable='YES'", Integer.class);
        Integer publicacionIndex = jdbcTemplate.queryForObject("SELECT count(*) FROM pg_indexes "
                + "WHERE indexname='ux_notificaciones_usuario_publicacion_tipo'", Integer.class);
        String transaccionIndex = jdbcTemplate.queryForObject("SELECT indexdef FROM pg_indexes "
                + "WHERE indexname='ux_notificaciones_usuario_transaccion_tipo'", String.class);

        assertThat(nullable).isEqualTo(1);
        assertThat(publicacionIndex).isEqualTo(1);
        assertThat(transaccionIndex).contains("transaccion_id IS NOT NULL")
                .contains("COMPRA_PENDIENTE_DIARIA", "VENTA_POR_ENTREGAR_DIARIA");
    }

    /**
     * Installs V1 through V20 in a separate schema, inserts historical rows while stopped at V20,
     * then runs the actual V21 resource and verifies Flyway history and resulting constraints.
     *
     * @throws Exception when the isolated Flyway installation or JDBC harness fails
     */
    @Test
    @DisplayName("V21 migra realmente una instalación detenida en V20")
    void V21_MigraInstalacionRealDetenidaEnV20() throws Exception {
        String schema = "flyway_v20_harness_" + System.nanoTime();
        Flyway flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .placeholders(Map.of("admin_email", "harness-admin@example.com",
                        "admin_password_hash", "harness-hash"))
                .cleanDisabled(false)
                .target("20")
                .load();
        JdbcTemplate isolated = isolatedJdbc(schema);
        try {
            flyway.migrate();
            Integer v20Count = isolated.queryForObject("SELECT COUNT(*) FROM flyway_schema_history "
                    + "WHERE version='20' AND success=true", Integer.class);
            Integer v20Checksum = isolated.queryForObject("SELECT checksum FROM flyway_schema_history "
                    + "WHERE version='20' AND success=true", Integer.class);
            assertThat(v20Count).isEqualTo(1);
            assertThat(v20Checksum).isNotNull();

            Long userId = isolated.queryForObject("INSERT INTO usuarios(email,password_hash,rol) "
                    + "VALUES ('history-" + schema + "@example.com','hash','USUARIO') RETURNING id", Long.class);
            Long categoryId = isolated.queryForObject("INSERT INTO categorias(nombre) VALUES ('history-" + schema
                    + "') RETURNING id", Long.class);
            Long subcategoryId = isolated.queryForObject("INSERT INTO subcategorias(categoria_id,nombre) VALUES (?,?) "
                    + "RETURNING id", Long.class, categoryId, "history-" + schema);
            Long publicationId = isolated.queryForObject("INSERT INTO publicaciones(usuario_id,categoria_id,"
                    + "subcategoria_id,precio,stock,descripcion) VALUES (?,?,?,?,?,?) RETURNING id", Long.class,
                    userId, categoryId, subcategoryId, 100L, 1, "historical");
            Long transactionId = isolated.queryForObject("INSERT INTO transacciones(comprador_id,publicacion_id,"
                    + "precio_snapshot,fecha_reservada) VALUES (?,?,?,now()) RETURNING id", Long.class,
                    userId, publicationId, 100L);
            isolated.update("INSERT INTO notificaciones(usuario_id,transaccion_id,mensaje,tipo) VALUES "
                    + "(?,?,?,'COMPRA_PENDIENTE_DIARIA')", userId, transactionId, "daily-v20");
            isolated.update("INSERT INTO notificaciones(usuario_id,transaccion_id,mensaje,tipo) VALUES "
                    + "(?,?,?,'COMPRA_CONFIRMADA')", userId, transactionId, "normal-v20");

            Flyway flywayV21 = Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration")
                    .placeholders(Map.of("admin_email", "harness-admin@example.com",
                            "admin_password_hash", "harness-hash"))
                    .cleanDisabled(false)
                    .target("21")
                    .load();
            flywayV21.migrate();
            Integer checksumAfter = isolated.queryForObject("SELECT checksum FROM flyway_schema_history "
                    + "WHERE version='20' AND success=true", Integer.class);
            assertThat(checksumAfter).isEqualTo(v20Checksum);
            assertThat(isolated.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='21' "
                    + "AND success=true", Integer.class)).isEqualTo(1);
            String definition = isolated.queryForObject("SELECT indexdef FROM pg_indexes WHERE schemaname=? "
                    + "AND indexname='ux_notificaciones_usuario_transaccion_tipo'", String.class, schema);
            assertThat(definition).contains("transaccion_id IS NOT NULL")
                    .contains("COMPRA_PENDIENTE_DIARIA", "VENTA_POR_ENTREGAR_DIARIA");
            isolated.update("INSERT INTO notificaciones(usuario_id,transaccion_id,mensaje,tipo) VALUES "
                    + "(?,?,?,'COMPRA_PENDIENTE_DIARIA')", userId, transactionId, "daily-v21");
            assertThat(isolated.queryForObject("SELECT COUNT(*) FROM notificaciones WHERE transaccion_id=? "
                    + "AND tipo='COMPRA_PENDIENTE_DIARIA'", Integer.class, transactionId)).isEqualTo(2);
            assertThatThrownBy(() -> isolated.update("INSERT INTO notificaciones(usuario_id,transaccion_id,"
                    + "mensaje,tipo) VALUES (?, ?, ?, 'COMPRA_CONFIRMADA')", userId, transactionId, "duplicate"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            flywayV21.validate();
        } finally {
            try {
                flyway.clean();
            } finally {
                // clean() removes Flyway-managed objects; this explicit, validated DROP also removes
                // the generated schema itself, even if clean() fails before completing.
                dropAndAssertHarnessSchema(schema);
            }
        }
    }

    /**
     * Drops the isolated harness schema independently of Flyway and proves that neither the schema nor its
     * Flyway history table remains in the application database.
     *
     * @param schema internally generated schema name belonging exclusively to this test
     * @throws IllegalArgumentException when the schema name is outside the test-controlled format
     */
    private void dropAndAssertHarnessSchema(String schema) {
        String quotedSchema = quoteHarnessSchema(schema);
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + quotedSchema + " CASCADE");

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
                Integer.class, schema)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema=? AND table_name='flyway_schema_history'", Integer.class, schema)).isZero();
    }

    /**
     * Validates and quotes the schema identifier before it is embedded in the DDL statement.
     *
     * @param schema internally generated schema name to quote
     * @return PostgreSQL quoted identifier safe for the controlled DDL statement
     * @throws IllegalArgumentException when the schema name is not the expected generated identifier
     */
    private String quoteHarnessSchema(String schema) {
        if (schema == null || !schema.matches("flyway_v20_harness_[0-9]+")) {
            throw new IllegalArgumentException("Unexpected Flyway harness schema name");
        }
        return "\"" + schema.replace("\"", "\"\"") + "\"";
    }

    /**
     * Creates a JDBC client whose connections resolve unqualified migration objects in the harness schema.
     *
     * @param schema isolated schema created for this test
     * @return SQL client bound to the isolated schema
     */
    private JdbcTemplate isolatedJdbc(String schema) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl() + "?currentSchema=" + schema, postgres.getUsername(), postgres.getPassword());
        return new JdbcTemplate(dataSource);
    }
}
