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
 * Integration tests for the daily open-transaction notification job against PostgreSQL through
 * Testcontainers.
 *
 * <p>The tests do not use a test-level transaction so concurrent calls enter separate Spring
 * transactions and exercise PostgreSQL {@code FOR UPDATE SKIP LOCKED} on real connections.</p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=test-secret-key-32-chars-minimum-ok"
})
class NotificacionDiariaTransaccionAbiertaJobIntegrationTests {

    /** Peru business time zone mandated for the 24-hour calculation. */
    private static final ZoneId PERU_ZONE = ZoneId.of("America/Lima");

    /** Buyer notification type mandated by the Story 7b contract. */
    private static final String TIPO_COMPRADOR = "COMPRA_PENDIENTE_DIARIA";

    /** Seller notification type mandated by the Story 7b contract. */
    private static final String TIPO_VENDEDOR = "VENTA_POR_ENTREGAR_DIARIA";

    /** Literal buyer notification message mandated by the Story 7b contract. */
    private static final String MENSAJE_COMPRADOR = "COMPRAS PENDIENTES";

    /** Literal seller notification message mandated by the Story 7b contract. */
    private static final String MENSAJE_VENDEDOR = "VENTAS POR ENTREGAR";

    /** PostgreSQL instance on which V14 and row locking are exercised. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Spring-proxied job under test. */
    @Autowired
    private NotificacionDiariaTransaccionAbiertaJob notificacionDiariaTransaccionAbiertaJob;


    /** Repository persisting buyer and seller fixtures. */
    @Autowired
    private UsuarioRepository usuarioRepository;

    /** Repository persisting category fixtures. */
    @Autowired
    private CategoriaRepository categoriaRepository;

    /** Repository persisting subcategory fixtures. */
    @Autowired
    private SubcategoriaRepository subcategoriaRepository;

    /** Domain service creating approved publication fixtures. */
    @Autowired
    private PublicacionService publicacionService;

    /** Repository persisting transaction fixtures. */
    @Autowired
    private TransaccionRepository transaccionRepository;

    /** JDBC client used for exact persisted-row assertions. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Creates a new daily notification for both parties after more than 24 hours while retaining
     * the transaction state, publication stock, and canonical transaction-event history.
     */
    @Test
    @DisplayName("Una reserva con avisos diarios de hace más de 24 horas notifica a ambas partes sin cambiar efectos de negocio")
    void ejecutar_ReservadaConAvisosDeMasDe24Horas_NotificaAmbasPartesSinCambiarEfectosDeNegocio() {
        Transaccion transaccion = crearTransaccion("reservada-vencida", EstadoTransaccion.RESERVADA);
        insertarAvisosDiarios(transaccion, ZonedDateTime.now(PERU_ZONE).minusHours(25));
        long stockAntes = consultarStock(transaccion);

        notificacionDiariaTransaccionAbiertaJob.ejecutar();

        assertThat(contarAvisosComprador(transaccion)).isEqualTo(2);
        assertThat(contarAvisosVendedor(transaccion)).isEqualTo(2);
        assertThat(consultarEstado(transaccion)).isEqualTo("reservada");
        assertThat(consultarStock(transaccion)).isEqualTo(stockAntes);
        assertThat(contarEventos(transaccion)).isZero();
    }

    /**
     * Verifies that the absence of a previous notice does not bypass the first 24-hour wait from
     * entering the current reserved state.
     */
    @Test
    @DisplayName("Una primera reserva de menos de 24 horas sin avisos no notifica")
    void ejecutar_PrimeraReservaDeMenosDe24HorasSinAvisos_NoNotifica() {
        Transaccion transaccion = crearTransaccion("primera-reserva-reciente", EstadoTransaccion.RESERVADA,
            ZonedDateTime.now(PERU_ZONE).minusHours(23));
        long stockAntes = consultarStock(transaccion);

        notificacionDiariaTransaccionAbiertaJob.ejecutar();

        assertThat(contarAvisosComprador(transaccion)).isZero();
        assertThat(contarAvisosVendedor(transaccion)).isZero();
        assertThat(consultarEstado(transaccion)).isEqualTo("reservada");
        assertThat(consultarStock(transaccion)).isEqualTo(stockAntes);
        assertThat(contarEventos(transaccion)).isZero();
    }

