package com.easymarket.marketplace.controller;

import com.easymarket.marketplace.model.Categoria;
import com.easymarket.marketplace.model.EstadoPublicacion;
import com.easymarket.marketplace.model.IdempotencyKey;
import com.easymarket.marketplace.model.Publicacion;
import com.easymarket.marketplace.model.Rol;
import com.easymarket.marketplace.model.Subcategoria;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.CategoriaRepository;
import com.easymarket.marketplace.repository.IdempotencyKeyRepository;
import com.easymarket.marketplace.repository.NotificacionRepository;
import com.easymarket.marketplace.repository.ProcessedStripeEventRepository;
import com.easymarket.marketplace.repository.PublicacionRepository;
import com.easymarket.marketplace.repository.SubcategoriaRepository;
import com.easymarket.marketplace.repository.TransaccionRepository;
import com.easymarket.marketplace.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración TDD para el endpoint REST {@code POST /webhooks/stripe}
 * (PHA03TSK10, Story 5, spec.md; Constitution principio 4 enmendado).
 *
 * <p>Test previo obligatorio de la fila en tasks.md: <strong>"firma HMAC manual determinista
 * (patrón TSK07): firma inválida → 400 sin tocar base de datos; firma válida → transición
 * correcta"</strong>.</p>
 *
 * <p><strong>Estrategia de firma:</strong> la firma HMAC-SHA256 se computa manualmente con el
 * mismo algoritmo que usa el SDK stripe-java internamente (patrón de
 * {@code WebhookVerificationServiceTests}, PHA03TSK07), sobre {@code timestamp + "." + payload},
 * con el webhook secret {@code whsec_FAKE_FOR_CONTEXT_TEST} (consistente con
 * {@code application-test.yml}). NO se usa Stripe CLI: el test es determinista, sin dependencia
 * de CLI/puertos/secret de sesión (decisión de plan.md, sección "Resolución de IDs de negocio en
 * el webhook (PHA03TSK10)").</p>
 *
 * <p><strong>Resolución de IDs de negocio:</strong> {@code comprador_id} y {@code publicacion_id}
 * se leen de la <b>metadata del PaymentIntent</b> dentro del {@code data.object} del evento
 * (decisión confirmada de plan.md 2026-08-03): el fixture construye el payload del evento
 * {@code payment_intent.succeeded} con esa metadata, y el endpoint debe extraerla para orquestar
 * {@code ProcesadorEventosWebhookService}.</p>
 *
 * <p><strong>Cobertura del criterio de aceptación:</strong>
 * <ol>
 *   <li>Firma inválida → HTTP 400 y la BD queda intacta (ni transacción ni fila en
 *       {@code processed_stripe_events}).</li>
 *   <li>Firma válida para {@code payment_intent.succeeded} → HTTP 200; existe exactamente UNA
 *       {@code Transaccion} en estado {@code reservada} con {@code comprador_id},
 *       {@code publicacion_id} y {@code precio_snapshot} correctos; el stock decrementó; la
 *       {@code IdempotencyKey} quedó vinculada ({@code transaccion_id} poblado).</li>
 *   <li>Evento repetido (mismo {@code eventId}) → HTTP 200 y no duplica efectos (una sola
 *       transacción; una sola fila en {@code processed_stripe_events}).</li>
 * </ol>
 * </p>
 *
 * <p><strong>Orden de limpieza del {@code setUp} (fix PHA15TSK04-L07):</strong> el flujo
 * COMPRA_CONFIRMADA (TSK04) inserta filas en {@code notificaciones} con
 * {@code transaccion_id}/{@code publicacion_id}; el {@code setUp} elimina
 * {@code notificaciones} antes que {@code transacciones} y {@code publicaciones} para no violar
 * {@code fk_notificaciones_transaccion} (V14) ni {@code fk_notificaciones_publicacion} (V20).</p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=test-secret-key-32-chars-minimum-ok",
    "stripe.webhook-secret=whsec_FAKE_FOR_CONTEXT_TEST"
})
class StripeWebhookControllerIntegrationTests {

    /** Secret de prueba consistente con {@code application-test.yml} — los payloads se firman con este valor. */
    private static final String WEBHOOK_SECRET = "whsec_FAKE_FOR_CONTEXT_TEST";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private CategoriaRepository categoriaRepository;

    @Autowired
    private SubcategoriaRepository subcategoriaRepository;

    @Autowired
    private PublicacionRepository publicacionRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private NotificacionRepository notificacionRepository;

    @Autowired
    private TransaccionRepository transaccionRepository;

