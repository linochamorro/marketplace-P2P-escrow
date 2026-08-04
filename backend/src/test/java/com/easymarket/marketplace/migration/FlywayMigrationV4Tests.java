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
 * Test de migración Flyway para la creación de las tablas {@code categorias} y {@code subcategorias} (V4).
 *
 * <p>Verifica que la migración V4 crea las tablas, índices, claves foráneas y constraints de unicidad
 * por nivel jerárquico correctamente sobre PostgreSQL real mediante Testcontainers (Story 4, plan.md):
 * <ul>
 *   <li>Constraint único en {@code categorias.nombre}: rechaza nombres duplicados en categorías raíz.</li>
 *   <li>Constraint único compuesto en {@code subcategorias(categoria_id, nombre)}: rechaza subcategorías duplicadas dentro de la misma categoría padre.</li>
 *   <li>Permite subcategorías con el mismo nombre si pertenecen a categorías padre distintas.</li>
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
class FlywayMigrationV4Tests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Verifica que la tabla {@code categorias} rechaza nombres duplicados a nivel de categoría raíz.
     */
    @Test
    @DisplayName("Categorías raíz deben rechazar nombres duplicados")
    void testCategoriasNombreUniqueConstraint() {
        jdbcTemplate.update("INSERT INTO categorias (nombre) VALUES (?)", "Electrónica");

        assertThatThrownBy(() ->
            jdbcTemplate.update("INSERT INTO categorias (nombre) VALUES (?)", "Electrónica")
        ).isInstanceOf(Exception.class);
    }

    /**
     * Verifica que {@code subcategorias} rechaza nombres duplicados dentro de la misma categoría padre,
     * pero permite el mismo nombre en categorías padre distintas.
     */
    @Test
    @DisplayName("Subcategorías deben rechazar duplicados en la misma categoría pero permitir en distintas categorías")
    void testSubcategoriasNombreUniquePerCategoriaConstraint() {
        jdbcTemplate.update("INSERT INTO categorias (nombre) VALUES (?)", "Tecnología");
        Long techId = jdbcTemplate.queryForObject("SELECT id FROM categorias WHERE nombre = 'Tecnología'", Long.class);

        jdbcTemplate.update("INSERT INTO categorias (nombre) VALUES (?)", "Hogar");
        Long hogarId = jdbcTemplate.queryForObject("SELECT id FROM categorias WHERE nombre = 'Hogar'", Long.class);

        jdbcTemplate.update("INSERT INTO subcategorias (categoria_id, nombre) VALUES (?, ?)", techId, "Accesorios");

        // Rechaza duplicado en la misma categoría padre
        assertThatThrownBy(() ->
            jdbcTemplate.update("INSERT INTO subcategorias (categoria_id, nombre) VALUES (?, ?)", techId, "Accesorios")
        ).isInstanceOf(Exception.class);

        // Permite el mismo nombre "Accesorios" en una categoría padre distinta ("Hogar")
        jdbcTemplate.update("INSERT INTO subcategorias (categoria_id, nombre) VALUES (?, ?)", hogarId, "Accesorios");

        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM subcategorias WHERE nombre = 'Accesorios'", Integer.class
        );
        assertThat(count).isEqualTo(2);
    }
}
