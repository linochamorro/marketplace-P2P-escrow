package com.easymarket.marketplace.service;

import com.easymarket.marketplace.model.Categoria;
import com.easymarket.marketplace.model.EstadoPublicacion;
import com.easymarket.marketplace.model.EstadoTransaccion;
import com.easymarket.marketplace.model.Publicacion;
import com.easymarket.marketplace.model.Rol;
import com.easymarket.marketplace.model.Subcategoria;
import com.easymarket.marketplace.model.Transaccion;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.CategoriaRepository;
import com.easymarket.marketplace.repository.SubcategoriaRepository;
import com.easymarket.marketplace.repository.TransaccionRepository;
import com.easymarket.marketplace.repository.UsuarioRepository;
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

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the 48-hour pending-shipment notification job against PostgreSQL via
 * Testcontainers.
 *
 * <p>The test deliberately has no test-level transaction. Each concurrent job call therefore
 * reaches the Spring proxy and opens an independent database transaction, so PostgreSQL
 * {@code FOR UPDATE SKIP LOCKED} and the persistent idempotency marker are exercised for real.</p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=test-secret-key-32-chars-minimum-ok"
})
class NotificarEnviadoPendienteJobIntegrationTests {

    /** Peru time zone used to construct the expired reservation fixture. */
    private static final ZoneId PERU_ZONE = ZoneId.of("America/Lima");

    /** Stable type the job must persist for the buyer. */
    private static final String TIPO = "ENVIO_PENDIENTE_48H";

    /** Literal message the job must persist for the buyer. */
    private static final String MENSAJE = "Tu compra permanece sin envío tras 48 horas. Puedes esperar o cancelarla.";

    /** PostgreSQL instance on which locking and V13 constraints are exercised. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Spring-proxied job whose independent invocations exercise database locking. */
    @Autowired
    private NotificarEnviadoPendienteJob notificarEnviadoPendienteJob;

    /** Repository used to persist buyer and seller fixtures. */
    @Autowired
    private UsuarioRepository usuarioRepository;

    /** Repository used to persist the category fixture. */
    @Autowired
    private CategoriaRepository categoriaRepository;

    /** Repository used to persist the subcategory fixture. */
    @Autowired
    private SubcategoriaRepository subcategoriaRepository;

    /** Domain service used to create an approved publication fixture. */
    @Autowired
    private PublicacionService publicacionService;

    /** Repository used to persist the eligible reserved transaction. */
    @Autowired
    private TransaccionRepository transaccionRepository;

    /** JDBC access used to assert persisted rows and immutable state directly. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Sends exactly one pending-shipment notification to the buyer of a reservation older than 48
     * hours, preserving both transaction state and publication stock under concurrent executions and
     * a later sequential execution.
     */
    @Test
    @DisplayName("Una reserva de hace 49 horas notifica una sola vez al comprador sin cambiar estado ni stock")
    void ejecutar_ReservaHace49Horas_NotificaUnaSolaVezSinCambiarEstadoNiStock() {
        Transaccion transaccion = crearTransaccionReservadaHace49Horas();
        long stockAntes = consultarStock(transaccion);

        ejecutarDosVecesEnParalelo();
        notificarEnviadoPendienteJob.ejecutar();

        assertThat(consultarNotificaciones(transaccion)).isEqualTo(1);
        assertThat(consultarTipoNotificacion(transaccion)).isEqualTo(TIPO);
        assertThat(consultarMensajeNotificacion(transaccion)).isEqualTo(MENSAJE);
        assertThat(consultarMarcadores(transaccion)).isEqualTo(1);
        assertThat(consultarEstado(transaccion)).isEqualTo("reservada");
        assertThat(consultarStock(transaccion)).isEqualTo(stockAntes);
        assertThat(consultarEventos(transaccion)).isZero();
    }

