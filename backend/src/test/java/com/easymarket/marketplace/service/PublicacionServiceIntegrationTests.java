package com.easymarket.marketplace.service;

import com.easymarket.marketplace.model.Categoria;
import com.easymarket.marketplace.model.EstadoPublicacion;
import com.easymarket.marketplace.model.Publicacion;
import com.easymarket.marketplace.model.Rol;
import com.easymarket.marketplace.model.Subcategoria;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.CategoriaRepository;
import com.easymarket.marketplace.repository.SubcategoriaRepository;
import com.easymarket.marketplace.repository.UsuarioRepository;
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

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas de integración para {@link PublicacionService} utilizando PostgreSQL real mediante Testcontainers.
 *
 * <p>Verifica la persistencia real en la tabla {@code publicaciones} de las migraciones V4 y V5:
 * <ul>
 *   <li>Persistencia y recuperación de una publicación con sus relaciones FK reales.</li>
 *   <li>Verificación de estado inicial por defecto 'pendiente_revisión' en base de datos.</li>
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
class PublicacionServiceIntegrationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private PublicacionService publicacionService;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private CategoriaRepository categoriaRepository;

    @Autowired
    private SubcategoriaRepository subcategoriaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Verifica la persistencia real de una publicación válida en la base de datos PostgreSQL.
     */
    @Test
    @DisplayName("Debe guardar la publicación en DB real con estado 'pendiente_revisión' y FKs correctas")
    void crearPublicacion_EnDBReal_PersisteCorrectamenteConEstadoPendienteRevision() {
        Usuario usuario = usuarioRepository.save(new Usuario("seller_pub@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now()));
        Categoria cat = categoriaRepository.save(new Categoria("Tecnología"));
        Subcategoria subcat = subcategoriaRepository.save(new Subcategoria(cat, "Smartphones"));

        Publicacion creada = publicacionService.crearPublicacion(
            usuario.getId(), cat.getId(), subcat.getId(), 299900L, 10, "Smartphone 5G 128GB"
        );

        assertThat(creada.getId()).isNotNull();
        assertThat(creada.getEstado()).isEqualTo(EstadoPublicacion.PENDIENTE_REVISION);

        String estadoDB = jdbcTemplate.queryForObject(
            "SELECT estado FROM publicaciones WHERE id = ?", String.class, creada.getId()
        );
        assertThat(estadoDB).isEqualTo("pendiente_revisión");
    }
}
