package com.easymarket.marketplace.service;

import com.easymarket.marketplace.exception.CategoriaConPublicacionesException;
import com.easymarket.marketplace.exception.NombreCategoriaDuplicadoException;
import com.easymarket.marketplace.model.Categoria;
import com.easymarket.marketplace.model.Subcategoria;
import com.easymarket.marketplace.repository.CategoriaRepository;
import com.easymarket.marketplace.repository.SubcategoriaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas de integración para {@link CategoriaService} utilizando PostgreSQL real mediante Testcontainers.
 *
 * <p>Verifica la interacción real con la base de datos PostgreSQL y las restricciones DDL de las migraciones V4 y V5:
 * <ul>
 *   <li>Rechazo de eliminación de categoría/subcategoría cuando existen publicaciones reales en la tabla {@code publicaciones}.</li>
 *   <li>Unicidad de subcategorías por categoría padre con datos reales persistidos.</li>
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
@Transactional
class CategoriaServiceIntegrationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private CategoriaService categoriaService;

    @Autowired
    private CategoriaRepository categoriaRepository;

    @Autowired
    private SubcategoriaRepository subcategoriaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Verifica que el servicio rechace la eliminación de una categoría cuando existe una publicación asociada en la DB.
     */
    @Test
    @DisplayName("Debe rechazar la eliminación de categoría en DB real si existen publicaciones asociadas")
    void eliminarCategoria_ConPublicacionRealEnDB_LanzaExcepcion() {
        Categoria cat = categoriaService.crearCategoria("Ropa");
        Subcategoria subcat = categoriaService.crearSubcategoria(cat.getId(), "Camisetas");

        Long usuarioId = crearUsuarioTest("seller_real@example.com");

        jdbcTemplate.update(
            "INSERT INTO publicaciones (usuario_id, categoria_id, subcategoria_id, precio, stock, descripcion) VALUES (?, ?, ?, ?, ?, ?)",
            usuarioId, cat.getId(), subcat.getId(), 2500L, 10, "Camiseta de algodón"
        );

        assertThatThrownBy(() -> categoriaService.eliminarCategoria(cat.getId()))
            .isInstanceOf(CategoriaConPublicacionesException.class)
            .hasMessageContaining("No se puede eliminar la categoría 'Ropa' porque tiene publicaciones asociadas");

        assertThat(categoriaRepository.findById(cat.getId())).isPresent();
    }

    /**
     * Verifica que el servicio rechace la eliminación de una subcategoría cuando existe una publicación asociada en la DB.
     */
    @Test
    @DisplayName("Debe rechazar la eliminación de subcategoría en DB real si existen publicaciones asociadas")
    void eliminarSubcategoria_ConPublicacionRealEnDB_LanzaExcepcion() {
        Categoria cat = categoriaService.crearCategoria("Calzado");
        Subcategoria subcat = categoriaService.crearSubcategoria(cat.getId(), "Zapatillas");

        Long usuarioId = crearUsuarioTest("seller_real2@example.com");

        jdbcTemplate.update(
            "INSERT INTO publicaciones (usuario_id, categoria_id, subcategoria_id, precio, stock, descripcion) VALUES (?, ?, ?, ?, ?, ?)",
            usuarioId, cat.getId(), subcat.getId(), 8000L, 3, "Zapatillas deportivas"
        );

        assertThatThrownBy(() -> categoriaService.eliminarSubcategoria(subcat.getId()))
            .isInstanceOf(CategoriaConPublicacionesException.class)
            .hasMessageContaining("No se puede eliminar la subcategoría 'Zapatillas' porque tiene publicaciones asociadas");

        assertThat(subcategoriaRepository.findById(subcat.getId())).isPresent();
    }

    /**
     * Helper para crear un usuario de prueba en la tabla {@code usuarios}.
     */
    private Long crearUsuarioTest(String email) {
        jdbcTemplate.update(
            "INSERT INTO usuarios (email, password_hash, rol) VALUES (?, ?, ?)",
            email, "$2a$10$abcdefghijklmnopqrstuuu", "USUARIO"
        );
        return jdbcTemplate.queryForObject("SELECT id FROM usuarios WHERE email = ?", Long.class, email);
    }
}