    /**
     * Verifies that a reservation that has not completed 48 hours remains ineligible for the
     * one-time warning and receives neither a notification nor a V13 marker.
     */
    @Test
    @DisplayName("Una reserva de menos de 48 horas no genera notificación ni marcador")
    void ejecutar_ReservaDeMenosDe48Horas_NoNotificaNiCreaMarcador() {
        Transaccion transaccion = crearTransaccion("menos-48h", ZonedDateTime.now(PERU_ZONE).minusHours(47).minusMinutes(59));

        notificarEnviadoPendienteJob.ejecutar();

        assertThat(consultarNotificaciones(transaccion)).isZero();
        assertThat(consultarMarcadores(transaccion)).isZero();
        assertThat(consultarEstado(transaccion)).isEqualTo("reservada");
    }

    /**
     * Verifies that an expired transaction in a state other than {@code reservada} remains
     * ineligible and receives neither a notification nor a V13 marker.
     */
    @Test
    @DisplayName("Una transacción vencida que no está reservada no genera notificación ni marcador")
    void ejecutar_TransaccionVencidaNoReservada_NoNotificaNiCreaMarcador() {
        Transaccion transaccion = crearTransaccion("enviado-vencido", ZonedDateTime.now(PERU_ZONE).minusHours(49));
        transaccion.setEstado(EstadoTransaccion.ENVIADO);
        transaccion.setFechaEnviado(ZonedDateTime.now(PERU_ZONE).minusHours(48));
        transaccionRepository.saveAndFlush(transaccion);

        notificarEnviadoPendienteJob.ejecutar();

        assertThat(consultarNotificaciones(transaccion)).isZero();
        assertThat(consultarMarcadores(transaccion)).isZero();
        assertThat(consultarEstado(transaccion)).isEqualTo("enviado");
    }

    /**
     * Creates all persisted data required for a reserved transaction whose reservation timestamp
     * is deliberately 49 hours old in Peru time.
     *
     * @return persisted reserved transaction eligible for the one-time notification
     */
    private Transaccion crearTransaccionReservadaHace49Horas() {
        return crearTransaccion("49h", ZonedDateTime.now(PERU_ZONE).minusHours(49));
    }

    /**
     * Creates persisted users, catalog data and a reserved transaction at the supplied reservation
     * timestamp.
     *
     * @param etiqueta unique fixture label used to keep database rows distinguishable
     * @param fechaReservada timestamp to persist as the reservation instant
     * @return persisted reserved transaction for the requested fixture
     */
    private Transaccion crearTransaccion(String etiqueta, ZonedDateTime fechaReservada) {
        String sufijo = etiqueta + "-" + System.nanoTime();
        ZonedDateTime ahoraPeru = ZonedDateTime.now(PERU_ZONE);
        Usuario vendedor = usuarioRepository.save(new Usuario("vendedor-aviso-" + sufijo + "@example.com", "hash", Rol.USUARIO, 0L, ahoraPeru));
        Usuario comprador = usuarioRepository.save(new Usuario("comprador-aviso-" + sufijo + "@example.com", "hash", Rol.USUARIO, 0L, ahoraPeru));
        Categoria categoria = categoriaRepository.save(new Categoria("Categoría aviso " + sufijo));
        Subcategoria subcategoria = subcategoriaRepository.save(new Subcategoria(categoria, "Subcategoría aviso " + sufijo));
        Publicacion publicacion = publicacionService.crearPublicacion(vendedor.getId(), categoria.getId(), subcategoria.getId(), 12_345L, 1, "Publicación para aviso pendiente");
        publicacionService.cambiarEstado(publicacion.getId(), EstadoPublicacion.APROBADA, null);
        return transaccionRepository.saveAndFlush(new Transaccion(comprador, publicacion, 12_345L, fechaReservada));
    }

