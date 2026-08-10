package com.easymarket.marketplace.migration;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies on a real PostgreSQL instance that V14 associates optional notification projections
 * with transactions for the future daily-open-transaction notice.
 *
 * <p>The migration must preserve notifications unrelated to a transaction, reject non-existent
 * transaction references through its explicit foreign key, and expose the exact lookup index used
 * by the future job. Notifications remain a convenience projection, not the append-only canonical
 * transaction-event log.</p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=test-secret-key-32-chars-minimum-ok"
})
class FlywayMigrationV14Tests {

    /** Real PostgreSQL database that Flyway migrates before each test context. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** JDBC client used to verify the schema contract independently of application logic. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Verifies that a notification can reference an existing transaction through V14's foreign key.
     */
    @Test
    @DisplayName("Debe insertar una notificación con transaccion_id válido")
    void testInsertaNotificacionConTransaccionValida() {
        Long transaccionId = crearTransaccionTest("fk-valida");
        Long usuarioId = crearUsuarioTest("notificado-v14-fk-valida@example.com");

        Long notificacionId = insertarNotificacion(usuarioId, transaccionId, "fk-valida");

        assertThat(jdbcTemplate.queryForObject(
            "SELECT transaccion_id FROM notificaciones WHERE id = ?", Long.class, notificacionId
        )).isEqualTo(transaccionId);
    }

    /**
     * Verifies that the explicit foreign key rejects a notification pointing to a transaction that
     * does not exist.
     */
    @Test
    @DisplayName("Debe rechazar transaccion_id inexistente con DataIntegrityViolationException")
    void testRechazaNotificacionConTransaccionInexistente() {
        Long usuarioId = crearUsuarioTest("notificado-v14-fk-invalida@example.com");

        assertThatThrownBy(() -> insertarNotificacion(usuarioId, 999999L, "fk-invalida"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Verifies that a notification without a transaction remains valid, preserving the existing
     * non-transaction notification use cases.
     */
    @Test
    @DisplayName("Debe permitir una notificación sin transacción")
    void testPermiteNotificacionSinTransaccion() {
        Long usuarioId = crearUsuarioTest("notificado-v14-sin-transaccion@example.com");

        Long notificacionId = insertarNotificacion(usuarioId, null, "sin-transaccion");

        assertThat(jdbcTemplate.queryForObject(
            "SELECT transaccion_id FROM notificaciones WHERE id = ?", Long.class, notificacionId
        )).isNull();
    }

    /**
     * Verifies that PostgreSQL exposes the exact index column order required to find the latest
     * daily notice for a transaction recipient and type.
     */
    @Test
    @DisplayName("Debe crear el índice de avisos diarios con sus cuatro columnas en orden")
    void testCreaIndiceDeAvisosDiarios() {
        String definicionIndice = jdbcTemplate.queryForObject(
            "SELECT indexdef FROM pg_indexes WHERE schemaname = current_schema() "
                + "AND tablename = 'notificaciones' "
                + "AND indexname = 'idx_notificaciones_transaccion_usuario_tipo_created_at'",
            String.class
        );

        assertThat(definicionIndice).contains(
            "(transaccion_id, usuario_id, tipo, created_at)"
        );
    }

    /**
     * Inserts an in-app notification with an optional transaction association.
     *
     * @param usuarioId identifier of the existing recipient user
     * @param transaccionId identifier of the associated transaction, or {@code null} when unrelated
     * @param sufijo unique value appended to the test message and type
     * @return identifier of the inserted notification
     */
    private Long insertarNotificacion(Long usuarioId, Long transaccionId, String sufijo) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO notificaciones (usuario_id, transaccion_id, mensaje, tipo, created_at) "
                + "VALUES (?, ?, ?, ?, ?) RETURNING id",
            Long.class,
            usuarioId,
            transaccionId,
            "Aviso de prueba V14 " + sufijo,
            "PRUEBA_V14_" + sufijo,
            OffsetDateTime.now()
        );
    }

    /**
     * Creates a reserved transaction with all mandatory foreign-key dependencies.
     *
     * @param sufijo unique value for all supporting rows
     * @return identifier of the created transaction
     */
    private Long crearTransaccionTest(String sufijo) {
        Long vendedorId = crearUsuarioTest("vendedor-v14-" + sufijo + "@example.com");
        Long compradorId = crearUsuarioTest("comprador-v14-" + sufijo + "@example.com");
        Long categoriaId = crearCategoriaTest("Categoría V14 " + sufijo);
        Long subcategoriaId = crearSubcategoriaTest(categoriaId, "Subcategoría V14 " + sufijo);
        Long publicacionId = crearPublicacionTest(vendedorId, categoriaId, subcategoriaId, sufijo);
        return jdbcTemplate.queryForObject(
            "INSERT INTO transacciones (estado, comprador_id, publicacion_id, precio_snapshot, fecha_reservada) "
                + "VALUES (?, ?, ?, ?, ?) RETURNING id",
            Long.class,
            "reservada",
            compradorId,
            publicacionId,
            10000L,
            OffsetDateTime.now()
        );
    }

    /**
     * Creates one test user.
     *
     * @param email unique email of the user
     * @return identifier of the created user
     */
    private Long crearUsuarioTest(String email) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO usuarios (email, password_hash, rol) VALUES (?, ?, ?) RETURNING id",
            Long.class,
            email,
            "$2a$10$abcdefghijklmnopqrstuuu",
            "USUARIO"
        );
    }

    /**
     * Creates one test category.
     *
     * @param nombre unique category name
     * @return identifier of the created category
     */
    private Long crearCategoriaTest(String nombre) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO categorias (nombre) VALUES (?) RETURNING id", Long.class, nombre
        );
    }

    /**
     * Creates one test subcategory under an existing category.
     *
     * @param categoriaId identifier of the parent category
     * @param nombre unique subcategory name within the parent
     * @return identifier of the created subcategory
     */
    private Long crearSubcategoriaTest(Long categoriaId, String nombre) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO subcategorias (categoria_id, nombre) VALUES (?, ?) RETURNING id",
            Long.class,
            categoriaId,
            nombre
        );
    }

    /**
     * Creates an approved publication for the specified seller.
     *
     * @param vendedorId identifier of the publication owner
     * @param categoriaId identifier of the publication category
     * @param subcategoriaId identifier of the publication subcategory
     * @param sufijo unique value for the test description
     * @return identifier of the created publication
     */
    private Long crearPublicacionTest(Long vendedorId, Long categoriaId, Long subcategoriaId, String sufijo) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO publicaciones (usuario_id, categoria_id, subcategoria_id, precio, stock, estado, descripcion) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id",
            Long.class,
            vendedorId,
            categoriaId,
            subcategoriaId,
            150000L,
            10,
            "aprobada",
            "Publicación de prueba V14 " + sufijo
        );
    }
}
