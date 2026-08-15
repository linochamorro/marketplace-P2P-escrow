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
 * Verifica en PostgreSQL real que V15 crea el log canónico append-only de creación de publicaciones.
 *
 * <p>La migración debe aceptar únicamente eventos {@code CREADA} que referencien una publicación y
 * un actor existentes. También debe rechazar referencias inválidas, otros tipos de evento y toda
 * modificación o eliminación posterior de una fila de auditoría.</p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=test-secret-key-32-chars-minimum-ok"
})
class FlywayMigrationV15Tests {

    /** Instancia PostgreSQL real que Flyway migra para validar el contrato persistente. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Cliente JDBC usado para comprobar las restricciones sin lógica de aplicación intermedia. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Verifica que un evento CREADA acepta las dos claves foráneas obligatorias existentes. */
    @Test
    @DisplayName("Debe insertar un evento CREADA con publicacion_id y actor_id válidos")
    void testInsertaEventoCreadaConFksValidas() {
        Long actorId = crearUsuarioTest("actor-v15-valido@example.com");
        Long publicacionId = crearPublicacionTest("valida");

        Long eventoId = insertarEvento(publicacionId, actorId, "CREADA", null);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT tipo FROM publicacion_eventos WHERE id = ?", String.class, eventoId
        )).isEqualTo("CREADA");
    }

    /** Verifica que la FK obligatoria rechaza una publicación inexistente. */
    @Test
    @DisplayName("Debe rechazar publicacion_id inexistente con DataIntegrityViolationException")
    void testRechazaPublicacionInexistentePorFk() {
        Long actorId = crearUsuarioTest("actor-v15-publicacion-invalida@example.com");

        assertThatThrownBy(() -> insertarEvento(999999L, actorId, "CREADA", null))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Verifica que la FK obligatoria rechaza un actor inexistente. */
    @Test
    @DisplayName("Debe rechazar actor_id inexistente con DataIntegrityViolationException")
    void testRechazaActorInexistentePorFk() {
        Long publicacionId = crearPublicacionTest("actor-invalido");

        assertThatThrownBy(() -> insertarEvento(publicacionId, 999999L, "CREADA", null))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Verifica que el contrato inicial no admite tipos distintos de CREADA. */
    @Test
    @DisplayName("Debe rechazar un tipo distinto de CREADA con DataIntegrityViolationException")
    void testRechazaTipoDistintoDeCreada() {
        Long actorId = crearUsuarioTest("actor-v15-tipo-invalido@example.com");
        Long publicacionId = crearPublicacionTest("tipo-invalido");

        assertThatThrownBy(() -> insertarEvento(publicacionId, actorId, "MODIFICADA", null))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Verifica que PostgreSQL rechaza específicamente UPDATE sobre un evento persistido. */
    @Test
    @DisplayName("Debe rechazar UPDATE sobre un evento append-only con DataIntegrityViolationException")
    void testRechazaUpdateSobreEventoAppendOnly() {
        Long eventoId = crearEventoTest("update");

        assertThatThrownBy(() -> jdbcTemplate.update(
            "UPDATE publicacion_eventos SET detalle = ? WHERE id = ?", "detalle alterado", eventoId
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Verifica que PostgreSQL rechaza específicamente DELETE sobre un evento persistido. */
    @Test
    @DisplayName("Debe rechazar DELETE sobre un evento append-only con DataIntegrityViolationException")
    void testRechazaDeleteSobreEventoAppendOnly() {
        Long eventoId = crearEventoTest("delete");

        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM publicacion_eventos WHERE id = ?", eventoId))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Inserta un evento de auditoría de publicación.
     *
     * @param publicacionId identificador de la publicación existente auditada
     * @param actorId identificador del usuario existente que creó la publicación
     * @param tipo tipo de evento que se desea persistir
     * @param detalle detalle opcional del evento
     * @return identificador del evento insertado
     * @throws DataIntegrityViolationException si una restricción de la migración rechaza la fila
     */
    private Long insertarEvento(Long publicacionId, Long actorId, String tipo, String detalle) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO publicacion_eventos (publicacion_id, actor_id, tipo, detalle) "
                + "VALUES (?, ?, ?, ?) RETURNING id",
            Long.class, publicacionId, actorId, tipo, detalle
        );
    }

    /**
     * Crea un evento CREADA válido para probar la inmutabilidad del trigger.
     *
     * @param sufijo valor único para los datos auxiliares
     * @return identificador del evento creado
     */
    private Long crearEventoTest(String sufijo) {
        Long actorId = crearUsuarioTest("actor-v15-" + sufijo + "@example.com");
        Long publicacionId = crearPublicacionTest(sufijo);
        return insertarEvento(publicacionId, actorId, "CREADA", "evento para " + sufijo);
    }

    /**
     * Crea un usuario de prueba válido.
     *
     * @param email email único del usuario
     * @return identificador del usuario creado
     */
    private Long crearUsuarioTest(String email) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO usuarios (email, password_hash, rol) VALUES (?, ?, ?) RETURNING id",
            Long.class, email, "$2a$10$abcdefghijklmnopqrstuuu", "USUARIO"
        );
    }

    /**
     * Crea una publicación de prueba con todas sus dependencias obligatorias.
     *
     * @param sufijo valor único para los datos auxiliares
     * @return identificador de la publicación creada
     */
    private Long crearPublicacionTest(String sufijo) {
        Long vendedorId = crearUsuarioTest("vendedor-v15-" + sufijo + "@example.com");
        Long categoriaId = crearCategoriaTest("Categoría V15 " + sufijo);
        Long subcategoriaId = crearSubcategoriaTest(categoriaId, "Subcategoría V15 " + sufijo);
        return jdbcTemplate.queryForObject(
            "INSERT INTO publicaciones (usuario_id, categoria_id, subcategoria_id, precio, stock, estado, descripcion) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id",
            Long.class, vendedorId, categoriaId, subcategoriaId, 150000L, 10, "aprobada",
            "Publicación de prueba V15 " + sufijo
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