    /** Starts two job invocations at the same time and waits for both to complete. */
    private void ejecutarDosVecesEnParalelo() {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch arranque = new CountDownLatch(1);
        try {
            Future<?> ejecucionA = executor.submit(() -> { esperarArranque(arranque); notificarEnviadoPendienteJob.ejecutar(); return null; });
            Future<?> ejecucionB = executor.submit(() -> { esperarArranque(arranque); notificarEnviadoPendienteJob.ejecutar(); return null; });
            arranque.countDown();
            esperarEjecucion(ejecucionA);
            esperarEjecucion(ejecucionB);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Waits until a worker may invoke the job.
     *
     * @param arranque latch coordinating concurrent workers
     */
    private void esperarArranque(CountDownLatch arranque) {
        try { arranque.await(); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError("La sincronización de ejecuciones concurrentes fue interrumpida.", exception); }
    }

    /**
     * Waits for one concurrent job invocation.
     *
     * @param ejecucion future representing one invocation
     */
    private void esperarEjecucion(Future<?> ejecucion) {
        try { ejecucion.get(60, TimeUnit.SECONDS); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError("La ejecución concurrente fue interrumpida.", exception); } catch (ExecutionException exception) { throw new AssertionError("La ejecución concurrente del job falló.", exception.getCause()); } catch (java.util.concurrent.TimeoutException exception) { throw new AssertionError("La ejecución concurrente del job superó 60 segundos.", exception); }
    }

    /**
     * Counts notification rows created for this fixture's buyer and the exact warning type and
     * message. This intentionally reads {@code notificaciones} directly rather than the V13
     * marker table, so it independently detects duplicate notification projections.
     *
     * @param transaccion transaction whose marker is inspected
     * @return number of matching notification rows
     */
    private Integer consultarNotificaciones(Transaccion transaccion) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notificaciones WHERE usuario_id = ? AND tipo = ? AND mensaje = ?", Integer.class, transaccion.getComprador().getId(), TIPO, MENSAJE);
    }

    /**
     * Reads the notification type linked to a transaction.
     *
     * @param transaccion transaction whose notification is inspected
     * @return persisted notification type
     */
    private String consultarTipoNotificacion(Transaccion transaccion) {
        return jdbcTemplate.queryForObject("SELECT n.tipo FROM notificaciones n JOIN avisos_envio_pendiente a ON a.notificacion_id = n.id WHERE a.transaccion_id = ?", String.class, transaccion.getId());
    }

    /**
     * Reads the notification message linked to a transaction.
     *
     * @param transaccion transaction whose notification is inspected
     * @return persisted notification message
     */
    private String consultarMensajeNotificacion(Transaccion transaccion) {
        return jdbcTemplate.queryForObject("SELECT n.mensaje FROM notificaciones n JOIN avisos_envio_pendiente a ON a.notificacion_id = n.id WHERE a.transaccion_id = ?", String.class, transaccion.getId());
    }

    /**
     * Counts persistent idempotency markers for a transaction.
     *
     * @param transaccion transaction whose marker is counted
     * @return number of markers for the transaction
     */
    private Integer consultarMarcadores(Transaccion transaccion) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM avisos_envio_pendiente WHERE transaccion_id = ?", Integer.class, transaccion.getId());
    }

    /**
     * Reads the transaction state directly from PostgreSQL.
     *
     * @param transaccion transaction whose state is inspected
     * @return persisted transaction state
     */
    private String consultarEstado(Transaccion transaccion) {
        return jdbcTemplate.queryForObject("SELECT estado FROM transacciones WHERE id = ?", String.class, transaccion.getId());
    }

    /**
     * Counts canonical transaction events associated with the transaction.
     *
     * @param transaccion transaction whose canonical audit events are counted
     * @return number of canonical transaction-event rows
     */
    private Integer consultarEventos(Transaccion transaccion) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transaccion_eventos WHERE transaccion_id = ?",
            Integer.class, transaccion.getId());
    }

    /**
     * Reads the publication stock directly from PostgreSQL.
     *
     * @param transaccion transaction whose publication stock is inspected
     * @return persisted publication stock
     */
    private long consultarStock(Transaccion transaccion) {
        Long stock = jdbcTemplate.queryForObject("SELECT stock FROM publicaciones WHERE id = ?", Long.class, transaccion.getPublicacion().getId());
        return stock == null ? -1L : stock;
    }
}