    @Autowired
    private ProcessedStripeEventRepository processedStripeEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    private Usuario vendedor;
    private Usuario comprador;
    private Categoria categoria;
    private Subcategoria subcategoria;

    /**
     * Prepara el entorno de cada prueba: levanta {@link MockMvc} con la cadena de seguridad de
     * Spring y deja la base de datos vacía antes de crear los fixtures compartidos (vendedor,
     * comprador, categoría y subcategoría).
     *
     * <p><strong>Orden de limpieza (fix PHA15TSK04-L07):</strong> las filas de
     * {@code notificaciones} se eliminan PRIMERO porque referencian a {@code transacciones}
     * ({@code fk_notificaciones_transaccion}, V14) y a {@code publicaciones}
     * ({@code fk_notificaciones_publicacion}, V20), y ninguna otra tabla las referencia; las
     * demás tablas se borran en orden inverso a sus FKs salientes (p. ej. {@code idempotency_keys}
     * antes que {@code transacciones}), de modo que ninguna fila hija sobreviva a su tabla padre.</p>
     */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        // Limpieza en orden inverso a las FKs: processed_stripe_events e idempotency_keys no
        // tienen dependencias salientes; idempotency_keys apunta a transacciones
        // (fk_idempotency_keys_transaccion), por lo que debe borrarse ANTES que las transacciones.
        // Las notificaciones referencian transacciones (fk_notificaciones_transaccion, V14) y
        // publicaciones (fk_notificaciones_publicacion, V20) y nada las referencia, por lo que se
        // borran PRIMERO (fix PHA15TSK04-L07: el flujo COMPRA_CONFIRMADA de TSK04 inserta
        // notificaciones con transaccion_id y si sobrevivían al deleteAll de transacciones la FK
        // violaba en este setUp).
        notificacionRepository.deleteAll();
        processedStripeEventRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();
        transaccionRepository.deleteAll();
        publicacionRepository.deleteAll();
        subcategoriaRepository.deleteAll();
        categoriaRepository.deleteAll();
        usuarioRepository.deleteAll();

        vendedor = usuarioRepository.save(new Usuario(
                "vendedor.webhook@easymarket.com",
                "hash",
                Rol.USUARIO,
                0L,
                ZonedDateTime.now(ZoneId.of("America/Lima"))
        ));

        comprador = usuarioRepository.save(new Usuario(
                "comprador.webhook@easymarket.com",
                "hash",
                Rol.USUARIO,
                0L,
                ZonedDateTime.now(ZoneId.of("America/Lima"))
        ));

