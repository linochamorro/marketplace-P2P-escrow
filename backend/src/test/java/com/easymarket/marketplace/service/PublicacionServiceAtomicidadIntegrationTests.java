package com.easymarket.marketplace.service;

import com.easymarket.marketplace.model.Categoria;
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
 * Pruebas de integración PostgreSQL para la atomicidad de los efectos de crear una publicación.
 *
 * <p>Un spy provoca un fallo específico en cada repositorio de escritura mientras el servicio se
 * ejecuta mediante el proxy transaccional real. Después se comprueba en PostgreSQL que el rollback
 * no dejó filas de publicación, evento ni notificación. Los tests unitarios cubren el contenido de
 * cada objeto y esta clase cubre exclusivamente el commit o rollback real.</p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=test-secret-key-32-chars-minimum-ok"
})
class PublicacionServiceAtomicidadIntegrationTests {

    /** Instancia PostgreSQL real para verificar los efectos de la transacción. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Servicio sujeto a la transacción de dominio que se prueba. */
    @Autowired
    private PublicacionService publicacionService;

    /** Repositorio real para crear los datos independientes del escenario. */
    @Autowired
    private UsuarioRepository usuarioRepository;

    /** Repositorio real de categorías para la preparación de cada escenario. */
    @Autowired
    private CategoriaRepository categoriaRepository;

    /** Repositorio real de subcategorías para la preparación de cada escenario. */
    @Autowired
    private SubcategoriaRepository subcategoriaRepository;

    /** Spy del primer efecto persistente cuyo fallo activa el rollback. */
    @MockitoSpyBean
    private PublicacionRepository publicacionRepository;

    /** Spy del registro append-only cuyo fallo activa el rollback. */
    @MockitoSpyBean
    private PublicacionEventoRepository publicacionEventoRepository;

    /** Spy del aviso in-app cuyo fallo activa el rollback. */
    @MockitoSpyBean
    private NotificacionRepository notificacionRepository;

    /** Cliente SQL para contar las filas efectivamente comprometidas. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Restaura los spies para que un fallo inyectado no afecte otro caso. */
    @AfterEach
    void restaurarSpies() {
        reset(publicacionRepository, publicacionEventoRepository, notificacionRepository);
    }

    /** Verifica rollback real cuando falla el guardado de la publicación. */
    @Test
    @DisplayName("Debe revertir publicación, evento y aviso si falla guardar la publicación")
    void crearPublicacion_FallaGuardarPublicacion_RevierteLosTresEfectos() {
        DatosCreacion datos = prepararDatos("falla-publicacion");
        Conteos conteosAntes = contarEfectos();
        doThrow(new DataIntegrityViolationException("fallo publicación"))
            .when(publicacionRepository).save(any(Publicacion.class));

        assertThatThrownBy(() -> crear(datos))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("fallo publicación");

        assertThat(contarEfectos()).isEqualTo(conteosAntes);
    }

    /** Verifica rollback real cuando falla el guardado del evento append-only. */
    @Test
    @DisplayName("Debe revertir publicación, evento y aviso si falla guardar el evento")
    void crearPublicacion_FallaGuardarEvento_RevierteLosTresEfectos() {
        DatosCreacion datos = prepararDatos("falla-evento");
        Conteos conteosAntes = contarEfectos();
        doThrow(new DataIntegrityViolationException("fallo evento"))
            .when(publicacionEventoRepository).save(any(PublicacionEvento.class));

        assertThatThrownBy(() -> crear(datos))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("fallo evento");

        assertThat(contarEfectos()).isEqualTo(conteosAntes);
    }

    /** Verifica rollback real cuando falla el guardado de la notificación in-app. */
    @Test
    @DisplayName("Debe revertir publicación, evento y aviso si falla guardar la notificación")
    void crearPublicacion_FallaGuardarNotificacion_RevierteLosTresEfectos() {
        DatosCreacion datos = prepararDatos("falla-notificacion");
        Conteos conteosAntes = contarEfectos();
        doThrow(new DataIntegrityViolationException("fallo notificación"))
            .when(notificacionRepository).upsertPublicacion(any(Long.class), any(Long.class), any(String.class),
                any(String.class), any(java.time.ZonedDateTime.class));

        assertThatThrownBy(() -> crear(datos))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("fallo notificación");

        assertThat(contarEfectos()).isEqualTo(conteosAntes);
    }

    /**
     * Ejecuta la creación de la publicación con los IDs preparados.
     *
     * @param datos relaciones persistidas necesarias para la creación válida
     * @return publicación creada si ningún spy provoca un fallo
     */
    private Publicacion crear(DatosCreacion datos) {
        return publicacionService.crearPublicacion(datos.vendedorId(), datos.categoriaId(), datos.subcategoriaId(),
            150000L, 5, "Publicación atómica " + datos.sufijo(), null);
    }

    /**
     * Crea vendedor, ADMIN único del escenario, categoría y subcategoría fuera de la transacción
     * que debe revertirse.
     *
     * @param sufijo valor único para los datos del escenario
     * @return IDs y sufijo requeridos por la creación bajo prueba
     */
    private DatosCreacion prepararDatos(String sufijo) {
        Usuario vendedor = usuarioRepository.save(new Usuario("vendedor-" + sufijo + "@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now()));
        if (usuarioRepository.findByRol(Rol.ADMIN).isEmpty()) {
            usuarioRepository.save(new Usuario("admin-" + sufijo + "@example.com", "hash", Rol.ADMIN, 0L, ZonedDateTime.now()));
        }
        Categoria categoria = categoriaRepository.save(new Categoria("Categoría " + sufijo));
        Subcategoria subcategoria = subcategoriaRepository.save(new Subcategoria(categoria, "Subcategoría " + sufijo));
        return new DatosCreacion(vendedor.getId(), categoria.getId(), subcategoria.getId(), sufijo);
    }

    /**
     * Cuenta los tres efectos persistentes de la transacción de dominio.
     *
     * @return conteos actuales de publicaciones, eventos y notificaciones
     */
    private Conteos contarEfectos() {
        return new Conteos(contar("publicaciones"), contar("publicacion_eventos"), contar("notificaciones"));
    }

    /**
     * Cuenta todas las filas de una tabla controlada definida por las migraciones del proyecto.
     *
     * @param tabla nombre constante de una tabla interna, nunca procedente de entrada externa
     * @return número actual de filas
     */
    private long contar(String tabla) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tabla, Long.class);
    }

    /**
     * IDs de relaciones ya persistidas para invocar una creación válida en un escenario de fallo.
     *
     * @param vendedorId identificador del usuario vendedor
     * @param categoriaId identificador de la categoría
     * @param subcategoriaId identificador de la subcategoría de la categoría
     * @param sufijo identificador textual único del escenario
     */
    private record DatosCreacion(Long vendedorId, Long categoriaId, Long subcategoriaId, String sufijo) {
    }

    /**
     * Conteos de los tres efectos que deben cambiar todos juntos o permanecer sin cambios.
     *
     * @param publicaciones número de publicaciones persistidas
     * @param eventos número de eventos append-only persistidos
     * @param notificaciones número de avisos in-app persistidos
     */
    private record Conteos(long publicaciones, long eventos, long notificaciones) {
    }
}
