package com.easymarket.marketplace.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test de migración Flyway para la creación de las tablas de idempotencia (V8):
 * {@code processed_stripe_events} e {@code idempotency_keys}.
 *
 * <p>Verifica que la migración V8 crea ambas tablas, sus claves primarias, la clave foránea a
 * {@code transacciones(id)} y la semántica de unicidad que ambas tablas deben garantizar, sobre
 * PostgreSQL real mediante Testcontainers (Story 5, spec.md; constitución, principio 4 enmendado —
 * webhooks idempotentes: "reintentos del proveedor no duplican efectos en el sistema"; plan.md,
 * secciones "Idempotencia de webhooks Stripe", "Idempotencia de compra (doble-submit)" y "Flujo de
 * compra y reserva de stock (PHA03)"):
 * <ul>
 *   <li>Unicidad de {@code processed_stripe_events.event_id}: insertar dos veces el mismo
 *       {@code event_id} falla por violación de la PK — mecanismo de guardia de idempotencia de
 *       webhooks — test previo obligatorio de la fila PHA03TSK02 en tasks.md.</li>
 *   <li>Unicidad de {@code idempotency_keys.key}: insertar dos veces la misma {@code key} falla
 *       por violación de la PK — mecanismo de idempotencia de doble-submit de compra — test
 *       previo obligatorio de la fila PHA03TSK02 en tasks.md.</li>
 *   <li>Clave foránea {@code idempotency_keys.transaccion_id} → {@code transacciones(id)}: rechaza
 *       un {@code transaccion_id} inexistente ({@link DataIntegrityViolationException}).</li>
 *   <li>Nullabilidad de {@code transaccion_id} y {@code payment_intent_id}: acepta valores NULL,
 *       consistente con el flujo PHA03 (plan.md) donde la key nace sin transacción y
 *       {@code transaccion_id} se puebla recién cuando el webhook crea la transacción.</li>
 *   <li>Inserción y lectura de filas válidas en ambas tablas.</li>
 * </ul>
 * </p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=test-secret-key-32-chars-minimum-ok"
})
class FlywayMigrationV8Tests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Verifica que la tabla {@code processed_stripe_events} rechaza un {@code event_id} duplicado:
     * el segundo INSERT del mismo evento viola la clave primaria. Este es el mecanismo de guardia
     * de idempotencia de webhooks (constitución, principio 4 enmendado): los reintentos de Stripe
     * con el mismo {@code event_id} no pueden duplicar efectos en el sistema.
     */
    @Test
    @DisplayName("Debe rechazar un event_id duplicado por constraint de unicidad (PK)")
    void testRechazaEventIdDuplicado() {
        String eventId = "evt_1Oq2LpZTestEventIdDuplicado";

        jdbcTemplate.update(
            "INSERT INTO processed_stripe_events (event_id, processed_at) VALUES (?, ?)",
            eventId, OffsetDateTime.now()
        );

        assertThatThrownBy(() ->
            jdbcTemplate.update(
                "INSERT INTO processed_stripe_events (event_id, processed_at) VALUES (?, ?)",
                eventId, OffsetDateTime.now()
            )
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Verifica que la tabla {@code idempotency_keys} rechaza una {@code key} duplicada: el segundo
     * INSERT de la misma key viola la clave primaria. Este es el mecanismo de idempotencia de
     * doble-submit de compra (plan.md, sección "Idempotencia de compra (doble-submit)"): un segundo
     * POST con la misma {@code Idempotency-Key} no puede crear una segunda compra.
     */
    @Test
    @DisplayName("Debe rechazar una key duplicada por constraint de unicidad (PK)")
    void testRechazaKeyDuplicada() {
        String key = "3f2a1c7e-4b5d-4e6f-8a9b-0c1d2e3f4a5b";

        jdbcTemplate.update(
            "INSERT INTO idempotency_keys (key, timestamp) VALUES (?, ?)",
            key, OffsetDateTime.now()
        );

        assertThatThrownBy(() ->
            jdbcTemplate.update(
                "INSERT INTO idempotency_keys (key, timestamp) VALUES (?, ?)",
                key, OffsetDateTime.now()
            )
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Verifica que la clave foránea {@code idempotency_keys.transaccion_id} →
     * {@code transacciones(id)} rechaza un {@code transaccion_id} que apunta a una transacción
     * inexistente (plan.md: {@code transaccion_id} solo puede poblarse con una transacción
     * realmente creada por el webhook {@code payment_intent.succeeded}).
     */
    @Test
    @DisplayName("Debe rechazar un transaccion_id inexistente por FK constraint")
    void testRechazaTransaccionInexistentePorFk() {
        String key = "5a4b3c2d-1e0f-4a9b-8c7d-6e5f4a3b2c1d";

        assertThatThrownBy(() ->
            jdbcTemplate.update(
                "INSERT INTO idempotency_keys (key, transaccion_id, timestamp) VALUES (?, ?, ?)",
                key, 999999L, OffsetDateTime.now()
            )
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Verifica que {@code idempotency_keys} acepta {@code transaccion_id} y
     * {@code payment_intent_id} en NULL. Es la semántica obligatoria del flujo PHA03 (plan.md,
     * sección "Flujo de compra y reserva de stock (PHA03)"): la key se inserta en
     * {@code POST /compras} antes de que exista transacción, por lo que {@code transaccion_id} es
     * NULL al nacer; {@code payment_intent_id} puede quedar NULL si el servicio registra la key
     * antes de crear el PaymentIntent.
     */
    @Test
    @DisplayName("Debe aceptar transaccion_id y payment_intent_id NULL en idempotency_keys")
    void testAceptaTransaccionYPaymentIntentNulos() {
        String key = "9e8f7a6b-5c4d-4e3f-2a1b-0c9d8e7f6a5b";

        jdbcTemplate.update(
            "INSERT INTO idempotency_keys (key, timestamp) VALUES (?, ?)",
            key, OffsetDateTime.now()
        );

        Long transaccionId = jdbcTemplate.queryForObject(
            "SELECT transaccion_id FROM idempotency_keys WHERE key = ?", Long.class, key
        );
        assertThat(transaccionId).isNull();

        String paymentIntentId = jdbcTemplate.queryForObject(
            "SELECT payment_intent_id FROM idempotency_keys WHERE key = ?", String.class, key
        );
        assertThat(paymentIntentId).isNull();
    }

    /**
     * Verifica que ambas tablas aceptan y devuelven filas válidas: un evento de Stripe con su
     * timestamp de procesamiento, y una key de idempotencia con {@code transaccion_id} y
     * {@code payment_intent_id} poblados (estado "maduro" de una key tras el webhook, opuesto al
     * estado inicial cubierto por {@link #testAceptaTransaccionYPaymentIntentNulos()}).
     */
    @Test
    @DisplayName("Debe insertar y leer filas válidas en ambas tablas de idempotencia")
    void testInsertaFilasValidasEnAmbasTablas() {
        String eventId = "evt_1Oq2LpZTestEventIdValido";
        jdbcTemplate.update(
            "INSERT INTO processed_stripe_events (event_id, processed_at) VALUES (?, ?)",
            eventId, OffsetDateTime.now()
        );
        OffsetDateTime processedAt = jdbcTemplate.queryForObject(
            "SELECT processed_at FROM processed_stripe_events WHERE event_id = ?", OffsetDateTime.class, eventId
        );
        assertThat(processedAt).isNotNull();

        Long transaccionId = crearTransaccionTest("comprador-v8-valido@example.com", "vendedor-v8-valido@example.com");
        String key = "7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";
        String paymentIntentId = "pi_3Oq2LpZTestPaymentIntentValido";
        jdbcTemplate.update(
            "INSERT INTO idempotency_keys (key, transaccion_id, timestamp, payment_intent_id) VALUES (?, ?, ?, ?)",
            key, transaccionId, OffsetDateTime.now(), paymentIntentId
        );

        Long transaccionLeida = jdbcTemplate.queryForObject(
            "SELECT transaccion_id FROM idempotency_keys WHERE key = ?", Long.class, key
        );
        assertThat(transaccionLeida).isEqualTo(transaccionId);

        String paymentIntentLeido = jdbcTemplate.queryForObject(
            "SELECT payment_intent_id FROM idempotency_keys WHERE key = ?", String.class, key
        );
        assertThat(paymentIntentLeido).isEqualTo(paymentIntentId);
    }

    /**
     * Helper para crear un usuario de prueba en la tabla {@code usuarios}.
     *
     * @param email Email único para el usuario (distinto por test, la BD no se limpia entre métodos).
     * @return ID del usuario creado.
     */
    private Long crearUsuarioTest(String email) {
        jdbcTemplate.update(
            "INSERT INTO usuarios (email, password_hash, rol) VALUES (?, ?, ?)",
            email, "$2a$10$abcdefghijklmnopqrstuuu", "USUARIO"
        );
        return jdbcTemplate.queryForObject("SELECT id FROM usuarios WHERE email = ?", Long.class, email);
    }

    /**
     * Helper para crear una categoría de prueba.
     *
     * @param nombre Nombre único de la categoría (distinto por test, la BD no se limpia entre métodos).
     * @return ID de la categoría creada.
     */
    private Long crearCategoriaTest(String nombre) {
        jdbcTemplate.update("INSERT INTO categorias (nombre) VALUES (?)", nombre);
        return jdbcTemplate.queryForObject("SELECT id FROM categorias WHERE nombre = ?", Long.class, nombre);
    }

    /**
     * Helper para crear una subcategoría de prueba.
     *
     * @param categoriaId ID de la categoría padre.
     * @param nombre      Nombre de la subcategoría.
     * @return ID de la subcategoría creada.
     */
    private Long crearSubcategoriaTest(Long categoriaId, String nombre) {
        jdbcTemplate.update("INSERT INTO subcategorias (categoria_id, nombre) VALUES (?, ?)", categoriaId, nombre);
        return jdbcTemplate.queryForObject(
            "SELECT id FROM subcategorias WHERE categoria_id = ? AND nombre = ?", Long.class, categoriaId, nombre
        );
    }

    /**
     * Helper que crea la cadena completa de dependencias de una transacción válida: un vendedor
     * (dueño), una categoría, una subcategoría y una publicación {@code aprobada} de ese vendedor.
     *
     * @param vendedorEmail Email único del vendedor dueño de la publicación (distinto por test).
     * @return ID de la publicación creada.
     */
    private Long crearPublicacionParaVendedor(String vendedorEmail) {
        Long vendedorId = crearUsuarioTest(vendedorEmail);
        Long categoriaId = crearCategoriaTest("Categoría " + vendedorEmail);
        Long subcategoriaId = crearSubcategoriaTest(categoriaId, "Subcategoría " + vendedorEmail);

        jdbcTemplate.update(
            "INSERT INTO publicaciones (usuario_id, categoria_id, subcategoria_id, precio, stock, estado, descripcion) VALUES (?, ?, ?, ?, ?, ?, ?)",
            vendedorId, categoriaId, subcategoriaId, 150000L, 10, "aprobada", "Publicación de prueba para idempotencia"
        );
        return jdbcTemplate.queryForObject("SELECT id FROM publicaciones WHERE usuario_id = ?", Long.class, vendedorId);
    }

    /**
     * Helper que crea una transacción {@code reservada} de prueba (V7), usada para probar que
     * {@code idempotency_keys.transaccion_id} acepta una referencia FK válida.
     *
     * @param compradorEmail Email único del comprador de la transacción (distinto por test).
     * @param vendedorEmail  Email único del vendedor dueño de la publicación (distinto por test).
     * @return ID de la transacción creada.
     */
    private Long crearTransaccionTest(String compradorEmail, String vendedorEmail) {
        Long compradorId = crearUsuarioTest(compradorEmail);
        Long publicacionId = crearPublicacionParaVendedor(vendedorEmail);

        jdbcTemplate.update(
            "INSERT INTO transacciones (estado, comprador_id, publicacion_id, precio_snapshot, fecha_reservada) VALUES (?, ?, ?, ?, ?)",
            "reservada", compradorId, publicacionId, 10000L, OffsetDateTime.now()
        );
        return jdbcTemplate.queryForObject(
            "SELECT id FROM transacciones WHERE comprador_id = ? AND publicacion_id = ?", Long.class, compradorId, publicacionId
        );
    }
}
