package com.easymarket.marketplace.service;

import com.easymarket.marketplace.exception.StockAgotadoException;
import com.easymarket.marketplace.model.Categoria;
import com.easymarket.marketplace.model.EstadoPublicacion;
import com.easymarket.marketplace.model.Publicacion;
import com.easymarket.marketplace.model.Rol;
import com.easymarket.marketplace.model.Subcategoria;
import com.easymarket.marketplace.model.Transaccion;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas de integración para {@link ReservaStockService} contra PostgreSQL real mediante
 * Testcontainers (tarea PHA03TSK04 de {@code tasks.md}, Story 5 de {@code spec.md}).
 *
 * <p>Test previo obligatorio de la fila: "dos compras simultáneas sobre última unidad — solo la
 * de timestamp más temprano gana". Verifica la carrera real de la última unidad:
 * <ul>
 *   <li>Dos compradores distintos intentan reservar simultáneamente la misma publicación con
 *       {@code stock = 1} (ExecutorService + CountDownLatch para sincronizar el arranque).</li>
 *   <li>Exactamente UNA transacción {@code reservada} queda persistida en la base de datos real,
 *       con {@code precio_snapshot} igual al precio de la publicación en el instante de la
 *       reserva y {@code fecha_reservada} poblada.</li>
 *   <li>El stock final de la publicación es 0 y el perdedor recibió
 *       {@link StockAgotadoException}.</li>
 * </ul>
 *
 * <p><strong>Diferencia deliberada con el patrón de {@code PublicacionServiceIntegrationTests}:</strong>
 * esta clase NO usa {@code @Transactional} a nivel de clase. La carrera de dos hilos requiere que
 * cada llamada al servicio abra su PROPIA transacción de base de datos sobre una conexión distinta
 * del pool (propagación REQUIRED en hilo distinto); si el test estuviera envuelto en una
 * transacción compartida, ambos hilos verían el mismo snapshot transaccional y el lock de fila de
 * PostgreSQL no podría materializar la prioridad FIFO — el test no probaría concurrencia real.</p>
 *
 * <p>El assert de resultado verifica "solo uno gana", NO "cuál gana": no se puede controlar qué
 * hilo adquiere primero el lock de fila, así que el ganador puede ser cualquiera de los dos
 * compradores (riesgo de flakiness evitado por diseño).</p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=test-secret-key-32-chars-minimum-ok"
})
class ReservaStockServiceIntegrationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ReservaStockService reservaStockService;

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
     * Ejecuta la carrera de la última unidad: dos compradores distintos intentan reservar
     * simultáneamente la misma publicación {@code aprobada} con {@code stock = 1}. Verifica
     * contra la base de datos real que exactamente una transacción {@code reservada} queda
     * persistida con el snapshot de precio correcto, que el stock final es 0 y que el perdedor
     * recibió {@link StockAgotadoException} (Story 5, spec.md: "solo la de timestamp más
     * temprano obtiene la reserva; la otra es rechazada por falta de stock").
     */
    @Test
    @DisplayName("Dos compras simultáneas sobre la última unidad: exactamente una transacción 'reservada', stock final 0 y el perdedor recibe StockAgotadoException")
    void reservarStock_DosComprasSimultaneasSobreUltimaUnidad_SoloUnaGana() throws Exception {
        String sufijo = Long.toString(System.nanoTime());
        Usuario vendedor = usuarioRepository.save(
            new Usuario("vendedor-carrera-" + sufijo + "@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now())
        );
        Usuario compradorA = usuarioRepository.save(
            new Usuario("comprador-a-" + sufijo + "@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now())
        );
        Usuario compradorB = usuarioRepository.save(
            new Usuario("comprador-b-" + sufijo + "@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now())
        );

        Categoria categoria = categoriaRepository.save(new Categoria("Categoría carrera " + sufijo));
        Subcategoria subcategoria = subcategoriaRepository.save(new Subcategoria(categoria, "Subcategoría carrera " + sufijo));

        long precio = 250000L;
        Publicacion publicacion = publicacionService.crearPublicacion(
            vendedor.getId(), categoria.getId(), subcategoria.getId(), precio, 1, "Última unidad para carrera", null
        );
        publicacionService.cambiarEstado(publicacion.getId(), EstadoPublicacion.APROBADA, null);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch arranque = new CountDownLatch(1);

        Future<Object> intentoA = executor.submit(() -> {
            arranque.await();
            try {
                return reservaStockService.reservarStock(compradorA.getId(), publicacion.getId());
            } catch (Throwable t) {
                return t;
            }
        });
        Future<Object> intentoB = executor.submit(() -> {
            arranque.await();
            try {
                return reservaStockService.reservarStock(compradorB.getId(), publicacion.getId());
            } catch (Throwable t) {
                return t;
            }
        });

        arranque.countDown();
        Object resultadoA = intentoA.get(60, TimeUnit.SECONDS);
        Object resultadoB = intentoB.get(60, TimeUnit.SECONDS);
        executor.shutdownNow();

        // Clasificación agnóstica del ganador: exactamente uno de los dos intentos devolvió una
        // transacción y el otro fue rechazado con StockAgotadoException (no se asume cuál gana).
        List<Transaccion> transaccionesGanadoras = new ArrayList<>();
        List<StockAgotadoException> perdedores = new ArrayList<>();
        for (Object resultado : List.of(resultadoA, resultadoB)) {
            if (resultado instanceof Transaccion transaccion) {
                transaccionesGanadoras.add(transaccion);
            } else if (resultado instanceof StockAgotadoException excepcion) {
                perdedores.add(excepcion);
            }
        }

        assertThat(transaccionesGanadoras).hasSize(1);
        assertThat(perdedores).hasSize(1);

        // Verificación directa contra la base de datos real: exactamente una transacción
        // 'reservada' para esa publicación, con snapshot de precio inmutable de la publicación.
        Integer totalTransacciones = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM transacciones WHERE publicacion_id = ?", Integer.class, publicacion.getId()
        );
        assertThat(totalTransacciones).isEqualTo(1);

        String estado = jdbcTemplate.queryForObject(
            "SELECT estado FROM transacciones WHERE publicacion_id = ?", String.class, publicacion.getId()
        );
        assertThat(estado).isEqualTo("reservada");

        Long precioSnapshot = jdbcTemplate.queryForObject(
            "SELECT precio_snapshot FROM transacciones WHERE publicacion_id = ?", Long.class, publicacion.getId()
        );
        assertThat(precioSnapshot).isEqualTo(precio);

        // El decremento atómico condicional dejó el stock final en 0 (una sola unidad reservada).
        Integer stockFinal = jdbcTemplate.queryForObject(
            "SELECT stock FROM publicaciones WHERE id = ?", Integer.class, publicacion.getId()
        );
        assertThat(stockFinal).isZero();
    }
}
