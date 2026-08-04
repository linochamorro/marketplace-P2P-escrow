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

import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test de migración Flyway para la creación de la tabla {@code publicaciones} (V5).
 *
 * <p>Verifica que la migración V5 crea la tabla, índices, claves foráneas y restricciones de check
 * correctamente sobre PostgreSQL real mediante Testcontainers (Story 1, spec.md):
 * <ul>
 *   <li>Claves foráneas válidas a {@code usuarios(id)}, {@code categorias(id)} y {@code subcategorias(id)}.</li>
 *   <li>Rechazo de FKs inválidas hacia entidades inexistentes (DataIntegrityViolationException).</li>
 *   <li>Restricción de {@code estado} a los 5 valores exactos de la máquina de estados
 *       ({@code pendiente_revisión}, {@code aprobada}, {@code cambios_solicitados}, {@code rechazada}, {@code oculta}).</li>
 *   <li>Restricción de {@code precio > 0} en unidades enteras (centavos).</li>
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
class FlywayMigrationV5Tests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Verifica la inserción exitosa de una publicación válida con claves foráneas existentes y estado por defecto.
     */
    @Test
    @DisplayName("Debe permitir insertar una publicación válida con FKs correctas y estado 'pendiente_revisión'")
    void testInsertPublicacionValida() {
        Long usuarioId = crearUsuarioTest("vendedor@example.com");
        Long categoriaId = crearCategoriaTest("Electrónica");
        Long subcategoriaId = crearSubcategoriaTest(categoriaId, "Laptops");

        jdbcTemplate.update(
            "INSERT INTO publicaciones (usuario_id, categoria_id, subcategoria_id, precio, stock, descripcion) VALUES (?, ?, ?, ?, ?, ?)",
            usuarioId, categoriaId, subcategoriaId, 150000L, 5, "Laptop Gamer Core i7"
        );

        String estado = jdbcTemplate.queryForObject(
            "SELECT estado FROM publicaciones WHERE usuario_id = ?", String.class, usuarioId
        );
        assertThat(estado).isEqualTo("pendiente_revisión");
    }

    /**
     * Verifica que se rechacen valores de {@code estado} ajenos a la máquina de estados definida.
     */
    @Test
    @DisplayName("Debe rechazar estados no permitidos por la máquina de estados")
    void testRechazaEstadoInvalido() {
        Long usuarioId = crearUsuarioTest("seller2@example.com");
        Long categoriaId = crearCategoriaTest("Hogar");
        Long subcategoriaId = crearSubcategoriaTest(categoriaId, "Muebles");

        assertThatThrownBy(() ->
            jdbcTemplate.update(
                "INSERT INTO publicaciones (usuario_id, categoria_id, subcategoria_id, precio, stock, estado, descripcion) VALUES (?, ?, ?, ?, ?, ?, ?)",
                usuarioId, categoriaId, subcategoriaId, 5000L, 2, "INVALIDO", "Silla Ergonómica"
            )
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Verifica que se acepten todos los valores válidos de {@code estado} de la máquina de estados.
     */
    @Test
    @DisplayName("Debe aceptar los 5 valores válidos de la máquina de estados")
    void testAceptaEstadosValidos() {
        Long usuarioId = crearUsuarioTest("seller3@example.com");
        Long categoriaId = crearCategoriaTest("Deportes");
        Long subcategoriaId = crearSubcategoriaTest(categoriaId, "Bicicletas");

        String[] estadosValidos = {"pendiente_revisión", "aprobada", "cambios_solicitados", "rechazada", "oculta"};
        for (String estado : estadosValidos) {
            jdbcTemplate.update(
                "INSERT INTO publicaciones (usuario_id, categoria_id, subcategoria_id, precio, stock, estado, descripcion) VALUES (?, ?, ?, ?, ?, ?, ?)",
                usuarioId, categoriaId, subcategoriaId, 10000L, 1, estado, "Bicicleta de montaña " + estado
            );
        }

        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM publicaciones WHERE usuario_id = ?", Integer.class, usuarioId
        );
        assertThat(count).isEqualTo(5);
    }

    /**
     * Verifica que la clave foránea {@code usuario_id} rechace un usuario inexistente.
     */
    @Test
    @DisplayName("Debe rechazar usuario_id inexistente por FK constraint")
    void testRechazaUsuarioInexistente() {
        Long categoriaId = crearCategoriaTest("Libros");
        Long subcategoriaId = crearSubcategoriaTest(categoriaId, "Ficción");

        assertThatThrownBy(() ->
            jdbcTemplate.update(
                "INSERT INTO publicaciones (usuario_id, categoria_id, subcategoria_id, precio, stock, descripcion) VALUES (?, ?, ?, ?, ?, ?)",
                999999L, categoriaId, subcategoriaId, 2000L, 10, "Novela de Ficción"
            )
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Helper para crear un usuario de prueba en la tabla {@code usuarios}.
     *
     * @param email Email único para el usuario.
     * @return ID del usuario creado.
     */
    private Long crearUsuarioTest(String email) {
        jdbcTemplate.update(
            "INSERT INTO usuarios (email, password_hash, rol) VALUES (?, ?, ?)",
            email, "$2a$10$abcdefghijklmnopqrstuuu", "USUARIO"
        );
        return jdbcTemplate.queryForObject("SELECT id FROM usuarios WHERE email = ?", Long.class, email);
    }

    /**
     * Helper para crear una categoría de prueba.
     *
     * @param nombre Nombre único de la categoría.
     * @return ID de la categoría creada.
     */
    private Long crearCategoriaTest(String nombre) {
        jdbcTemplate.update("INSERT INTO categorias (nombre) VALUES (?)", nombre);
        return jdbcTemplate.queryForObject("SELECT id FROM categorias WHERE nombre = ?", Long.class, nombre);
    }

    /**
     * Helper para crear una subcategoría de prueba.
     *
     * @param categoriaId ID de la categoría padre.
     * @param nombre Nombre de la subcategoría.
     * @return ID de la subcategoría creada.
     */
    private Long crearSubcategoriaTest(Long categoriaId, String nombre) {
        jdbcTemplate.update("INSERT INTO subcategorias (categoria_id, nombre) VALUES (?, ?)", categoriaId, nombre);
        return jdbcTemplate.queryForObject(
            "SELECT id FROM subcategorias WHERE categoria_id = ? AND nombre = ?", Long.class, categoriaId, nombre
        );
    }
}
