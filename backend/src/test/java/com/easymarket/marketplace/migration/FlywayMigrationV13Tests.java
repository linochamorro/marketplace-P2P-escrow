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
 * Verifica mediante PostgreSQL real que la migración V13 crea el marcador persistente de avisos
 * puntuales de envío pendiente definido para PHA04TSK26.
 *
 * <p>El contrato asegura que cada transacción y cada notificación solo pueden participar en un
 * marcador, y que ambas referencias deben existir. Esto permite que el futuro job de 48 horas
 * deduplique un aviso sin alterar el estado de la transacción.</p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=test-secret-key-32-chars-minimum-ok"
})
class FlywayMigrationV13Tests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Verifica que un marcador acepta las dos referencias existentes y registra su timestamp.
     */
    @Test
    @DisplayName("Debe insertar un marcador con FKs válidas y created_at")
    void testInsertaMarcadorConFksValidas() {
        Long transaccionId = crearTransaccionTest("fk-valida");
        Long notificacionId = crearNotificacionTest("fk-valida");

        insertarMarcador(transaccionId, notificacionId);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT created_at FROM avisos_envio_pendiente WHERE transaccion_id = ?",
            OffsetDateTime.class,
            transaccionId
        )).isNotNull();
    }

    /**
     * Verifica que la tabla no admite una transacción inexistente ni una notificación inexistente.
     */
    @Test
    @DisplayName("Debe rechazar FKs de transacción y notificación inexistentes")
    void testRechazaFksInvalidas() {
        Long transaccionId = crearTransaccionTest("fk-invalida-notificacion");
        Long notificacionId = crearNotificacionTest("fk-invalida-transaccion");

        assertThatThrownBy(() -> insertarMarcador(999999L, notificacionId))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertarMarcador(transaccionId, 999999L))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Verifica que una transacción no puede recibir dos avisos puntuales de envío pendiente.
     */
    @Test
    @DisplayName("Debe rechazar una segunda notificación para la misma transacción")
    void testRechazaTransaccionDuplicada() {
        Long transaccionId = crearTransaccionTest("transaccion-duplicada");
        insertarMarcador(transaccionId, crearNotificacionTest("transaccion-duplicada-primera"));

        assertThatThrownBy(() -> insertarMarcador(
            transaccionId,
            crearNotificacionTest("transaccion-duplicada-segunda")
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Verifica que una notificación no puede asociarse a dos transacciones distintas.
     */
    @Test
    @DisplayName("Debe rechazar una notificación asociada a una segunda transacción")
    void testRechazaNotificacionDuplicada() {
        Long notificacionId = crearNotificacionTest("notificacion-duplicada");
        insertarMarcador(crearTransaccionTest("notificacion-duplicada-primera"), notificacionId);

        assertThatThrownBy(() -> insertarMarcador(
            crearTransaccionTest("notificacion-duplicada-segunda"),
            notificacionId
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Inserta un marcador con el instante actual para las referencias indicadas.
     *
     * @param transaccionId ID de la transacción cuyo aviso puntual se deduplica.
     * @param notificacionId ID de la notificación in-app asociada al aviso.
     */
    private void insertarMarcador(Long transaccionId, Long notificacionId) {
        jdbcTemplate.update(
            "INSERT INTO avisos_envio_pendiente (transaccion_id, notificacion_id, created_at) VALUES (?, ?, ?)",
            transaccionId,
            notificacionId,
            OffsetDateTime.now()
        );
    }

    /**
     * Crea una notificación in-app con un destinatario existente.
     *
     * @param sufijo valor único para los datos auxiliares de prueba.
     * @return ID de la notificación creada.
     */
    private Long crearNotificacionTest(String sufijo) {
        Long usuarioId = crearUsuarioTest("notificado-v13-" + sufijo + "@example.com");
        return jdbcTemplate.queryForObject(
            "INSERT INTO notificaciones (usuario_id, mensaje, tipo) VALUES (?, ?, ?) RETURNING id",
            Long.class,
            usuarioId,
            "Aviso de prueba V13 " + sufijo,
            "ENVIO_PENDIENTE_48H"
        );
    }

    /**
     * Crea una transacción reservada con sus referencias obligatorias para probar la FK del
     * marcador sin utilizar entidades ni servicios de dominio.
     *
     * @param sufijo valor único para los datos auxiliares de prueba.
     * @return ID de la transacción creada.
     */
    private Long crearTransaccionTest(String sufijo) {
        Long vendedorId = crearUsuarioTest("vendedor-v13-" + sufijo + "@example.com");
        Long compradorId = crearUsuarioTest("comprador-v13-" + sufijo + "@example.com");
        Long categoriaId = crearCategoriaTest("Categoría V13 " + sufijo);
        Long subcategoriaId = crearSubcategoriaTest(categoriaId, "Subcategoría V13 " + sufijo);
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
     * Crea un usuario de prueba.
     *
     * @param email email único del usuario de prueba.
     * @return ID del usuario creado.
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
     * Crea una categoría de prueba.
     *
     * @param nombre nombre único de la categoría.
     * @return ID de la categoría creada.
     */
    private Long crearCategoriaTest(String nombre) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO categorias (nombre) VALUES (?) RETURNING id",
            Long.class,
            nombre
        );
    }

    /**
     * Crea una subcategoría de prueba para una categoría existente.
     *
     * @param categoriaId ID de la categoría padre.
     * @param nombre nombre único dentro de la categoría padre.
     * @return ID de la subcategoría creada.
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
     * Crea una publicación aprobada para el vendedor indicado.
     *
     * @param vendedorId ID del usuario dueño de la publicación.
     * @param categoriaId ID de la categoría de la publicación.
     * @param subcategoriaId ID de la subcategoría de la publicación.
     * @param sufijo valor único para la descripción de prueba.
     * @return ID de la publicación creada.
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
            "Publicación de prueba V13 " + sufijo
        );
    }
}
