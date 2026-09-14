package com.easymarket.marketplace.migration;

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
 * Test de migración Flyway para la apertura de {@code stripe_refund_outbox} a órdenes sin
 * transacción incorporada en V23 (PHA16TSK01).
 *
 * <p>Verifica sobre PostgreSQL real mediante Testcontainers que la columna
 * {@code transaccion_id} admite {@code NULL} tras el {@code ALTER COLUMN ... DROP NOT NULL},
 * mientras las constraints nombradas en V12 siguen vigentes: {@code uq_stripe_refund_outbox_transaccion}
 * (unicidad por transacción, que en PostgreSQL admite múltiples {@code NULL}), {@code
 * uq_stripe_refund_outbox_idempotency_key} (unicidad de clave de idempotencia) y {@code
 * fk_stripe_refund_outbox_transaccion} (FK a {@code transacciones}). La tabla sigue mutable
 * operativamente (sin trigger append-only, como en V12); el evento canónico de negocio permanece
 * en {@code transaccion_eventos}.</p>
 *
 * <p>Traza: plan.md "PHA16 — Estabilización de producción y seguridad", fila "Esquema de la
 * outbox (V23)"; informe de auditoría 2026-09-13 (hallazgo A1, crítico); Story 5 (spec.md,
 * perdedor de la carrera de stock).</p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=test-secret-key-32-chars-minimum-ok"
})
class FlywayMigrationV23Tests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Verifica el criterio (a): una orden de reembolso sin transacción
     * ({@code transaccion_id = NULL}, caso del perdedor de la carrera de stock) es aceptada, y
     * que la columna queda declarada nullable en el esquema.
     */
    @Test
    @DisplayName("Debe aceptar INSERT con transaccion_id NULL y la columna queda nullable")
    void testAceptaOrdenSinTransaccion() {
        Long ordenId = insertarOrden(null, "pi_sin_transaccion_v23", "refund:pi:pi_sin_transaccion_v23",
            "PENDIENTE", null);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM stripe_refund_outbox WHERE id = ?", Integer.class, ordenId
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT transaccion_id FROM stripe_refund_outbox WHERE id = ?", Long.class, ordenId
        )).isNull();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_name = 'stripe_refund_outbox' AND column_name = 'transaccion_id'",
            String.class
        )).isEqualTo("YES");
    }

    /**
     * Verifica el criterio (b): dos órdenes con la misma {@code idempotency_key} son rechazadas
     * porque el UNIQUE {@code uq_stripe_refund_outbox_idempotency_key} de V12 sigue vigente.
     */
    @Test
    @DisplayName("Debe rechazar idempotency_key duplicada (UNIQUE vigente)")
    void testRechazaClaveIdempotenciaDuplicada() {
        Long primeraTransaccionId = crearTransaccionTest("v23-clave-duplicada-1");
        Long segundaTransaccionId = crearTransaccionTest("v23-clave-duplicada-2");
        insertarOrden(primeraTransaccionId, "pi_v23_clave_dup_1", "refund:v23-clave-compartida",
            "PENDIENTE", null);

        assertThatThrownBy(() -> insertarOrden(
            segundaTransaccionId, "pi_v23_clave_dup_2", "refund:v23-clave-compartida", "SOLICITADO", 2
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Verifica el criterio (c): dos órdenes con la misma {@code transaccion_id} no nula son
     * rechazadas porque la unicidad por transacción {@code uq_stripe_refund_outbox_transaccion}
     * de V12 sigue vigente.
     */
    @Test
    @DisplayName("Debe rechazar una segunda orden para la misma transaccion no nula")
    void testRechazaTransaccionDuplicada() {
        Long transaccionId = crearTransaccionTest("v23-transaccion-duplicada");
        insertarOrden(transaccionId, "pi_v23_transaccion_dup_1", "refund:" + transaccionId,
            "PENDIENTE", null);

        assertThatThrownBy(() -> insertarOrden(
            transaccionId, "pi_v23_transaccion_dup_2", "refund:v23-duplicada-alterna", "SOLICITADO", 1
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Verifica el criterio (d): una orden con {@code transaccion_id} inexistente es rechazada
     * porque la FK {@code fk_stripe_refund_outbox_transaccion} de V12 sigue vigente.
     */
    @Test
    @DisplayName("Debe rechazar transaccion_id inexistente por FK vigente")
    void testRechazaTransaccionInexistentePorFk() {
        assertThatThrownBy(() -> insertarOrden(
            999999L, "pi_v23_fk_invalida", "refund:v23-fk-invalida", "PENDIENTE", null
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Verifica el criterio (e): múltiples órdenes con {@code transaccion_id = NULL} coexisten,
     * porque PostgreSQL admite múltiples {@code NULL} en una columna UNIQUE.
     */
    @Test
    @DisplayName("Debe permitir múltiples órdenes con transaccion_id NULL")
    void testMultiplesOrdenesSinTransaccionCoexisten() {
        Long primeraOrdenId = insertarOrden(null, "pi_v23_nula_1", "refund:pi:pi_v23_nula_1",
            "PENDIENTE", null);
        Long segundaOrdenId = insertarOrden(null, "pi_v23_nula_2", "refund:pi:pi_v23_nula_2",
            "PENDIENTE", null);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM stripe_refund_outbox WHERE id IN (?, ?)",
            Integer.class, primeraOrdenId, segundaOrdenId
        )).isEqualTo(2);
    }

    /**
     * Verifica que las constraints nombradas en V12 conservan sus nombres exactos tras el
     * {@code ALTER COLUMN ... DROP NOT NULL} de V23, consultando el catálogo
     * {@code pg_constraint} de PostgreSQL.
     *
     * @return nada; el test falla por aserción si alguna constraint falta o fue renombrada.
     */
    @Test
    @DisplayName("Debe conservar los nombres uq/fk de transaccion tras el ALTER")
    void testConservaNombresDeConstraintsDeTransaccion() {
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM pg_constraint WHERE conname = 'uq_stripe_refund_outbox_transaccion'",
            Integer.class
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM pg_constraint WHERE conname = 'fk_stripe_refund_outbox_transaccion'",
            Integer.class
        )).isOne();
    }

    /**
     * Inserta una orden de reembolso con los datos operativos explícitos o con el default de
     * {@code intentos} cuando el parámetro correspondiente es {@code null}. A diferencia del
     * helper de V12, {@code transaccionId} admite {@code null} (órdenes del perdedor de la
     * carrera de stock, sin transacción asociada).
     *
     * @param transaccionId ID de la transacción que origina el reembolso, o {@code null} si la
     *     orden no tiene transacción asociada.
     * @param paymentIntentId ID persistido del PaymentIntent de Stripe.
     * @param idempotencyKey clave persistida para idempotencia frente a Stripe.
     * @param estado estado operativo solicitado para la orden.
     * @param intentos cantidad de intentos, o {@code null} para usar el default de esquema.
     * @return ID de la orden de outbox creada.
     */
    private Long insertarOrden(Long transaccionId, String paymentIntentId, String idempotencyKey,
        String estado, Integer intentos) {
        if (intentos == null) {
            return jdbcTemplate.queryForObject(
                "INSERT INTO stripe_refund_outbox (transaccion_id, payment_intent_id, idempotency_key, estado, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
                Long.class, transaccionId, paymentIntentId, idempotencyKey, estado,
                OffsetDateTime.now(), OffsetDateTime.now()
            );
        }
        return jdbcTemplate.queryForObject(
            "INSERT INTO stripe_refund_outbox (transaccion_id, payment_intent_id, idempotency_key, estado, intentos, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id",
            Long.class, transaccionId, paymentIntentId, idempotencyKey, estado, intentos,
            OffsetDateTime.now(), OffsetDateTime.now()
        );
    }

    /**
     * Crea una transacción reservada con todas sus referencias previas para probar la FK y la
     * unicidad por transacción de la outbox sin depender de entidades JPA ni de servicios de
     * dominio.
     *
     * @param sufijo valor único para los datos auxiliares de la transacción.
     * @return ID de la transacción creada.
     */
    private Long crearTransaccionTest(String sufijo) {
        Long vendedorId = crearUsuarioTest("vendedor-v23-" + sufijo + "@example.com");
        Long compradorId = crearUsuarioTest("comprador-v23-" + sufijo + "@example.com");
        Long categoriaId = crearCategoriaTest("Categoría V23 " + sufijo);
        Long subcategoriaId = crearSubcategoriaTest(categoriaId, "Subcategoría V23 " + sufijo);
        Long publicacionId = crearPublicacionTest(vendedorId, categoriaId, subcategoriaId, sufijo);
        return jdbcTemplate.queryForObject(
            "INSERT INTO transacciones (estado, comprador_id, publicacion_id, precio_snapshot, fecha_reservada) "
                + "VALUES (?, ?, ?, ?, ?) RETURNING id",
            Long.class, "reservada", compradorId, publicacionId, 10000L, OffsetDateTime.now()
        );
    }

    /**
     * Crea un usuario de prueba en {@code usuarios}.
     *
     * @param email email único del usuario de prueba.
     * @return ID del usuario creado.
     */
    private Long crearUsuarioTest(String email) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO usuarios (email, password_hash, rol) VALUES (?, ?, ?) RETURNING id",
            Long.class, email, "$2a$10$abcdefghijklmnopqrstuuu", "USUARIO"
        );
    }

    /**
     * Crea una categoría de prueba.
     *
     * @param nombre nombre único de la categoría.
     * @return ID de la categoría creada.
     */
    private Long crearCategoriaTest(String nombre) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO categorias (nombre) VALUES (?) RETURNING id", Long.class, nombre
        );
    }

    /**
     * Crea una subcategoría de prueba para la categoría indicada.
     *
     * @param categoriaId ID de la categoría padre existente.
     * @param nombre nombre único de la subcategoría.
     * @return ID de la subcategoría creada.
     */
    private Long crearSubcategoriaTest(Long categoriaId, String nombre) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO subcategorias (categoria_id, nombre) VALUES (?, ?) RETURNING id",
            Long.class, categoriaId, nombre
        );
    }

    /**
     * Crea una publicación aprobada para el vendedor indicado. No fija
     * {@code codigo_producto}: el trigger {@code tg_publicaciones_codigo_producto} de V22 lo
     * asigna automáticamente al insertar.
     *
     * @param vendedorId ID del usuario dueño de la publicación.
     * @param categoriaId ID de la categoría de la publicación.
     * @param subcategoriaId ID de la subcategoría de la publicación.
     * @param sufijo valor único para la descripción de prueba.
     * @return ID de la publicación creada.
     */
    private Long crearPublicacionTest(Long vendedorId, Long categoriaId, Long subcategoriaId,
        String sufijo) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO publicaciones (usuario_id, categoria_id, subcategoria_id, precio, stock, estado, descripcion) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id",
            Long.class, vendedorId, categoriaId, subcategoriaId, 150000L, 10, "aprobada",
            "Publicación de prueba V23 " + sufijo
        );
    }
}