    /**
     * Verifies that each participant receives exactly one first notice after more than 24 hours in
     * the current reserved state, without any pre-existing daily notice.
     */
    @Test
    @DisplayName("Una primera reserva de más de 24 horas sin avisos notifica exactamente una vez a cada parte")
    void ejecutar_PrimeraReservaDeMasDe24HorasSinAvisos_NotificaExactamenteUnaVezACadaParte() {
        Transaccion transaccion = crearTransaccion("primera-reserva-vencida", EstadoTransaccion.RESERVADA,
            ZonedDateTime.now(PERU_ZONE).minusHours(25));
        long stockAntes = consultarStock(transaccion);

        notificacionDiariaTransaccionAbiertaJob.ejecutar();

        assertThat(contarAvisosComprador(transaccion)).isEqualTo(1);
        assertThat(contarAvisosVendedor(transaccion)).isEqualTo(1);
        assertThat(consultarEstado(transaccion)).isEqualTo("reservada");
        assertThat(consultarStock(transaccion)).isEqualTo(stockAntes);
        assertThat(contarEventos(transaccion)).isZero();
    }

    /**
     * Verifies that the append-only event that entered dispute, rather than a transaction column,
     * controls the first-notice delay for a dispute.
     */
    @Test
    @DisplayName("La primera disputa usa el evento destino disputa para esperar o emitir sus avisos")
    void ejecutar_PrimeraDisputaSinAvisos_UsaEventoDestinoDisputaComoEntradaAlEstado() {
        Transaccion disputaReciente = crearTransaccion("primera-disputa-reciente", EstadoTransaccion.DISPUTA);
        insertarEventoEntradaDisputa(disputaReciente, ZonedDateTime.now(PERU_ZONE).minusHours(23));
        Transaccion disputaVencida = crearTransaccion("primera-disputa-vencida", EstadoTransaccion.DISPUTA);
        insertarEventoEntradaDisputa(disputaVencida, ZonedDateTime.now(PERU_ZONE).minusHours(25));

        notificacionDiariaTransaccionAbiertaJob.ejecutar();

        assertThat(contarAvisosComprador(disputaReciente)).isZero();
        assertThat(contarAvisosVendedor(disputaReciente)).isZero();
        assertThat(contarAvisosComprador(disputaVencida)).isEqualTo(1);
        assertThat(contarAvisosVendedor(disputaVencida)).isEqualTo(1);
        assertThat(contarEventos(disputaReciente)).isEqualTo(1);
        assertThat(contarEventos(disputaVencida)).isEqualTo(1);
    }

    /**
     * Verifies that a final transaction never receives daily notifications even when the last
     * matching notices are older than the eligibility threshold.
     */
    @Test
    @DisplayName("Una transacción recibida no genera avisos diarios aunque sus avisos previos tengan más de 24 horas")
    void ejecutar_RecibidoConAvisosDeMasDe24Horas_NoGeneraNingunAviso() {
        Transaccion transaccion = crearTransaccion("recibida-final", EstadoTransaccion.RECIBIDO);
        insertarAvisosDiarios(transaccion, ZonedDateTime.now(PERU_ZONE).minusHours(25));

        notificacionDiariaTransaccionAbiertaJob.ejecutar();

        assertThat(contarAvisosComprador(transaccion)).isEqualTo(1);
        assertThat(contarAvisosVendedor(transaccion)).isEqualTo(1);
        assertThat(consultarEstado(transaccion)).isEqualTo("recibido");
        assertThat(contarEventos(transaccion)).isZero();
    }

    /**
     * Verifies that the strict 24-hour interval prevents a duplicate while either recipient's last
     * daily notification remains safely inside the interval.
     */
    @Test
    @DisplayName("Una corrida antes de 24 horas no duplica los avisos diarios")
    void ejecutar_ReservadaConAvisosRecientes_NoDuplica() {
        Transaccion transaccion = crearTransaccion("reservada-reciente", EstadoTransaccion.RESERVADA);
        insertarAvisosDiarios(transaccion, ZonedDateTime.now(PERU_ZONE).minusHours(23));

        notificacionDiariaTransaccionAbiertaJob.ejecutar();

        assertThat(contarAvisosComprador(transaccion)).isEqualTo(1);
        assertThat(contarAvisosVendedor(transaccion)).isEqualTo(1);
    }

