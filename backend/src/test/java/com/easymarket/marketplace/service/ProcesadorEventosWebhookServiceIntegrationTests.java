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
import com.easymarket.marketplace.repository.IdempotencyKeyRepository;
import com.easymarket.marketplace.repository.ProcessedStripeEventRepository;
import com.easymarket.marketplace.repository.SubcategoriaRepository;
import com.easymarket.marketplace.repository.UsuarioRepository;
import com.stripe.model.Event;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas de integración para {@link ProcesadorEventosWebhookService} contra PostgreSQL real
 * mediante Testcontainers (tarea PHA03TSK08 de {@code tasks.md}, Story 5 de {@code spec.md}).
 *
 * <p>Test previo obligatorio de la fila: "evento repetido no duplica efectos;
 * {@code payment_intent.succeeded} crea transacción {@code reservada};
 * {@code payment_intent.payment_failed} no deja stock apartado ni transacción huérfana".</p>
 *
 * <p><strong>Diferencia deliberada con el patrón de {@code ReservaStockServiceIntegrationTests}:</strong>
 * no hay carrera de concurrencia aquí; el procesamiento de eventos es secuencial (Stripe entrega
 * un webhook a la vez para un mismo event_id). Sí se verifica idempotencia: la segunda invocación
 * con el mismo event_id no debe crear una segunda transacción.</p>
 *
 * <p>El objeto {@link Event} de Stripe se construye manualmente para los tests (solo se usan
 * {@code event.getId()} y {@code event.getType()}, no se necesita deserializar el
 * {@code data.object} real); los parámetros {@code compradorId}, {@code publicacionId} y
 * {@code paymentIntentId} se proporcionan por separado conforme a la firma actual.</p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=test-secret-key-32-chars-minimum-ok"
})
class ProcesadorEventosWebhookServiceIntegrationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ProcesadorEventosWebhookService procesadorEventosWebhookService;

    @Autowired
    private PublicacionService publicacionService;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private CategoriaRepository categoriaRepository;

    @Autowired
    private SubcategoriaRepository subcategoriaRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private ProcessedStripeEventRepository processedStripeEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Test de idempotencia: el mismo event_id procesado dos veces no debe crear una segunda
     * transacción. Setup: publicación con stock=1, comprador distinto al vendedor. La primera
     * llamada crea la transacción; la segunda debe ser ignorada silenciosamente (null).
     */
    @Test
    @DisplayName("Evento repetido con mismo event_id no duplica efectos")
    void procesarEvento_EventoRepetido_NoDuplicaTransaccion() {
        String sufijo = Long.toString(System.nanoTime());
        Usuario vendedor = usuarioRepository.save(
            new Usuario("vendedor-idemp-" + sufijo + "@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now())
        );
        Usuario comprador = usuarioRepository.save(
            new Usuario("comprador-idemp-" + sufijo + "@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now())
        );

        Categoria categoria = categoriaRepository.save(new Categoria("Categoría idemp " + sufijo));
        Subcategoria subcategoria = subcategoriaRepository.save(new Subcategoria(categoria, "Subcategoría idemp " + sufijo));

        long precio = 299900L;
        Publicacion publicacion = publicacionService.crearPublicacion(
            vendedor.getId(), categoria.getId(), subcategoria.getId(), precio, 1, "Artículo para test de idempotencia", null
        );
        publicacionService.cambiarEstado(publicacion.getId(), EstadoPublicacion.APROBADA, null);

        String eventId = "evt_test_idemp_" + sufijo;
        String paymentIntentId = "pi_test_idemp_" + sufijo;

        Event event = new Event();
        event.setId(eventId);
        event.setType("payment_intent.succeeded");

        // Primera llamada — debe crear la transacción
        procesadorEventosWebhookService.procesarEvento(event, comprador.getId(), publicacion.getId(), paymentIntentId);

        Integer transaccionesCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM transacciones WHERE publicacion_id = ?", Integer.class, publicacion.getId()
        );
        assertThat(transaccionesCount).isEqualTo(1);

        // Segunda llamada con el mismo eventId — debe ser ignorada
        procesadorEventosWebhookService.procesarEvento(event, comprador.getId(), publicacion.getId(), paymentIntentId);

        // El conteo no debe haber cambiado
        Integer transaccionesCountFinal = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM transacciones WHERE publicacion_id = ?", Integer.class, publicacion.getId()
        );
        assertThat(transaccionesCountFinal).isEqualTo(1);

        Integer avisosVendedorCountFinal = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM notificaciones n "
                + "JOIN transacciones t ON t.id = n.transaccion_id "
                + "WHERE n.usuario_id = ? AND n.tipo = 'COMPRA_CONFIRMADA' "
                + "AND t.publicacion_id = ?",
            Integer.class, vendedor.getId(), publicacion.getId()
        );
        assertThat(avisosVendedorCountFinal).isEqualTo(1);
    }

    /**
     * Test de evento {@code payment_intent.succeeded}: debe crear una transacción en estado
     * {@code reservada} con el snapshot de precio correcto y decrementar el stock en 1.
     */
    @Test
    @DisplayName("payment_intent.succeeded crea transacción 'reservada' y decrementa stock")
    void procesarEvento_Succeeded_CreaTransaccionReservada() {
        String sufijo = Long.toString(System.nanoTime());
        Usuario vendedor = usuarioRepository.save(
            new Usuario("vendedor-succ-" + sufijo + "@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now())
        );
        Usuario comprador = usuarioRepository.save(
            new Usuario("comprador-succ-" + sufijo + "@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now())
        );

        Categoria categoria = categoriaRepository.save(new Categoria("Categoría succ " + sufijo));
        Subcategoria subcategoria = subcategoriaRepository.save(new Subcategoria(categoria, "Subcategoría succ " + sufijo));

        long precio = 150000L;
        Publicacion publicacion = publicacionService.crearPublicacion(
            vendedor.getId(), categoria.getId(), subcategoria.getId(), precio, 1, "Artículo para test succeeded", null
        );
        publicacionService.cambiarEstado(publicacion.getId(), EstadoPublicacion.APROBADA, null);

        String eventId = "evt_test_succ_" + sufijo;
        String paymentIntentId = "pi_test_succ_" + sufijo;

        Event event = new Event();
        event.setId(eventId);
        event.setType("payment_intent.succeeded");

        Transaccion transaccion = procesadorEventosWebhookService.procesarEvento(
            event, comprador.getId(), publicacion.getId(), paymentIntentId);

        // Verificar que exactamente una transacción 'reservada' fue creada
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

        // El stock debe haber decrementado de 1 a 0
        Integer stockFinal = jdbcTemplate.queryForObject(
            "SELECT stock FROM publicaciones WHERE id = ?", Integer.class, publicacion.getId()
        );
        assertThat(stockFinal).isZero();

        Integer avisosVendedor = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM notificaciones n "
                + "JOIN transacciones t ON t.id = n.transaccion_id "
                + "WHERE n.usuario_id = ? AND n.transaccion_id = t.id "
                + "AND n.tipo = 'COMPRA_CONFIRMADA' AND t.publicacion_id = ?",
            Integer.class, vendedor.getId(), publicacion.getId());
        assertThat(avisosVendedor).isEqualTo(1);

        Map<String, Object> avisoVendedor = jdbcTemplate.queryForMap(
            "SELECT n.usuario_id, n.tipo, n.transaccion_id, n.publicacion_id, n.mensaje "
                + "FROM notificaciones n "
                + "WHERE n.usuario_id = ? AND n.tipo = 'COMPRA_CONFIRMADA' "
                + "AND n.transaccion_id = ? AND n.publicacion_id = ?",
            vendedor.getId(), transaccion.getId(), publicacion.getId());
        assertThat(avisoVendedor.get("usuario_id")).isEqualTo(vendedor.getId());
        assertThat(avisoVendedor.get("tipo")).isEqualTo("COMPRA_CONFIRMADA");
        assertThat(avisoVendedor.get("transaccion_id")).isEqualTo(transaccion.getId());
        assertThat(avisoVendedor.get("publicacion_id")).isEqualTo(publicacion.getId());
        assertThat(avisoVendedor.get("mensaje").toString())
            .contains("Nueva compra confirmada en tu publicación #" + publicacion.getId())
            .contains("transacción #" + transaccion.getId());
    }

    /**
     * Test de evento {@code payment_intent.payment_failed}: no debe crear ninguna transacción
     * ni modificar el stock.
     */
    @Test
    @DisplayName("payment_intent.payment_failed no crea transacción ni modifica stock")
    void procesarEvento_Failed_NoCreaTransaccionNiModificaStock() {
        String sufijo = Long.toString(System.nanoTime());
        Usuario vendedor = usuarioRepository.save(
            new Usuario("vendedor-fail-" + sufijo + "@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now())
        );
        Usuario comprador = usuarioRepository.save(
            new Usuario("comprador-fail-" + sufijo + "@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now())
        );

        Categoria categoria = categoriaRepository.save(new Categoria("Categoría fail " + sufijo));
        Subcategoria subcategoria = subcategoriaRepository.save(new Subcategoria(categoria, "Subcategoría fail " + sufijo));

        Publicacion publicacion = publicacionService.crearPublicacion(
            vendedor.getId(), categoria.getId(), subcategoria.getId(), 10000L, 5, "Artículo para test payment_failed", null
        );
        publicacionService.cambiarEstado(publicacion.getId(), EstadoPublicacion.APROBADA, null);

        String eventId = "evt_test_fail_" + sufijo;
        String paymentIntentId = "pi_test_fail_" + sufijo;

        Event event = new Event();
        event.setId(eventId);
        event.setType("payment_intent.payment_failed");

        procesadorEventosWebhookService.procesarEvento(event, comprador.getId(), publicacion.getId(), paymentIntentId);

        // No debe haber transacción creada
        Integer totalTransacciones = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM transacciones WHERE publicacion_id = ?", Integer.class, publicacion.getId()
        );
        assertThat(totalTransacciones).isZero();

        // El stock debe permanecer intacto (5 unidades)
        Integer stockFinal = jdbcTemplate.queryForObject(
            "SELECT stock FROM publicaciones WHERE id = ?", Integer.class, publicacion.getId()
        );
        assertThat(stockFinal).isEqualTo(5);
    }
}
