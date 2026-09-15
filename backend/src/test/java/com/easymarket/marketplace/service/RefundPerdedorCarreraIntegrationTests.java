package com.easymarket.marketplace.service;

import com.easymarket.marketplace.model.Categoria;
import com.easymarket.marketplace.model.EstadoPublicacion;
import com.easymarket.marketplace.model.Publicacion;
import com.easymarket.marketplace.model.Rol;
import com.easymarket.marketplace.model.StripeRefundOutbox;
import com.easymarket.marketplace.model.Subcategoria;
import com.easymarket.marketplace.model.Transaccion;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.CategoriaRepository;
import com.easymarket.marketplace.repository.SubcategoriaRepository;
import com.easymarket.marketplace.repository.UsuarioRepository;
import com.stripe.exception.ApiException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verificación de integración del ciclo completo del refund del perdedor de la carrera de stock
 * (PHA16TSK03 de {@code tasks.md}, Story 5 de {@code spec.md}; plan.md, sección "PHA16 —
 * Estabilización de producción y seguridad"; constitution principios 1 y 4).
 *
 * <p>Cada test recorre el ciclo real sin tocar producción: escenario con stock agotado
 * determinista (patrón TSK02: stock=1 llevado a 0 por {@code UPDATE} directo, sin concurrencia)
 * → {@code ProcesadorEventosWebhookService.procesarEvento(payment_intent.succeeded)} (orden
 * durable {@code PENDIENTE} de TSK01/TSK02) → {@code StripeRefundOutboxProcessor.procesarPendientes()}
 * real inyectado, con el {@code StripeRefundGateway} mockeado → aserciones sobre la orden, el
 * gateway y la ausencia de efectos colaterales.</p>
 *
 * <p><strong>Mock vs sandbox (decisión declarada):</strong> se usa {@code @MockitoBean
 * StripeRefundGateway} (mock determinista apto para CI, patrón
 * {@code CompraControllerIntegrationTests}): el ciclo verificado es la orquestación durable
 * (selección {@code FOR UPDATE SKIP LOCKED} + una sola llamada con la clave persistida +
 * transición de estado), no la red de Stripe; el camino con clave real ya está cubierto por
 * {@code StripeRefundGatewayIntegrationTests} (sandbox, con skip sin clave). El camino feliz no
 * necesita stub (el mock retorna {@code null} y el procesador marca {@code SOLICITADO} sin
 * inspeccionar el {@code Refund}, patrón de {@code StripeRefundOutboxProcessorTests}); el camino
 * de fallo se simula con {@code thenThrow(new ApiException("Stripe unavailable", null, null,
 * 500, null))}, mismo constructor del test unitario del procesador.</p>
 *
 * <p><strong>Aislamiento entre tests:</strong> cada test usa {@code paymentIntentId} y
 * {@code eventId} únicos (sufijo {@code System.nanoTime()}) y todas las aserciones filtran por
 * ellos; el mock es compartido por el contexto pero cada {@code verify} usa args exactos, por lo
 * que una orden {@code PENDIENTE} residual de otro test (camino de fallo) no contamina ningún
 * conteo.</p>
 *
 * <p>Cero cambios de producción: esta suite solo lee y ejercita componentes existentes. Si algún
 * test revelara un defecto de producción, el loop se detiene y se reporta sin corregir (orden de
 * la fila PHA16TSK03).</p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=test-secret-key-32-chars-minimum-ok"
})
class RefundPerdedorCarreraIntegrationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ProcesadorEventosWebhookService procesadorEventosWebhookService;

    @Autowired
    private StripeRefundOutboxProcessor stripeRefundOutboxProcessor;

    @Autowired
    private StripeRefundOutboxSelectionService stripeRefundOutboxSelectionService;

    @MockitoBean
    private StripeRefundGateway stripeRefundGateway;

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
     * Verifica el criterio de éxito del ciclo (fila PHA16TSK03): stock agotado →
     * {@code payment_intent.succeeded} crea la orden durable {@code PENDIENTE} sin transacción;
     * la selección {@code FOR UPDATE SKIP LOCKED} la recoge explícitamente; la primera corrida
     * del procesador la deja {@code SOLICITADO} (intentos=1, sin error); la segunda corrida no
     * duplica la llamada al gateway (un único {@code crearRefund} con la clave persistida
     * {@code refund:pi:&lt;pi&gt;}); y no existen transacción, movimiento de saldo ni cambio de
     * stock/saldo para ese pago.
     *
     * @throws StripeException declarado porque la verificación del contrato
     *         {@link StripeRefundGateway} puede lanzar esta excepción verificada
     */
    @Test
    @DisplayName("Ciclo de exito: dos corridas producen un unico refund con la clave persistida")
    void cicloExito_DosCorridas_UnicoRefundConMismaClave() throws StripeException {
        String sufijo = Long.toString(System.nanoTime());
        EscenarioCompra escenario = crearEscenarioCompra("ciclo-ok-" + sufijo, 1);
        // Agotamiento determinista del stock (patrón TSK02): el decremento condicional de
        // reservarStock no afectará filas y lanzará StockAgotadoException real (perdedor).
        jdbcTemplate.update("UPDATE publicaciones SET stock = 0 WHERE id = ?", escenario.publicacion().getId());

        String eventId = "evt_test_ciclo_ok_" + sufijo;
        String paymentIntentId = "pi_test_ciclo_ok_" + sufijo;
        String claveEsperada = "refund:pi:" + paymentIntentId;

        Transaccion resultado = procesadorEventosWebhookService.procesarEvento(
            eventoStripe(eventId, "payment_intent.succeeded"),
            escenario.comprador().getId(), escenario.publicacion().getId(), paymentIntentId);
        assertThat(resultado).isNull();

        // La orden sin transacción es seleccionable por findPendientesForUpdateSkipLocked
        // (vía el servicio real de selección): prueba explícita de recogida sin transacción.
        List<StripeRefundOutbox> pendientes = stripeRefundOutboxSelectionService.seleccionarPendientes()
            .stream()
            .filter(orden -> paymentIntentId.equals(orden.getPaymentIntentId()))
            .toList();
        assertThat(pendientes).hasSize(1);
        assertThat(pendientes.get(0).getTransaccion()).isNull();
        assertThat(pendientes.get(0).getIdempotencyKey()).isEqualTo(claveEsperada);
        assertThat(pendientes.get(0).getEstado()).isEqualTo("PENDIENTE");

        // Primera corrida del procesador real (gateway mockeado, éxito sin stub).
        stripeRefundOutboxProcessor.procesarPendientes();

        Map<String, Object> orden = jdbcTemplate.queryForMap(
            "SELECT transaccion_id, idempotency_key, estado, intentos, ultimo_error "
                + "FROM stripe_refund_outbox WHERE payment_intent_id = ?",
            paymentIntentId);
        assertThat(orden.get("transaccion_id")).isNull();
        assertThat(orden.get("idempotency_key")).isEqualTo(claveEsperada);
        assertThat(orden.get("estado")).isEqualTo("SOLICITADO");
        assertThat(((Number) orden.get("intentos")).intValue()).isEqualTo(1);
        assertThat(orden.get("ultimo_error")).isNull();

        // Segunda corrida: la orden ya está SOLICITADO, no se reintenta ni se duplica.
        stripeRefundOutboxProcessor.procesarPendientes();

        Map<String, Object> ordenTrasReintento = jdbcTemplate.queryForMap(
            "SELECT estado, intentos, ultimo_error, idempotency_key "
                + "FROM stripe_refund_outbox WHERE payment_intent_id = ?",
            paymentIntentId);
        assertThat(ordenTrasReintento.get("estado")).isEqualTo("SOLICITADO");
        assertThat(((Number) ordenTrasReintento.get("intentos")).intValue()).isEqualTo(1);
        assertThat(ordenTrasReintento.get("ultimo_error")).isNull();
        assertThat(ordenTrasReintento.get("idempotency_key")).isEqualTo(claveEsperada);
        verify(stripeRefundGateway, times(1)).crearRefund(paymentIntentId, claveEsperada);

        // Sin efectos colaterales para el pago del perdedor: sin transacción, stock intacto
        // en 0 (el procesador no restaura nada), sin movimientos de saldo y saldos en 0.
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM transacciones WHERE publicacion_id = ?",
            Integer.class, escenario.publicacion().getId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT stock FROM publicaciones WHERE id = ?", Integer.class, escenario.publicacion().getId()))
            .isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM movimientos_saldo m JOIN transacciones t ON t.id = m.transaccion_id "
                + "WHERE t.publicacion_id = ?",
            Integer.class, escenario.publicacion().getId())).isZero();
        assertThat(saldoDe(escenario.vendedor().getId())).isZero();
        assertThat(saldoDe(escenario.comprador().getId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM processed_stripe_events WHERE event_id = ?", Integer.class, eventId))
            .isOne();
    }

    /**
     * Verifica el criterio de fallo del ciclo (fila PHA16TSK03): con Stripe indisponible, el
     * procesador conserva la orden {@code PENDIENTE} reintentable con intentos y
     * {@code ultimo_error} registrados; el reintento reutiliza la misma clave persistida
     * (sin duplicar la orden ni cambiar su identidad); y tampoco hay efectos colaterales.
     *
     * @throws StripeException declarado porque el stub del contrato {@link StripeRefundGateway}
     *         lanza esta excepción verificada
     */
    @Test
    @DisplayName("Fallo de Stripe: la orden queda PENDIENTE reintentable con la misma clave")
    void cicloFalloStripe_OrdenReintentableConMismaClave() throws StripeException {
        String sufijo = Long.toString(System.nanoTime());
        EscenarioCompra escenario = crearEscenarioCompra("ciclo-fail-" + sufijo, 1);
        jdbcTemplate.update("UPDATE publicaciones SET stock = 0 WHERE id = ?", escenario.publicacion().getId());

        String eventId = "evt_test_ciclo_fail_" + sufijo;
        String paymentIntentId = "pi_test_ciclo_fail_" + sufijo;
        String claveEsperada = "refund:pi:" + paymentIntentId;

        Transaccion resultado = procesadorEventosWebhookService.procesarEvento(
            eventoStripe(eventId, "payment_intent.succeeded"),
            escenario.comprador().getId(), escenario.publicacion().getId(), paymentIntentId);
        assertThat(resultado).isNull();

        // Fallo de Stripe simulado (mismo constructor ApiException del test unitario del
        // procesador): la orden debe conservarse reintentable.
        when(stripeRefundGateway.crearRefund(paymentIntentId, claveEsperada))
            .thenThrow(new ApiException("Stripe unavailable", null, null, 500, null));

        stripeRefundOutboxProcessor.procesarPendientes();

        Map<String, Object> orden = jdbcTemplate.queryForMap(
            "SELECT transaccion_id, idempotency_key, estado, intentos, ultimo_error "
                + "FROM stripe_refund_outbox WHERE payment_intent_id = ?",
            paymentIntentId);
        assertThat(orden.get("transaccion_id")).isNull();
        assertThat(orden.get("idempotency_key")).isEqualTo(claveEsperada);
        assertThat(orden.get("estado")).isEqualTo("PENDIENTE");
        assertThat(((Number) orden.get("intentos")).intValue()).isEqualTo(1);
        assertThat(orden.get("ultimo_error")).isEqualTo("Stripe unavailable");

        // Reintento: misma clave persistida, un intento más, sigue PENDIENTE reintentable.
        stripeRefundOutboxProcessor.procesarPendientes();

        Map<String, Object> ordenTrasReintento = jdbcTemplate.queryForMap(
            "SELECT idempotency_key, estado, intentos, ultimo_error "
                + "FROM stripe_refund_outbox WHERE payment_intent_id = ?",
            paymentIntentId);
        assertThat(ordenTrasReintento.get("idempotency_key")).isEqualTo(claveEsperada);
        assertThat(ordenTrasReintento.get("estado")).isEqualTo("PENDIENTE");
        assertThat(((Number) ordenTrasReintento.get("intentos")).intValue()).isEqualTo(2);
        assertThat(ordenTrasReintento.get("ultimo_error")).isEqualTo("Stripe unavailable");
        verify(stripeRefundGateway, times(2)).crearRefund(paymentIntentId, claveEsperada);

        // Sin efectos colaterales tampoco en el camino de fallo.
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM transacciones WHERE publicacion_id = ?",
            Integer.class, escenario.publicacion().getId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT stock FROM publicaciones WHERE id = ?", Integer.class, escenario.publicacion().getId()))
            .isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM movimientos_saldo m JOIN transacciones t ON t.id = m.transaccion_id "
                + "WHERE t.publicacion_id = ?",
            Integer.class, escenario.publicacion().getId())).isZero();
        assertThat(saldoDe(escenario.vendedor().getId())).isZero();
        assertThat(saldoDe(escenario.comprador().getId())).isZero();
    }

    /**
     * Verifica el criterio {@code charge.refunded} de la fila PHA16TSK03: el evento no crea orden
     * de refund ni transacción, no modifica el stock, no acredita saldo (sin transacción no puede
     * existir movimiento) y queda marcado como procesado.
     */
    @Test
    @DisplayName("charge.refunded no crea orden ni transaccion y no acredita saldo")
    void chargeRefunded_NoTransicionaNiAcredita() {
        String sufijo = Long.toString(System.nanoTime());
        EscenarioCompra escenario = crearEscenarioCompra("ciclo-noop-" + sufijo, 5);

        String eventId = "evt_test_ciclo_refunded_" + sufijo;
        String paymentIntentId = "pi_test_ciclo_refunded_" + sufijo;

        Transaccion resultado = procesadorEventosWebhookService.procesarEvento(
            eventoStripe(eventId, "charge.refunded"),
            escenario.comprador().getId(), escenario.publicacion().getId(), paymentIntentId);

        assertThat(resultado).isNull();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM stripe_refund_outbox WHERE payment_intent_id = ?",
            Integer.class, paymentIntentId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM transacciones WHERE publicacion_id = ?",
            Integer.class, escenario.publicacion().getId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT stock FROM publicaciones WHERE id = ?", Integer.class, escenario.publicacion().getId()))
            .isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM movimientos_saldo m JOIN transacciones t ON t.id = m.transaccion_id "
                + "WHERE t.publicacion_id = ?",
            Integer.class, escenario.publicacion().getId())).isZero();
        assertThat(saldoDe(escenario.vendedor().getId())).isZero();
        assertThat(saldoDe(escenario.comprador().getId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM processed_stripe_events WHERE event_id = ?", Integer.class, eventId))
            .isOne();
    }

    /**
     * Escenario mínimo de compra para el ciclo del refund del perdedor (patrón TSK02): vendedor,
     * comprador y publicación aprobada con el stock inicial pedido.
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
            new Usuario("vendedor-ciclo-" + sufijo + "@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now())
        );
        Usuario comprador = usuarioRepository.save(
            new Usuario("comprador-ciclo-" + sufijo + "@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now())
        );

        Categoria categoria = categoriaRepository.save(new Categoria("Categoría ciclo " + sufijo));
        Subcategoria subcategoria = subcategoriaRepository.save(new Subcategoria(categoria, "Subcategoría ciclo " + sufijo));

        Publicacion publicacion = publicacionService.crearPublicacion(
            vendedor.getId(), categoria.getId(), subcategoria.getId(), 150000L, stock,
            "Artículo para test del ciclo de refund " + sufijo, null
        );
        publicacionService.cambiarEstado(publicacion.getId(), EstadoPublicacion.APROBADA, null);
        return new EscenarioCompra(vendedor, comprador, publicacion);
    }

    /**
     * Construye un evento Stripe mínimo para los tests (solo se usan {@code event.getId()} y
     * {@code event.getType()}, como en los tests preexistentes del servicio).
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

    /**
     * Lee el saldo disponible contable de un usuario.
     *
     * @param usuarioId identificador del usuario
     * @return saldo disponible en centavos
     */
    private long saldoDe(Long usuarioId) {
        Long saldo = jdbcTemplate.queryForObject(
            "SELECT saldo_disponible FROM usuarios WHERE id = ?", Long.class, usuarioId);
        assertThat(saldo).isNotNull();
        return saldo;
    }
}
