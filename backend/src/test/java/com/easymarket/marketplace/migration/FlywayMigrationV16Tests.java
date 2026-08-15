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
 * Verifica en PostgreSQL real que V16 conserva el evento append-only cuando se elimina su
 * publicación rechazada.
 *
 * <p>La migración debe anular únicamente {@code publicacion_id} mediante la FK, conservar el
 * {@code actor_id} obligatorio y mantener el trigger de V15 que rechaza modificaciones directas
 * de los eventos.</p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=test-secret-key-32-chars-minimum-ok"
})
class FlywayMigrationV16Tests {

    /** Instancia PostgreSQL real que Flyway migra para validar el contrato persistente. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Cliente JDBC usado para comprobar las restricciones sin lógica de aplicación intermedia. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Verifica la retención del evento después de borrar su publicación y la inmutabilidad que V15
     * ya imponía sobre dicho evento.
     */
    @Test
    @DisplayName("Debe conservar el evento con publicación nula y rechazar referencias o mutaciones inválidas")
    void testConservaEventoAlEliminarPublicacionRechazada() {
        Long actorId = crearUsuarioTest("actor-v16@example.com");
        Long publicacionId = crearPublicacionRechazadaTest("conservada");
        Long eventoId = insertarEvento(publicacionId, actorId, "CREADA", "evento conservado");

        jdbcTemplate.update("DELETE FROM publicaciones WHERE id = ?", publicacionId);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT publicacion_id FROM publicacion_eventos WHERE id = ?", Long.class, eventoId
        )).isNull();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT actor_id FROM publicacion_eventos WHERE id = ?", Long.class, eventoId
        )).isEqualTo(actorId);
        assertThatThrownBy(() -> insertarEvento(999999L, actorId, "CREADA", null))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
            "UPDATE publicacion_eventos SET detalle = ? WHERE id = ?", "alterado", eventoId
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM publicacion_eventos WHERE id = ?", eventoId))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Inserta un evento de auditoría de publicación.
     *
     * @param publicacionId identificador de la publicación auditada, o {@code null} si corresponde
     * @param actorId identificador del usuario existente responsable del evento
     * @param tipo tipo de evento que se desea persistir
     * @param detalle detalle opcional del evento
     * @return identificador del evento insertado
     * @throws DataIntegrityViolationException si una restricción persistente rechaza la fila
     */
    private Long insertarEvento(Long publicacionId, Long actorId, String tipo, String detalle) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO publicacion_eventos (publicacion_id, actor_id, tipo, detalle) "
                + "VALUES (?, ?, ?, ?) RETURNING id",
            Long.class, publicacionId, actorId, tipo, detalle
        );
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
     * Crea una publicación rechazada con todas sus dependencias obligatorias.
     *
     * @param sufijo valor único para los datos auxiliares
     * @return identificador de la publicación creada
     */
    private Long crearPublicacionRechazadaTest(String sufijo) {
        Long vendedorId = crearUsuarioTest("vendedor-v16-" + sufijo + "@example.com");
        Long categoriaId = crearCategoriaTest("Categoría V16 " + sufijo);
        Long subcategoriaId = crearSubcategoriaTest(categoriaId, "Subcategoría V16 " + sufijo);
        return jdbcTemplate.queryForObject(
            "INSERT INTO publicaciones (usuario_id, categoria_id, subcategoria_id, precio, stock, estado, descripcion) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id",
            Long.class, vendedorId, categoriaId, subcategoriaId, 150000L, 10, "rechazada",
            "Publicación de prueba V16 " + sufijo
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