        categoria = categoriaRepository.save(new Categoria("Vehículos"));
        subcategoria = subcategoriaRepository.save(new Subcategoria(categoria, "Autos"));
    }

    /**
     * Crea una publicación aprobada con el precio (centavos) y stock dados.
     *
     * @param precio precio en centavos (entero, principio 3)
     * @param stock  stock disponible (entero)
     * @return la publicación persistida
     */
    private Publicacion guardarPublicacionAprobada(long precio, int stock) {
        Publicacion publicacion = new Publicacion(vendedor, categoria, subcategoria, precio, stock, "Vehículo en venta");
        publicacion.setEstado(EstadoPublicacion.APROBADA);
        return publicacionRepository.save(publicacion);
    }

    /**
     * Construye el cuerpo JSON crudo de un evento {@code payment_intent.succeeded} con la
     * metadata del PaymentIntent conteniendo los IDs de negocio (decisión de plan.md 2026-08-03).
     *
     * @param eventId          ID del evento de Stripe ({@code evt_...})
     * @param paymentIntentId  ID del PaymentIntent ({@code pi_...})
     * @param compradorId      ID del usuario comprador (va en la metadata)
     * @param publicacionId    ID de la publicación comprada (va en la metadata)
     * @return el payload JSON como String (sin deserializar)
     */
    private String construirPayloadSucceeded(String eventId, String paymentIntentId, Long compradorId, Long publicacionId) {
        return "{\"id\":\"" + eventId + "\","
                + "\"object\":\"event\","
                + "\"type\":\"payment_intent.succeeded\","
                + "\"data\":{\"object\":{"
                + "\"id\":\"" + paymentIntentId + "\","
                + "\"object\":\"payment_intent\","
                + "\"amount\":2500000,"
                + "\"currency\":\"pen\","
                + "\"status\":\"succeeded\","
                + "\"metadata\":{"
                + "\"project\":\"easymarket\","
                + "\"comprador_id\":\"" + compradorId + "\","
                + "\"publicacion_id\":\"" + publicacionId + "\""
                + "}}}}";
    }

    /**
     * Construye el cuerpo JSON crudo de un evento {@code charge.refunded}, cuyo
     * {@code data.object} es un objeto tipo {@code charge} (NO un PaymentIntent).
     *
     * <p>Este payload es el que usa el refinamiento 2026-08-03 para verificar que un evento
     * firmado válidamente pero sin metadata de negocio (el refund opera sobre un Charge) recibe
     * ack 200 y se delega en el procesador idempotente, sin reintentos infinitos de Stripe.</p>
     *
     * @param eventId  ID del evento de Stripe ({@code evt_...})
     * @param chargeId ID del Charge de Stripe ({@code ch_...})
     * @return el payload JSON como String (sin deserializar)
     */
    private String construirPayloadChargeRefunded(String eventId, String chargeId) {
        return "{\"id\":\"" + eventId + "\","
                + "\"object\":\"event\","
                + "\"type\":\"charge.refunded\","
                + "\"data\":{\"object\":{"
                + "\"id\":\"" + chargeId + "\","
                + "\"object\":\"charge\","
                + "\"amount\":2500000,"
                + "\"currency\":\"pen\""
                + "}}}";
    }

    /**
     * Construye el cuerpo JSON crudo de un evento {@code payment_intent.succeeded} SIN metadata
     * de negocio (sin {@code comprador_id}/{@code publicacion_id}).
     *
     * <p>Usado por el refinamiento 2026-08-03 para confirmar que la rama {@code succeeded} sigue
     * exigiendo metadata válida: si falta → 400 (evento crítico no processable).</p>
     *
     * @param eventId         ID del evento de Stripe ({@code evt_...})
     * @param paymentIntentId ID del PaymentIntent ({@code pi_...})
     * @return el payload JSON como String (sin deserializar)
     */
    private String construirPayloadSucceededSinMetadata(String eventId, String paymentIntentId) {
        return "{\"id\":\"" + eventId + "\","
                + "\"object\":\"event\","
                + "\"type\":\"payment_intent.succeeded\","
                + "\"data\":{\"object\":{"
                + "\"id\":\"" + paymentIntentId + "\","
                + "\"object\":\"payment_intent\","
                + "\"amount\":2500000,"
                + "\"currency\":\"pen\","
                + "\"status\":\"succeeded\""
                + "}}}";
    }

    /**
     * Calcula el HMAC-SHA256 de un mensaje con una clave secreta, en hexadecimal minúsculas,
     * exactamente como el SDK de Stripe lo computa para verificar {@code Stripe-Signature}
     * (patrón replicado de {@code WebhookVerificationServiceTests}, PHA03TSK07).
     *
     * @param data el mensaje a firmar (formato {@code timestamp + "." + payload})
     * @param key  el webhook secret (prefijo {@code whsec_})
     * @return la firma HMAC-SHA256 en hexadecimal minúsculas
     * @throws Exception si el algoritmo HMAC no está disponible
     */
    private String computeHmacSha256(String data, String key) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(secretKeySpec);
        byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : hmacBytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /**
     * Genera el header {@code Stripe-Signature} con firma HMAC-SHA256 manual determinista sobre
     * {@code timestamp + "." + payload}, con un timestamp dentro de la ventana de tolerancia de
     * 5 minutos del SDK de Stripe.
     *
     * @param payload cuerpo crudo del webhook (JSON)
     * @param secret  webhook secret usado para firmar
     * @return header completo en formato {@code t=<timestamp>,v1=<hmac>}
     * @throws Exception si falla el cálculo del HMAC
     */
    private String generarHeaderFirmaValida(String payload, String secret) throws Exception {
        long timestamp = System.currentTimeMillis() / 1000L;
        String signedPayload = timestamp + "." + payload;
        String signature = computeHmacSha256(signedPayload, secret);
        return "t=" + timestamp + ",v1=" + signature;
    }

    /**
     * Verifica el criterio de aceptación 1: firma inválida → 400 sin tocar la base de datos.
     *
     * <p>Se envía un payload con un header {@code Stripe-Signature} cuya firma {@code v1} es
     * arbitraria (no corresponde al HMAC real del payload). El endpoint debe responder 400 sin
     * detalle, sin crear transacción y sin registrar la fila en {@code processed_stripe_events}.</p>
     */
    @Test
    @DisplayName("Firma inválida retorna 400 sin tocar base de datos")
    void recibirWebhook_FirmaInvalida_Retorna400SinTocarBaseDeDatos() throws Exception {
        Publicacion publicacion = guardarPublicacionAprobada(2500000L, 3);
        String eventId = "evt_invalida_1";
        String paymentIntentId = "pi_invalida_1";
        String payload = construirPayloadSucceeded(eventId, paymentIntentId, comprador.getId(), publicacion.getId());

        // Header con firma arbitraria (HMAC incorrecto) — debe ser rechazado antes de tocar la BD
        String sigHeader = "t=" + (System.currentTimeMillis() / 1000L) + ",v1=firma_invalida_arbitraria";

        mockMvc.perform(post("/webhooks/stripe")
                        .header("Stripe-Signature", sigHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());

        // La BD debe permanecer intacta: ni transacción ni evento procesado
        Integer transaccionesCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transacciones WHERE publicacion_id = ?", Integer.class, publicacion.getId());
        assertThat(transaccionesCount).isZero();
        assertThat(processedStripeEventRepository.existsById(eventId)).isFalse();
    }

    /**
     * Verifica el criterio de aceptación 2: firma válida para {@code payment_intent.succeeded} →
     * 200 y transición correcta.
     *
     * <p>El webhook debe orquestar {@code ProcesadorEventosWebhookService} con los IDs extraídos
     * de la metadata del PaymentIntent, creando exactamente UNA {@code Transaccion} en estado
     * {@code reservada} con {@code comprador_id}/{@code publicacion_id}/{@code precio_snapshot}
     * correctos, decrementando el stock y poblando el {@code transaccion_id} de la
     * {@code IdempotencyKey} vinculada al PaymentIntent.</p>
     */
    @Test
    @DisplayName("Firma válida payment_intent.succeeded retorna 200 y crea transacción reservada")
    void recibirWebhook_FirmaValidaSucceeded_Retorna200YTransicionCorrecta() throws Exception {
        Publicacion publicacion = guardarPublicacionAprobada(2500000L, 3);
        String idempotencyKey = UUID.randomUUID().toString();
        String eventId = "evt_succeeded_1";
        String paymentIntentId = "pi_succeeded_1";

        // Vincular la idempotency key al PaymentIntent (fase "pago en vuelo", plan.md) para que
        // el procesador pueble el transaccion_id al llegar el webhook
        idempotencyKeyRepository.save(new IdempotencyKey(idempotencyKey, paymentIntentId, ZonedDateTime.now()));

        String payload = construirPayloadSucceeded(eventId, paymentIntentId, comprador.getId(), publicacion.getId());
        String sigHeader = generarHeaderFirmaValida(payload, WEBHOOK_SECRET);

        mockMvc.perform(post("/webhooks/stripe")
                        .header("Stripe-Signature", sigHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        // Exactamente una transacción 'reservada' con IDs y snapshot de precio correctos
        Integer totalTransacciones = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transacciones WHERE publicacion_id = ?", Integer.class, publicacion.getId());
        assertThat(totalTransacciones).isEqualTo(1);

        Long compradorIdTransaccion = jdbcTemplate.queryForObject(
                "SELECT comprador_id FROM transacciones WHERE publicacion_id = ?", Long.class, publicacion.getId());
        assertThat(compradorIdTransaccion).isEqualTo(comprador.getId());

        Long publicacionIdTransaccion = jdbcTemplate.queryForObject(
                "SELECT publicacion_id FROM transacciones WHERE publicacion_id = ?", Long.class, publicacion.getId());
        assertThat(publicacionIdTransaccion).isEqualTo(publicacion.getId());

        String estado = jdbcTemplate.queryForObject(
                "SELECT estado FROM transacciones WHERE publicacion_id = ?", String.class, publicacion.getId());
        assertThat(estado).isEqualTo("reservada");

        Long precioSnapshot = jdbcTemplate.queryForObject(
                "SELECT precio_snapshot FROM transacciones WHERE publicacion_id = ?", Long.class, publicacion.getId());
        assertThat(precioSnapshot).isEqualTo(2500000L);

        // El stock debe haber decrementado de 3 a 2
        Publicacion publicacionRecargada = publicacionRepository.findById(publicacion.getId()).orElseThrow();
        assertThat(publicacionRecargada.getStock()).isEqualTo(2);

        // La idempotency key quedó vinculada a la transacción creada
        IdempotencyKey keyVinculada = idempotencyKeyRepository.findById(idempotencyKey).orElseThrow();
        assertThat(keyVinculada.getTransaccionId()).isNotNull();
    }

    /**
     * Verifica el criterio de aceptación 3 (idempotencia): el mismo {@code eventId} reenviado
     * por Stripe (misma firma válida) retorna 200 y no duplica efectos.
     *
     * <p>Tras dos envíos del mismo evento: exactamente una transacción y una sola fila en
     * {@code processed_stripe_events} (la guardia {@code existsById} del procesador descarta el
     * segundo envío — Constitution principio 4 enmendado).</p>
     */
    @Test
    @DisplayName("Evento repetido con mismo eventId retorna 200 y no duplica efectos")
    void recibirWebhook_EventoRepetido_Retorna200YNoDuplicaEfectos() throws Exception {
        Publicacion publicacion = guardarPublicacionAprobada(1500000L, 2);
        String idempotencyKey = UUID.randomUUID().toString();
        String eventId = "evt_repetido_1";
        String paymentIntentId = "pi_repetido_1";

        idempotencyKeyRepository.save(new IdempotencyKey(idempotencyKey, paymentIntentId, ZonedDateTime.now()));

        String payload = construirPayloadSucceeded(eventId, paymentIntentId, comprador.getId(), publicacion.getId());
        String sigHeader = generarHeaderFirmaValida(payload, WEBHOOK_SECRET);

        // Primer envío — crea la transacción
        mockMvc.perform(post("/webhooks/stripe")
                        .header("Stripe-Signature", sigHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        // Segundo envío con el mismo eventId y la misma firma válida — debe ser descartado
        mockMvc.perform(post("/webhooks/stripe")
                        .header("Stripe-Signature", sigHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        // Una sola transacción y una sola fila de idempotencia de evento
        Integer totalTransacciones = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transacciones WHERE publicacion_id = ?", Integer.class, publicacion.getId());
        assertThat(totalTransacciones).isEqualTo(1);

        Integer stockFinal = jdbcTemplate.queryForObject(
                "SELECT stock FROM publicaciones WHERE id = ?", Integer.class, publicacion.getId());
        assertThat(stockFinal).isEqualTo(1);
        assertThat(processedStripeEventRepository.count()).isEqualTo(1);
    }

    /**
     * Verifica el refinamiento 2026-08-03 (decisión de Lino): un evento con firma válida pero
     * cuyo {@code data.object} NO es un PaymentIntent (ej. {@code charge.refunded} con objeto
     * tipo {@code Charge}) debe recibir <strong>ack 200</strong> y delegarse en el procesador
     * idempotente — NO 400 — para evitar reintentos infinitos de Stripe (backoff exponencial,
     * Constitution principio 4 enmendado).
     *
     * <p>El procesador registra el {@code eventId} en {@code processed_stripe_events} y loguea
     * sin efecto para este tipo de evento; no se crea ninguna transacción.</p>
     */
    @Test
    @DisplayName("charge.refunded con firma válida retorna 200 sin crear transacción")
    void recibirWebhook_ChargeRefundedFirmaValida_Retorna200SinCrearTransaccion() throws Exception {
        Publicacion publicacion = guardarPublicacionAprobada(2500000L, 3);
        String eventId = "evt_refunded_1";
        String chargeId = "ch_refunded_1";
        String payload = construirPayloadChargeRefunded(eventId, chargeId);
        String sigHeader = generarHeaderFirmaValida(payload, WEBHOOK_SECRET);

        mockMvc.perform(post("/webhooks/stripe")
                        .header("Stripe-Signature", sigHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        // No se crea transacción, pero el evento queda registrado como procesado (idempotencia)
        Integer transaccionesCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transacciones WHERE publicacion_id = ?", Integer.class, publicacion.getId());
        assertThat(transaccionesCount).isZero();
        assertThat(processedStripeEventRepository.existsById(eventId)).isTrue();
    }

    /**
     * Verifica el refinamiento 2026-08-03 (regla 1 conservada): la rama {@code payment_intent.succeeded}
     * sigue siendo crítica — si el PaymentIntent no tiene metadata de negocio válida
     * ({@code comprador_id}/{@code publicacion_id} ausentes), el endpoint responde 400 sin tocar
     * la base de datos (evento no processable).
     */
    @Test
    @DisplayName("payment_intent.succeeded sin metadata válida retorna 400 sin tocar BD")
    void recibirWebhook_SucceededSinMetadata_Retorna400SinTocarBaseDeDatos() throws Exception {
        Publicacion publicacion = guardarPublicacionAprobada(2500000L, 3);
        String eventId = "evt_sin_metadata_1";
        String paymentIntentId = "pi_sin_metadata_1";
        String payload = construirPayloadSucceededSinMetadata(eventId, paymentIntentId);
        String sigHeader = generarHeaderFirmaValida(payload, WEBHOOK_SECRET);

        mockMvc.perform(post("/webhooks/stripe")
                        .header("Stripe-Signature", sigHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());

        Integer transaccionesCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transacciones WHERE publicacion_id = ?", Integer.class, publicacion.getId());
        assertThat(transaccionesCount).isZero();
        assertThat(processedStripeEventRepository.existsById(eventId)).isFalse();
    }
}
