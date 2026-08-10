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

import java.time.ZonedDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link AutoConfirmarRecepcionJob} against PostgreSQL via Testcontainers.
 *
 * <p>The test intentionally has no test-level transaction: each concurrent invocation reaches the
 * Spring proxy and opens its own database transaction, allowing PostgreSQL's {@code FOR UPDATE SKIP
 * LOCKED} to be exercised against actual connections.</p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=test-secret-key-32-chars-minimum-ok"
})
class AutoConfirmarRecepcionJobIntegrationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private AutoConfirmarRecepcionJob autoConfirmarRecepcionJob;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private CategoriaRepository categoriaRepository;

    @Autowired
    private SubcategoriaRepository subcategoriaRepository;

    @Autowired
    private PublicacionService publicacionService;

    @Autowired
    private TransaccionRepository transaccionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Auto-confirms one delivered transaction whose delivery timestamp is 49 hours old when two
     * job executions begin concurrently, without duplicating the credit or append-only records.
     *
     * @throws Exception if a concurrent invocation does not finish within its time limit
     */
    @Test
    @DisplayName("Una transacción entregada hace 49 horas se auto-confirma una sola vez bajo dos ejecuciones concurrentes")
    void ejecutar_TransaccionEntregadaHace49Horas_TransicionaYAcreditaUnaSolaVezBajoConcurrencia() throws Exception {
        long precioSnapshot = 12_345L;
        Transaccion transaccion = crearTransaccionEntregadaHace49Horas(precioSnapshot);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch arranque = new CountDownLatch(1);
        Future<?> ejecucionA = executor.submit(() -> {
            arranque.await();
            autoConfirmarRecepcionJob.ejecutar();
            return null;
        });
        Future<?> ejecucionB = executor.submit(() -> {
            arranque.await();
            autoConfirmarRecepcionJob.ejecutar();
            return null;
        });

        arranque.countDown();
        ejecucionA.get(60, TimeUnit.SECONDS);
        ejecucionB.get(60, TimeUnit.SECONDS);
        executor.shutdownNow();

        String estado = jdbcTemplate.queryForObject(
            "SELECT estado FROM transacciones WHERE id = ?", String.class, transaccion.getId());
        Long saldoVendedor = jdbcTemplate.queryForObject(
            "SELECT saldo_disponible FROM usuarios WHERE id = ?", Long.class,
            transaccion.getPublicacion().getUsuario().getId());
        Integer movimientos = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM movimientos_saldo WHERE transaccion_id = ?", Integer.class, transaccion.getId());
        Integer eventos = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM transaccion_eventos WHERE transaccion_id = ? "
                + "AND estado_origen = 'entregado' AND estado_destino = 'recibido_sin_respuesta' "
                + "AND actor_id IS NULL AND motivo IS NULL",
            Integer.class, transaccion.getId());
        Long montoMovimiento = jdbcTemplate.queryForObject(
            "SELECT monto FROM movimientos_saldo WHERE transaccion_id = ?", Long.class, transaccion.getId());

        assertThat(estado).isEqualTo("recibido_sin_respuesta");
        assertThat(saldoVendedor).isEqualTo(precioSnapshot);
        assertThat(movimientos).isEqualTo(1);
        assertThat(montoMovimiento).isEqualTo(precioSnapshot).isPositive();
        assertThat(eventos).isEqualTo(1);
    }

    /**
     * Creates all persisted data required for an eligible delivered transaction with a timestamp
     * deliberately set 49 hours in the past.
     *
     * @param precioSnapshot immutable amount in cents to credit to the seller
     * @return persisted delivered transaction eligible for automatic confirmation
     */
    private Transaccion crearTransaccionEntregadaHace49Horas(long precioSnapshot) {
        String sufijo = Long.toString(System.nanoTime());
        Usuario vendedor = usuarioRepository.save(
            new Usuario("vendedor-auto-" + sufijo + "@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now()));
        Usuario comprador = usuarioRepository.save(
            new Usuario("comprador-auto-" + sufijo + "@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now()));
        Categoria categoria = categoriaRepository.save(new Categoria("Categoría auto " + sufijo));
        Subcategoria subcategoria = subcategoriaRepository.save(new Subcategoria(categoria, "Subcategoría auto " + sufijo));
        Publicacion publicacion = publicacionService.crearPublicacion(
            vendedor.getId(), categoria.getId(), subcategoria.getId(), precioSnapshot, 1, "Publicación para auto-confirmación");
        publicacionService.cambiarEstado(publicacion.getId(), EstadoPublicacion.APROBADA, null);

        Transaccion transaccion = new Transaccion(comprador, publicacion, precioSnapshot, ZonedDateTime.now().minusDays(3));
        transaccion.setEstado(EstadoTransaccion.ENTREGADO);
        transaccion.setFechaEntregado(ZonedDateTime.now().minusHours(49));
        return transaccionRepository.save(transaccion);
    }
}