    /**
     * Verifies that two simultaneous eligible executions and a later execution produce exactly one
     * new daily notification per recipient for the same transaction.
     */
    @Test
    @DisplayName("Dos corridas concurrentes y una posterior no duplican los avisos diarios elegibles")
    void ejecutar_CorridasConcurrentesYPosterior_NoDuplicanAvisosElegibles() {
        Transaccion transaccion = crearTransaccion("reservada-concurrente", EstadoTransaccion.RESERVADA);
        insertarAvisosDiarios(transaccion, ZonedDateTime.now(PERU_ZONE).minusHours(25));

        ejecutarDosVecesEnParalelo();
        notificacionDiariaTransaccionAbiertaJob.ejecutar();

        assertThat(contarAvisosComprador(transaccion)).isEqualTo(2);
        assertThat(contarAvisosVendedor(transaccion)).isEqualTo(2);
    }

    /**
     * Creates a transaction fixture in the supplied state with a distinct buyer, seller, and
     * publication.
     *
     * @param etiqueta unique fixture label
     * @param estado state to persist for the transaction
     * @return persisted transaction fixture
     */
    private Transaccion crearTransaccion(String etiqueta, EstadoTransaccion estado) {
        return crearTransaccion(etiqueta, estado, ZonedDateTime.now(PERU_ZONE).minusDays(3));
    }

    /**
     * Creates a transaction fixture in the supplied state and with an explicit reservation time.
     *
     * @param etiqueta unique fixture label
     * @param estado state to persist for the transaction
     * @param fechaReservada timestamp to persist as the reservation-state entry time
     * @return persisted transaction fixture
     */
    private Transaccion crearTransaccion(String etiqueta, EstadoTransaccion estado, ZonedDateTime fechaReservada) {
        String sufijo = etiqueta + "-" + System.nanoTime();
        ZonedDateTime ahora = ZonedDateTime.now(PERU_ZONE);
        Usuario vendedor = usuarioRepository.save(new Usuario("vendedor-diario-" + sufijo + "@example.com", "hash", Rol.USUARIO, 0L, ahora));
        Usuario comprador = usuarioRepository.save(new Usuario("comprador-diario-" + sufijo + "@example.com", "hash", Rol.USUARIO, 0L, ahora));
        Categoria categoria = categoriaRepository.save(new Categoria("Categoría diario " + sufijo));
        Subcategoria subcategoria = subcategoriaRepository.save(new Subcategoria(categoria, "Subcategoría diario " + sufijo));
        Publicacion publicacion = publicacionService.crearPublicacion(vendedor.getId(), categoria.getId(), subcategoria.getId(), 12_345L, 2, "Publicación para aviso diario", null);
        publicacionService.cambiarEstado(publicacion.getId(), EstadoPublicacion.APROBADA, null);
        Transaccion transaccion = new Transaccion(comprador, publicacion, 12_345L, fechaReservada);
        transaccion.setEstado(estado);
        return transaccionRepository.saveAndFlush(transaccion);
    }

    /**
     * Inserts the immutable event that records a transition into dispute at the supplied instant.
     *
     * @param transaccion disputed transaction whose append-only history is populated
     * @param createdAt timestamp of the transition into dispute
     */
    private void insertarEventoEntradaDisputa(Transaccion transaccion, ZonedDateTime createdAt) {
        jdbcTemplate.update("INSERT INTO transaccion_eventos (transaccion_id, estado_origen, estado_destino, created_at) VALUES (?, ?, ?, ?)",
            transaccion.getId(), "entregado", "disputa", createdAt.toOffsetDateTime());
    }

    /**
     * Inserts one prior matching daily notification for each transaction participant.
     *
     * @param transaccion transaction to associate through V14
     * @param createdAt timestamp of both prior notifications
     */
    private void insertarAvisosDiarios(Transaccion transaccion, ZonedDateTime createdAt) {
        jdbcTemplate.update("INSERT INTO notificaciones (usuario_id, transaccion_id, mensaje, tipo, created_at) VALUES (?, ?, ?, ?, ?)", transaccion.getComprador().getId(), transaccion.getId(), MENSAJE_COMPRADOR, TIPO_COMPRADOR, createdAt.toOffsetDateTime());
        jdbcTemplate.update("INSERT INTO notificaciones (usuario_id, transaccion_id, mensaje, tipo, created_at) VALUES (?, ?, ?, ?, ?)", transaccion.getPublicacion().getUsuario().getId(), transaccion.getId(), MENSAJE_VENDEDOR, TIPO_VENDEDOR, createdAt.toOffsetDateTime());
    }

