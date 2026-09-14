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

import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 *
 * <p>Extensión PHA16TSK02 (refund durable al perdedor de la carrera de stock, Story 5): cinco
 * tests adicionales cubren (a) stock agotado → orden {@code PENDIENTE} sin transacción y evento
 * marcado; (b) reserva exitosa → sin orden (regresión); (c) re-entrega del mismo
 * {@code eventId} → sin segunda orden; (d) fallo al persistir la orden → revierte la marca del
 * evento; (e) {@code charge.refunded} y {@code payment_intent.payment_failed} sin cambios.</p>
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

    /**
     * Test del criterio (a) de PHA16TSK02: stock agotado al procesar
     * {@code payment_intent.succeeded} (perdedor de la carrera de stock, Story 5) → orden
     * durable {@code PENDIENTE} con {@code transaccion_id} NULL, {@code payment_intent_id} exacto
     * y clave idempotente exacta {@code refund:pi:<pi>}; el evento queda registrado en
     * {@code processed_stripe_events} y no se crea ninguna transacción.
     */
    @Test
    @DisplayName("Stock agotado crea orden PENDIENTE sin transaccion y marca el evento")
    void procesarEvento_StockAgotado_CreaOrdenPendienteSinTransaccion() {
        String sufijo = Long.toString(System.nanoTime());
        EscenarioCompra escenario = crearEscenarioCompra("agot-" + sufijo, 1);
        // Agotar el stock por SQL directo: el decremento condicional de reservarStock no
        // afectará filas y lanzará StockAgotadoException (perdedor de la carrera).
        jdbcTemplate.update("UPDATE publicaciones SET stock = 0 WHERE id = ?", escenario.publicacion().getId());

        String eventId = "evt_test_agot_" + sufijo;
        String paymentIntentId = "pi_test_agot_" + sufijo;

        Transaccion resultado = procesadorEventosWebhookService.procesarEvento(
            eventoStripe(eventId, "payment_intent.succeeded"),
            escenario.comprador().getId(), escenario.publicacion().getId(), paymentIntentId);

        assertThat(resultado).isNull();

        Map<String, Object> orden = jdbcTemplate.queryForMap(
            "SELECT transaccion_id, payment_intent_id, idempotency_key, estado, intentos "
                + "FROM stripe_refund_outbox WHERE payment_intent_id = ?",
            paymentIntentId);
        assertThat(orden.get("transaccion_id")).isNull();
        assertThat(orden.get("payment_intent_id")).isEqualTo(paymentIntentId);
        assertThat(orden.get("idempotency_key")).isEqualTo("refund:pi:" + paymentIntentId);
        assertThat(orden.get("estado")).isEqualTo("PENDIENTE");
        assertThat(((Number) orden.get("intentos")).intValue()).isZero();

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM processed_stripe_events WHERE event_id = ?", Integer.class, eventId
        )).isOne();

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM transacciones WHERE publicacion_id = ?",
            Integer.class, escenario.publicacion().getId()
        )).isZero();
    }

    /**
     * Test del criterio (b) de PHA16TSK02 (regresión del camino feliz): reserva exitosa →
     * transacción creada y NINGUNA orden de refund en la outbox para ese pago.
     */
    @Test
    @DisplayName("Reserva exitosa no crea orden de refund (regresion del camino feliz)")
    void procesarEvento_ReservaExitosa_NoCreaOrdenDeRefund() {
        String sufijo = Long.toString(System.nanoTime());
        EscenarioCompra escenario = crearEscenarioCompra("ok-" + sufijo, 1);

        String eventId = "evt_test_ok_" + sufijo;
        String paymentIntentId = "pi_test_ok_" + sufijo;

        Transaccion transaccion = procesadorEventosWebhookService.procesarEvento(
            eventoStripe(eventId, "payment_intent.succeeded"),
            escenario.comprador().getId(), escenario.publicacion().getId(), paymentIntentId);

        assertThat(transaccion).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM stripe_refund_outbox WHERE payment_intent_id = ?",
            Integer.class, paymentIntentId
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM transacciones WHERE publicacion_id = ?",
            Integer.class, escenario.publicacion().getId()
        )).isOne();
    }

    /**
     * Test del criterio (c) de PHA16TSK02 (idempotencia): la re-entrega del mismo
     * {@code eventId} tras un stock agotado retorna temprano por la guardia de
     * {@code processed_stripe_events} y no crea una segunda orden de refund.
     */
    @Test
    @DisplayName("Re-entrega del mismo eventId tras stock agotado no crea segunda orden")
    void procesarEvento_EventoRepetidoTrasStockAgotado_NoDuplicaOrden() {
        String sufijo = Long.toString(System.nanoTime());
        EscenarioCompra escenario = crearEscenarioCompra("repet-" + sufijo, 1);
        jdbcTemplate.update("UPDATE publicaciones SET stock = 0 WHERE id = ?", escenario.publicacion().getId());

        String eventId = "evt_test_repet_" + sufijo;
        String paymentIntentId = "pi_test_repet_" + sufijo;
        Event event = eventoStripe(eventId, "payment_intent.succeeded");

        Transaccion primera = procesadorEventosWebhookService.procesarEvento(
            event, escenario.comprador().getId(), escenario.publicacion().getId(), paymentIntentId);
        Transaccion segunda = procesadorEventosWebhookService.procesarEvento(
            event, escenario.comprador().getId(), escenario.publicacion().getId(), paymentIntentId);

        assertThat(primera).isNull();
        assertThat(segunda).isNull();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM stripe_refund_outbox WHERE payment_intent_id = ?",
            Integer.class, paymentIntentId
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM transacciones WHERE publicacion_id = ?",
            Integer.class, escenario.publicacion().getId()
        )).isZero();
    }

    /**
     * Test del criterio (d) de PHA16TSK02 (atomicidad, constitution principio 1): si la
     * persistencia de la orden falla (clave idempotente duplicada), la excepción se propaga y
     * la marca del evento en {@code processed_stripe_events} se revierte con ella — el evento
     * NO queda registrado.
     */
    @Test
    @DisplayName("Fallo al persistir la orden revierte tambien la marca del evento")
    void procesarEvento_FalloAlPersistirOrden_RevierteMarcaDelEvento() {
        String sufijo = Long.toString(System.nanoTime());
        EscenarioCompra escenario = crearEscenarioCompra("atomic-" + sufijo, 1);
        jdbcTemplate.update("UPDATE publicaciones SET stock = 0 WHERE id = ?", escenario.publicacion().getId());

        String eventId = "evt_test_atomic_" + sufijo;
        String paymentIntentId = "pi_test_atomic_" + sufijo;
        // Pre-insertar una orden con la misma clave determinista que el servicio intentará
        // persistir: el save violará el UNIQUE de idempotency_key.
        jdbcTemplate.update(
            "INSERT INTO stripe_refund_outbox (transaccion_id, payment_intent_id, idempotency_key, estado, intentos, created_at, updated_at) "
                + "VALUES (NULL, ?, ?, 'PENDIENTE', 0, ?, ?)",
            paymentIntentId, "refund:pi:" + paymentIntentId, OffsetDateTime.now(), OffsetDateTime.now());

        Event event = eventoStripe(eventId, "payment_intent.succeeded");

        assertThatThrownBy(() -> procesadorEventosWebhookService.procesarEvento(
            event, escenario.comprador().getId(), escenario.publicacion().getId(), paymentIntentId)
        ).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM processed_stripe_events WHERE event_id = ?", Integer.class, eventId
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM stripe_refund_outbox WHERE idempotency_key = ?",
            Integer.class, "refund:pi:" + paymentIntentId
        )).isOne();
    }

    /**
     * Test del criterio (e) de PHA16TSK02 (sin cambios): {@code charge.refunded} y
     * {@code payment_intent.payment_failed} no crean orden de refund ni transacción y no
     * modifican el stock.
     */
    @Test
    @DisplayName("charge.refunded y payment_failed no crean orden ni transaccion")
    void procesarEvento_RefundYFailed_NoCreanOrdenNiTransaccion() {
        String sufijo = Long.toString(System.nanoTime());
        EscenarioCompra escenario = crearEscenarioCompra("noop-" + sufijo, 5);

        String refundedEventId = "evt_test_noop_refunded_" + sufijo;
        String refundedPi = "pi_test_noop_refunded_" + sufijo;
        String failedEventId = "evt_test_noop_failed_" + sufijo;
        String failedPi = "pi_test_noop_failed_" + sufijo;

        assertThat(procesadorEventosWebhookService.procesarEvento(
            eventoStripe(refundedEventId, "charge.refunded"),
            escenario.comprador().getId(), escenario.publicacion().getId(), refundedPi)).isNull();
        assertThat(procesadorEventosWebhookService.procesarEvento(
            eventoStripe(failedEventId, "payment_intent.payment_failed"),
            escenario.comprador().getId(), escenario.publicacion().getId(), failedPi)).isNull();

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM stripe_refund_outbox WHERE payment_intent_id IN (?, ?)",
            Integer.class, refundedPi, failedPi
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM transacciones WHERE publicacion_id = ?",
            Integer.class, escenario.publicacion().getId()
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT stock FROM publicaciones WHERE id = ?", Integer.class, escenario.publicacion().getId()
        )).isEqualTo(5);
    }

    /**
     * Escenario mínimo de compra para los tests del refund del perdedor de la carrera de stock
     * (PHA16TSK02, Story 5 de spec.md): vendedor, comprador y publicación aprobada con el
     * stock inicial pedido.
     *
     * @param vendedor vendedor dueño de la publicación
     * @param comprador comprador distinto del vendedor
     * @param publicacion publicación aprobada con el stock inicial pedido
     */
    private record EscenarioCompra(Usuario vendedor, Usuario comprador, Publicacion publicacion) {
    }

    /**
     * Crea un vendedor, un comprador y una publicación aprobada con el stock inicial indicado,
     * con datos únicos por sufijo para aislar cada test de los demás.
     *
     * @param sufijo sufijo único para correos, nombres y descripción (evita colisiones entre tests)
     * @param stock stock inicial de la publicación creada
     * @return escenario con las tres entidades ya persistidas
     */
    private EscenarioCompra crearEscenarioCompra(String sufijo, int stock) {
        Usuario vendedor = usuarioRepository.save(
            new Usuario("vendedor-refund-" + sufijo + "@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now())
        );
        Usuario comprador = usuarioRepository.save(
            new Usuario("comprador-refund-" + sufijo + "@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now())
        );

        Categoria categoria = categoriaRepository.save(new Categoria("Categoría refund " + sufijo));
        Subcategoria subcategoria = subcategoriaRepository.save(new Subcategoria(categoria, "Subcategoría refund " + sufijo));

        Publicacion publicacion = publicacionService.crearPublicacion(
            vendedor.getId(), categoria.getId(), subcategoria.getId(), 150000L, stock,
            "Artículo para test de refund " + sufijo, null
        );
        publicacionService.cambiarEstado(publicacion.getId(), EstadoPublicacion.APROBADA, null);
        return new EscenarioCompra(vendedor, comprador, publicacion);
    }

    /**
     * Construye un evento Stripe mínimo para los tests (solo se usan {@code event.getId()} y
     * {@code event.getType()}, como en los tests preexistentes de esta clase).
     *
     * @param eventId identificador del evento de Stripe
     * @param tipo tipo del evento (p. ej. {@code payment_intent.succeeded})
     * @return evento con id y tipo fijados
     */
    private static Event eventoStripe(String eventId, String tipo) {
        Event event = new Event();
        event.setId(eventId);
        event.setType(tipo);
        return event;
    }
}
