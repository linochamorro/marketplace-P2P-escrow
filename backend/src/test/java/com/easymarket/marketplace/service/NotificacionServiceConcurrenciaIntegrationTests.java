package com.easymarket.marketplace.service;

import com.easymarket.marketplace.model.Categoria;
import com.easymarket.marketplace.model.Publicacion;
import com.easymarket.marketplace.model.Rol;
import com.easymarket.marketplace.model.Subcategoria;
import com.easymarket.marketplace.model.Transaccion;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.CategoriaRepository;
import com.easymarket.marketplace.repository.PublicacionRepository;
import com.easymarket.marketplace.repository.SubcategoriaRepository;
import com.easymarket.marketplace.repository.TransaccionRepository;
import com.easymarket.marketplace.repository.UsuarioRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.ZonedDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies normal notification idempotency through two independent PostgreSQL transactions.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "app.jwt.secret=test-secret-key-32-chars-minimum-ok"
})
class NotificacionServiceConcurrenciaIntegrationTests {

    /** Real PostgreSQL database used to exercise the unique-index conflict path. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private NotificacionService service;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private CategoriaRepository categoriaRepository;

    @Autowired
    private SubcategoriaRepository subcategoriaRepository;

    @Autowired
    private PublicacionRepository publicacionRepository;

    @Autowired
    private TransaccionRepository transaccionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Installs the database-side overlap gate before each concurrency scenario. */
    @BeforeEach
    void instalarBarreraDeEscritura() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS test_notificacion_overlap (pid BIGINT NOT NULL)");
        jdbcTemplate.execute("CREATE OR REPLACE FUNCTION test_notificacion_overlap_trigger() RETURNS trigger "
                + "LANGUAGE plpgsql AS $$ BEGIN INSERT INTO test_notificacion_overlap(pid) VALUES (pg_backend_pid()); "
                + "PERFORM pg_sleep(2); RETURN NEW; END $$");
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_notificacion_overlap_trigger ON notificaciones");
        jdbcTemplate.execute("CREATE TRIGGER test_notificacion_overlap_trigger BEFORE INSERT ON notificaciones "
                + "FOR EACH ROW EXECUTE FUNCTION test_notificacion_overlap_trigger()");
    }

    /** Removes the temporary database-side overlap gate and its evidence rows. */
    @AfterEach
    void retirarBarreraDeEscritura() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_notificacion_overlap_trigger ON notificaciones");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS test_notificacion_overlap_trigger()");
        jdbcTemplate.execute("DROP TABLE IF EXISTS test_notificacion_overlap");
    }

    /**
     * Starts two legitimate normal emitters together and proves that both complete successfully,
     * while PostgreSQL retains exactly one transaction notification slot.
     *
     * @throws Exception when a worker cannot complete within the bounded test wait
     */
    @Test
    @DisplayName("Dos emisores normales concurrentes no fallan ni duplican el slot")
    void emisionesNormalesConcurrentes_NoFallaElPerdedorNiDuplica() throws Exception {
        String sufijo = String.valueOf(System.nanoTime());
        ZonedDateTime ahora = ZonedDateTime.now();
        Usuario vendedor = usuarioRepository.save(new Usuario("concurrent-seller-" + sufijo + "@example.com",
                "hash", Rol.USUARIO, 0L, ahora));
        Usuario comprador = usuarioRepository.save(new Usuario("concurrent-buyer-" + sufijo + "@example.com",
                "hash", Rol.USUARIO, 0L, ahora));
        Categoria categoria = categoriaRepository.save(new Categoria("concurrent-category-" + sufijo));
        Subcategoria subcategoria = subcategoriaRepository.save(new Subcategoria(categoria,
                "concurrent-subcategory-" + sufijo));
        Publicacion publicacion = new Publicacion(vendedor, categoria, subcategoria, 100L, 1, "concurrent");
        publicacion.setEstado(com.easymarket.marketplace.model.EstadoPublicacion.APROBADA);
        publicacion = publicacionRepository.saveAndFlush(publicacion);
        Transaccion transaccion = transaccionRepository.saveAndFlush(
                new Transaccion(comprador, publicacion, 100L, ahora));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch inicio = new CountDownLatch(1);
        try {
            Future<?> primero = executor.submit(() -> emitir(inicio, comprador, transaccion, "A"));
            Future<?> segundo = executor.submit(() -> emitir(inicio, comprador, transaccion, "B"));
            inicio.countDown();
            primero.get(60, TimeUnit.SECONDS);
            segundo.get(60, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(DISTINCT pid) FROM test_notificacion_overlap",
                Integer.class)).isEqualTo(2);

        Integer filas = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notificaciones "
                + "WHERE usuario_id = ? AND transaccion_id = ? AND tipo = ?", Integer.class,
                comprador.getId(), transaccion.getId(), "COMPRA_CONFIRMADA");
        assertThat(filas).isEqualTo(1);
    }

    /**
     * Starts two publication emitters and holds both repository calls until both have entered the
     * real PostgreSQL upsert, proving an actual overlapping insert race rather than only concurrent
     * task scheduling.
     *
     * @throws Exception when setup or either worker fails
     */
    @Test
    @DisplayName("Dos emisores de publicación concurrentes comparten exactamente un slot")
    void emisionesDePublicacionConcurrentes_NoFallaElPerdedorNiDuplica() throws Exception {
        String sufijo = String.valueOf(System.nanoTime());
        ZonedDateTime ahora = ZonedDateTime.now();
        Usuario vendedor = usuarioRepository.save(new Usuario("publication-seller-" + sufijo + "@example.com",
                "hash", Rol.USUARIO, 0L, ahora));
        Usuario admin = usuarioRepository.findByRol(Rol.ADMIN)
                .orElseThrow(() -> new AssertionError("El contexto debe provisionar un único ADMIN."));
        Categoria categoria = categoriaRepository.save(new Categoria("publication-category-" + sufijo));
        Subcategoria subcategoria = subcategoriaRepository.save(new Subcategoria(categoria,
                "publication-subcategory-" + sufijo));
        Publicacion publicacionNueva = new Publicacion(vendedor, categoria, subcategoria, 100L, 1, "publication-race");
        publicacionNueva.setEstado(com.easymarket.marketplace.model.EstadoPublicacion.APROBADA);
        final Publicacion publicacion = publicacionRepository.saveAndFlush(publicacionNueva);

        CountDownLatch inicio = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> primero = executor.submit(() -> emitirPublicacion(inicio, admin, publicacion));
            Future<?> segundo = executor.submit(() -> emitirPublicacion(inicio, admin, publicacion));
            inicio.countDown();
            primero.get(60, TimeUnit.SECONDS);
            segundo.get(60, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(DISTINCT pid) FROM test_notificacion_overlap",
                Integer.class)).isEqualTo(2);

        Integer filas = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notificaciones "
                + "WHERE usuario_id = ? AND publicacion_id = ? AND tipo = ?", Integer.class,
                admin.getId(), publicacion.getId(), "PUBLICACION_PENDIENTE_APROBAR");
        assertThat(filas).isEqualTo(1);
    }

    /**
     * Emits a normal transaction notification after the shared start signal.
     *
     * @param inicio latch released by the coordinator for both workers
     * @param comprador recipient of the notification
     * @param transaccion transaction associated with the slot
     * @param emisor label used to distinguish the two worker messages
     * @throws AssertionError when the worker is interrupted
     */
    private void emitir(CountDownLatch inicio, Usuario comprador, Transaccion transaccion, String emisor) {
        try {
            inicio.await();
            service.crearNotificacionUsuario(comprador, "COMPRA_CONFIRMADA", "emisor " + emisor,
                    transaccion, ZonedDateTime.now());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("La emisión concurrente fue interrumpida.", exception);
        }
    }

    /**
     * Emits an administrative publication notification after the shared start signal.
     *
     * @param inicio latch released by the coordinator for both workers
     * @param admin sole administrative recipient
     * @param publicacion pending publication associated with the slot
     * @throws AssertionError when the worker is interrupted
     */
    private void emitirPublicacion(CountDownLatch inicio, Usuario admin, Publicacion publicacion) {
        try {
            inicio.await();
            service.crearNotificacionAdmin("PUBLICACION_PENDIENTE_APROBAR", "publicación concurrente",
                    publicacion, null, ZonedDateTime.now());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("La emisión concurrente fue interrumpida.", exception);
        }
    }
}