    /** Starts two job invocations concurrently and waits until both complete. */
    private void ejecutarDosVecesEnParalelo() {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch arranque = new CountDownLatch(1);
        try {
            Future<?> ejecucionA = executor.submit(() -> ejecutarTrasArranque(arranque));
            Future<?> ejecucionB = executor.submit(() -> ejecutarTrasArranque(arranque));
            arranque.countDown();
            esperarEjecucion(ejecucionA);
            esperarEjecucion(ejecucionB);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Waits for the shared start signal and invokes the job.
     *
     * @param arranque latch releasing both worker threads
     * @return {@code null} after the invocation completes
     */
    private Void ejecutarTrasArranque(CountDownLatch arranque) {
        try {
            arranque.await();
            notificacionDiariaTransaccionAbiertaJob.ejecutar();
            return null;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("La sincronización de ejecuciones concurrentes fue interrumpida.", exception);
        }
    }

    /**
     * Waits for one concurrent invocation and exposes its failure to the test.
     *
     * @param ejecucion future representing a job invocation
     */
    private void esperarEjecucion(Future<?> ejecucion) {
        try {
            ejecucion.get(60, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("La ejecución concurrente fue interrumpida.", exception);
        } catch (ExecutionException exception) {
            throw new AssertionError("La ejecución concurrente del job falló.", exception.getCause());
        } catch (java.util.concurrent.TimeoutException exception) {
            throw new AssertionError("La ejecución concurrente del job superó 60 segundos.", exception);
        }
    }

    /**
     * Counts exact buyer daily notifications isolated by transaction, recipient, type, and message.
     *
     * @param transaccion transaction whose buyer notifications are counted
     * @return matching persisted-row count
     */
    private Integer contarAvisosComprador(Transaccion transaccion) {
        return contarAvisos(transaccion, transaccion.getComprador().getId(), TIPO_COMPRADOR, MENSAJE_COMPRADOR);
    }

    /**
     * Counts exact seller daily notifications isolated by transaction, recipient, type, and message.
     *
     * @param transaccion transaction whose seller notifications are counted
     * @return matching persisted-row count
     */
    private Integer contarAvisosVendedor(Transaccion transaccion) {
        return contarAvisos(transaccion, transaccion.getPublicacion().getUsuario().getId(), TIPO_VENDEDOR, MENSAJE_VENDEDOR);
    }

    /**
     * Counts exact daily notifications using every business discriminator mandated by the task.
     *
     * @param transaccion transaction association to match
     * @param usuarioId notification recipient to match
     * @param tipo notification type to match
     * @param mensaje literal notification message to match
     * @return matching persisted-row count
     */
    private Integer contarAvisos(Transaccion transaccion, Long usuarioId, String tipo, String mensaje) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notificaciones WHERE transaccion_id = ? AND usuario_id = ? AND tipo = ? AND mensaje = ?", Integer.class, transaccion.getId(), usuarioId, tipo, mensaje);
    }

    /**
     * Reads the persisted transaction state.
     *
     * @param transaccion transaction to inspect
     * @return persisted state value
     */
    private String consultarEstado(Transaccion transaccion) {
        return jdbcTemplate.queryForObject("SELECT estado FROM transacciones WHERE id = ?", String.class, transaccion.getId());
    }

    /**
     * Reads the persisted publication stock.
     *
     * @param transaccion transaction whose publication stock is inspected
     * @return current persisted stock
     */
    private long consultarStock(Transaccion transaccion) {
        Long stock = jdbcTemplate.queryForObject("SELECT stock FROM publicaciones WHERE id = ?", Long.class, transaccion.getPublicacion().getId());
        return stock == null ? -1L : stock;
    }

    /**
     * Counts canonical transaction events for the supplied transaction.
     *
     * @param transaccion transaction whose event history is counted
     * @return canonical event count
     */
    private Integer contarEventos(Transaccion transaccion) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transaccion_eventos WHERE transaccion_id = ?", Integer.class, transaccion.getId());
    }
}
