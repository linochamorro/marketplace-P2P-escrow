package com.easymarket.marketplace.service;

import com.easymarket.marketplace.model.Categoria;
import com.easymarket.marketplace.model.Notificacion;
import com.easymarket.marketplace.model.Publicacion;
import com.easymarket.marketplace.model.PublicacionEvento;
import com.easymarket.marketplace.model.Rol;
import com.easymarket.marketplace.model.Subcategoria;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.CategoriaRepository;
import com.easymarket.marketplace.repository.NotificacionRepository;
import com.easymarket.marketplace.repository.PublicacionEventoRepository;
import com.easymarket.marketplace.repository.PublicacionRepository;
import com.easymarket.marketplace.repository.SubcategoriaRepository;
import com.easymarket.marketplace.repository.UsuarioRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * Pruebas de integración PostgreSQL del rollback de publicación, evento y aviso de Story 1.
 *
 * <p>Cada caso hace fallar uno de los tres repositorios de escritura mediante un spy y verifica
 * por conteos SQL que ninguno de los tres efectos queda comprometido.</p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true", "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate", "app.jwt.secret=test-secret-key-32-chars-minimum-ok"
})
class PublicacionCreacionAuditoriaAtomicidadIntegrationTests {

    /** Contenedor PostgreSQL real usado para confirmar el rollback. */
    @Container @ServiceConnection static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    /** Servicio transaccional bajo prueba. */
    @Autowired private PublicacionService publicacionService;
    /** Repositorios reales de los datos de preparación. */
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private CategoriaRepository categoriaRepository;
    @Autowired private SubcategoriaRepository subcategoriaRepository;
    /** Spies de los tres puntos de escritura de la operación. */
    @MockitoSpyBean private PublicacionRepository publicacionRepository;
    @MockitoSpyBean private PublicacionEventoRepository publicacionEventoRepository;
    @MockitoSpyBean private NotificacionRepository notificacionRepository;
    /** Cliente SQL para contar efectos ya comprometidos. */
    @Autowired private JdbcTemplate jdbcTemplate;

    /** Restaura los spies tras cada fallo inyectado. */
    @AfterEach
    void restaurarSpies() {
        reset(publicacionRepository, publicacionEventoRepository, notificacionRepository);
    }

    /** Verifica que un fallo al guardar la publicación no deja ninguno de los tres efectos. */
    @Test
    @DisplayName("Fallo de save publicación revierte publicación evento y aviso")
    void crearPublicacion_FallaSavePublicacion_RevierteTresEfectos() {
        verificarRollback("publicacion", () -> doThrow(new DataIntegrityViolationException("fallo publicación"))
            .when(publicacionRepository).save(any(Publicacion.class)));
    }

    /** Verifica que un fallo al guardar el evento no deja ninguno de los tres efectos. */
    @Test
    @DisplayName("Fallo de save evento revierte publicación evento y aviso")
    void crearPublicacion_FallaSaveEvento_RevierteTresEfectos() {
        verificarRollback("evento", () -> doThrow(new DataIntegrityViolationException("fallo evento"))
            .when(publicacionEventoRepository).save(any(PublicacionEvento.class)));
    }

    /** Verifica que un fallo al guardar el aviso no deja ninguno de los tres efectos. */
    @Test
    @DisplayName("Fallo de save aviso revierte publicación evento y aviso")
    void crearPublicacion_FallaSaveAviso_RevierteTresEfectos() {
        verificarRollback("aviso", () -> doThrow(new DataIntegrityViolationException("fallo aviso"))
            .when(notificacionRepository).save(any(Notificacion.class)));
    }

    /**
     * Inyecta un fallo de guardado y comprueba el rollback contra PostgreSQL.
     *
     * @param sufijo identificador único de los datos de prueba
     * @param prepararFallo configuración del spy que debe fallar
     */
    private void verificarRollback(String sufijo, Runnable prepararFallo) {
        Datos datos = prepararDatos(sufijo);
        Conteos antes = conteos();
        prepararFallo.run();
        assertThatThrownBy(() -> publicacionService.crearPublicacion(datos.vendedorId(), datos.categoriaId(),
            datos.subcategoriaId(), 150000L, 2, "Publicación " + sufijo))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(conteos()).isEqualTo(antes);
    }

    /**
     * Persiste relaciones válidas fuera de la transacción cuya reversión se verifica.
     *
     * @param sufijo valor distintivo del escenario
     * @return IDs de vendedor, categoría y subcategoría persistidos
     */
    private Datos prepararDatos(String sufijo) {
        Usuario vendedor = usuarioRepository.save(new Usuario("vendedor-" + sufijo + "@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now()));
        if (usuarioRepository.findByRol(Rol.ADMIN).isEmpty()) {
            usuarioRepository.save(new Usuario("admin-" + sufijo + "@example.com", "hash", Rol.ADMIN, 0L, ZonedDateTime.now()));
        }
        Categoria categoria = categoriaRepository.save(new Categoria("Categoría " + sufijo));
        Subcategoria subcategoria = subcategoriaRepository.save(new Subcategoria(categoria, "Subcategoría " + sufijo));
        return new Datos(vendedor.getId(), categoria.getId(), subcategoria.getId());
    }

    /** @return conteos de filas comprometidas de los tres efectos de la operación */
    private Conteos conteos() {
        return new Conteos(contar("publicaciones"), contar("publicacion_eventos"), contar("notificaciones"));
    }

    /**
     * Cuenta filas de una tabla interna constante.
     *
     * @param tabla nombre constante de tabla, nunca entrada externa
     * @return cantidad de filas actual
     */
    private long contar(String tabla) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tabla, Long.class);
    }

    /**
     * Relaciones persistidas requeridas para la invocación válida del servicio.
     *
     * @param vendedorId ID del vendedor
     * @param categoriaId ID de la categoría
     * @param subcategoriaId ID de la subcategoría
     */
    private record Datos(Long vendedorId, Long categoriaId, Long subcategoriaId) { }

    /**
     * Conteos de los efectos que deben comprometerse o revertirse juntos.
     *
     * @param publicaciones conteo de publicaciones
     * @param eventos conteo de eventos
     * @param avisos conteo de notificaciones
     */
    private record Conteos(long publicaciones, long eventos, long avisos) { }
}
