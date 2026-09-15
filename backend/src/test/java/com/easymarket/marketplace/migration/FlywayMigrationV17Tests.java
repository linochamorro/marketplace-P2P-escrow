package com.easymarket.marketplace.migration;

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
 * Verifica en PostgreSQL real que V17 crea la tabla append-only {@code publicacion_motivos_historicos}
 * insertada por la moderación al pasar a {@code cambios_solicitados} o {@code rechazada}.
 *
 * <p>La migración debe aceptar referencias válidas a una publicación y al admin moderador, rechazar
 * referencias inválidas, acciones fuera del vocabulario de moderación y motivos nulos, y rechazar toda
 * modificación o eliminación posterior de una fila de auditoría. La FK {@code publicacion_id} es
 * nullable con {@code ON DELETE SET NULL} (mismo patrón que V16): eliminar la publicación rechazada
 * conserva el motivo histórico con {@code publicacion_id = NULL}.</p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=test-secret-key-32-chars-minimum-ok"
})
class FlywayMigrationV17Tests {

    /** Instancia PostgreSQL real que Flyway migra para validar el contrato persistente. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Cliente JDBC usado para comprobar las restricciones sin lógica de aplicación intermedia. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Verifica que un motivo histórico acepta las referencias válidas y persiste la fila. */
    @Test
    @DisplayName("Debe insertar un motivo histórico con publicacion_id y actor_id válidos")
    void testInsertaMotivoHistoricoConFksValidas() {
        Long actorId = crearAdminTest("admin-v17-valido@example.com");
        Long publicacionId = crearPublicacionRechazadaTest("valida");

        Long motivoId = insertarMotivoHistorico(publicacionId, actorId, "RECHAZADA", "Producto prohibido");

        assertThat(jdbcTemplate.queryForObject(
            "SELECT accion FROM publicacion_motivos_historicos WHERE id = ?", String.class, motivoId
        )).isEqualTo("RECHAZADA");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT motivo FROM publicacion_motivos_historicos WHERE id = ?", String.class, motivoId
        )).isEqualTo("Producto prohibido");
    }

    /** Verifica que la FK rechaza una publicación inexistente. */
    @Test
    @DisplayName("Debe rechazar publicacion_id inexistente con DataIntegrityViolationException")
    void testRechazaPublicacionInexistentePorFk() {
        Long actorId = crearAdminTest("admin-v17-publicacion-invalida@example.com");

        assertThatThrownBy(() -> insertarMotivoHistorico(999999L, actorId, "RECHAZADA", "motivo"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Verifica que la FK rechaza un actor inexistente. */
    @Test
    @DisplayName("Debe rechazar actor_id inexistente con DataIntegrityViolationException")
    void testRechazaActorInexistentePorFk() {
        Long publicacionId = crearPublicacionRechazadaTest("actor-invalido");

        assertThatThrownBy(() -> insertarMotivoHistorico(publicacionId, 999999L, "RECHAZADA", "motivo"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Verifica que el CHECK de acción no admite valores fuera del vocabulario de moderación. */
    @Test
    @DisplayName("Debe rechazar una acción no perteneciente a la moderación con DataIntegrityViolationException")
    void testRechazaAccionInvalida() {
        Long actorId = crearAdminTest("admin-v17-accion-invalida@example.com");
        Long publicacionId = crearPublicacionRechazadaTest("accion-invalida");

        assertThatThrownBy(() -> insertarMotivoHistorico(publicacionId, actorId, "APROBADA", "motivo"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Verifica que el motivo es obligatorio en el registro histórico. */
    @Test
    @DisplayName("Debe rechazar un motivo nulo con DataIntegrityViolationException")
    void testRechazaMotivoNulo() {
        Long actorId = crearAdminTest("admin-v17-motivo-nulo@example.com");
        Long publicacionId = crearPublicacionRechazadaTest("motivo-nulo");

        assertThatThrownBy(() -> insertarMotivoHistorico(publicacionId, actorId, "RECHAZADA", null))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Verifica que PostgreSQL rechaza específicamente UPDATE sobre un motivo histórico persistido. */
    @Test
    @DisplayName("Debe rechazar UPDATE sobre un motivo histórico append-only con DataIntegrityViolationException")
    void testRechazaUpdateSobreMotivoHistoricoAppendOnly() {
        Long motivoId = crearMotivoHistoricoTest("update");

        assertThatThrownBy(() -> jdbcTemplate.update(
            "UPDATE publicacion_motivos_historicos SET motivo = ? WHERE id = ?", "motivo alterado", motivoId
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Verifica que PostgreSQL rechaza específicamente DELETE sobre un motivo histórico persistido. */
    @Test
    @DisplayName("Debe rechazar DELETE sobre un motivo histórico append-only con DataIntegrityViolationException")
    void testRechazaDeleteSobreMotivoHistoricoAppendOnly() {
        Long motivoId = crearMotivoHistoricoTest("delete");

        assertThatThrownBy(() -> jdbcTemplate.update(
            "DELETE FROM publicacion_motivos_historicos WHERE id = ?", motivoId
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Verifica la retención del motivo histórico después de borrar su publicación rechazada y que el
     * trigger sigue rechazando mutaciones directas de la fila sobreviviente.
     */
    @Test
    @DisplayName("Debe conservar el motivo histórico con publicacion_id nulo al eliminar la publicación")
    void testConservaMotivoHistoricoAlEliminarPublicacionRechazada() {
        Long actorId = crearAdminTest("admin-v17-conservacion@example.com");
        Long publicacionId = crearPublicacionRechazadaTest("conservada");
        Long motivoId = insertarMotivoHistorico(publicacionId, actorId, "RECHAZADA", "Producto prohibido");

        jdbcTemplate.update("DELETE FROM publicaciones WHERE id = ?", publicacionId);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT publicacion_id FROM publicacion_motivos_historicos WHERE id = ?", Long.class, motivoId
        )).isNull();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT actor_id FROM publicacion_motivos_historicos WHERE id = ?", Long.class, motivoId
        )).isEqualTo(actorId);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT motivo FROM publicacion_motivos_historicos WHERE id = ?", String.class, motivoId
        )).isEqualTo("Producto prohibido");
        assertThatThrownBy(() -> jdbcTemplate.update(
            "UPDATE publicacion_motivos_historicos SET motivo = ? WHERE id = ?", "alterado", motivoId
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
            "DELETE FROM publicacion_motivos_historicos WHERE id = ?", motivoId
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Inserta un motivo histórico de auditoría de moderación.
     *
     * @param publicacionId identificador de la publicación existente moderada
     * @param actorId identificador del admin existente que moderó
     * @param accion acción de moderación ejecutada (CAMBIOS_SOLICITADOS o RECHAZADA)
     * @param motivo motivo literal informado por el admin
     * @return identificador del motivo histórico insertado
     * @throws DataIntegrityViolationException si una restricción de la migración rechaza la fila
     */
    private Long insertarMotivoHistorico(Long publicacionId, Long actorId, String accion, String motivo) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO publicacion_motivos_historicos (publicacion_id, accion, motivo, actor_id) "
                + "VALUES (?, ?, ?, ?) RETURNING id",
            Long.class, publicacionId, accion, motivo, actorId
        );
    }

    /**
     * Crea un motivo histórico válido para probar la inmutabilidad del trigger.
     *
     * @param sufijo valor único para los datos auxiliares
     * @return identificador del motivo histórico creado
     */
    private Long crearMotivoHistoricoTest(String sufijo) {
        Long actorId = crearAdminTest("admin-v17-" + sufijo + "@example.com");
        Long publicacionId = crearPublicacionRechazadaTest(sufijo);
        return insertarMotivoHistorico(publicacionId, actorId, "RECHAZADA", "motivo para " + sufijo);
    }

    /**
     * Crea un usuario admin de prueba válido.
     *
     * @param email email único del usuario
     * @return identificador del usuario creado
     */
    private Long crearAdminTest(String email) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO usuarios (email, password_hash, rol) VALUES (?, ?, ?) RETURNING id",
            Long.class, email, "$2a$10$abcdefghijklmnopqrstuuu", "ADMIN"
        );
    }

    /**
     * Crea una publicación rechazada con todas sus dependencias obligatorias.
     *
     * @param sufijo valor único para los datos auxiliares
     * @return identificador de la publicación creada
     */
    private Long crearPublicacionRechazadaTest(String sufijo) {
        Long vendedorId = jdbcTemplate.queryForObject(
            "INSERT INTO usuarios (email, password_hash, rol) VALUES (?, ?, ?) RETURNING id",
            Long.class, "vendedor-v17-" + sufijo + "@example.com", "$2a$10$abcdefghijklmnopqrstuuu", "USUARIO"
        );
        Long categoriaId = crearCategoriaTest("Categoría V17 " + sufijo);
        Long subcategoriaId = crearSubcategoriaTest(categoriaId, "Subcategoría V17 " + sufijo);
        return jdbcTemplate.queryForObject(
            "INSERT INTO publicaciones (usuario_id, categoria_id, subcategoria_id, precio, stock, estado, descripcion) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id",
            Long.class, vendedorId, categoriaId, subcategoriaId, 150000L, 10, "rechazada",
            "Publicación de prueba V17 " + sufijo
        );
    }

    /**
     * Crea una categoría de prueba.
     *
     * @param nombre nombre único de la categoría
     * @return identificador de la categoría creada
     */
    private Long crearCategoriaTest(String nombre) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO categorias (nombre) VALUES (?) RETURNING id", Long.class, nombre
        );
    }

    /**
     * Crea una subcategoría de prueba bajo una categoría existente.
     *
     * @param categoriaId identificador de la categoría padre
     * @param nombre nombre único de la subcategoría dentro de la categoría
     * @return identificador de la subcategoría creada
     */
    private Long crearSubcategoriaTest(Long categoriaId, String nombre) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO subcategorias (categoria_id, nombre) VALUES (?, ?) RETURNING id",
            Long.class, categoriaId, nombre
        );
    }
}
