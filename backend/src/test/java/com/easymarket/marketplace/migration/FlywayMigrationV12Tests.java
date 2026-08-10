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
 * Test de migración Flyway para la outbox durable de reembolsos Stripe incorporada en V12.
 *
 * <p>Verifica sobre PostgreSQL real mediante Testcontainers los criterios de PHA04TSK24:
 * {@code stripe_refund_outbox} admite una referencia válida a {@code transacciones}, rechaza
 * referencias inválidas y duplicados de transacción o clave de idempotencia, restringe el estado
 * exactamente a {@code PENDIENTE}/{@code SOLICITADO}, y preserva el contador de reintentos no
 * negativo con su valor por defecto. Cubre el contrato de la outbox definido en la sección
 * "Reembolsos Stripe por cancelación" de {@code plan.md}.</p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=test-secret-key-32-chars-minimum-ok"
})
class FlywayMigrationV12Tests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Verifica que una orden de reembolso puede referir una transacción existente y conserva los
     * valores por defecto y timestamps operativos definidos por la migración.
     */
    @Test
    @DisplayName("Debe insertar una orden con FK válida, reintentos por defecto y timestamps")
    void testInsertaOrdenConFkValidaYValoresOperativosPorDefecto() {
        Long transaccionId = crearTransaccionTest("fk-valida");

        Long ordenId = insertarOrden(transaccionId, "pi_fk_valida", "refund:" + transaccionId, "PENDIENTE", null);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT intentos FROM stripe_refund_outbox WHERE id = ?", Integer.class, ordenId
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT created_at FROM stripe_refund_outbox WHERE id = ?", OffsetDateTime.class, ordenId
        )).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT updated_at FROM stripe_refund_outbox WHERE id = ?", OffsetDateTime.class, ordenId
        )).isNotNull();
    }

    /**
     * Verifica que la FK de la outbox no permite crear una orden para una transacción inexistente.
     */
    @Test
    @DisplayName("Debe rechazar transaccion_id inexistente por FK")
    void testRechazaTransaccionInexistentePorFk() {
        assertThatThrownBy(() -> insertarOrden(999999L, "pi_fk_invalida", "refund:fk-invalida", "PENDIENTE", null))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Verifica que solo puede existir una orden de reembolso por transacción cancelada.
     */
    @Test
    @DisplayName("Debe rechazar una segunda orden para la misma transacción")
    void testRechazaTransaccionDuplicada() {
        Long transaccionId = crearTransaccionTest("transaccion-duplicada");
        insertarOrden(transaccionId, "pi_transaccion_duplicada_1", "refund:" + transaccionId, "PENDIENTE", null);

        assertThatThrownBy(() -> insertarOrden(
            transaccionId, "pi_transaccion_duplicada_2", "refund:duplicada-alterna", "SOLICITADO", 1
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Verifica que una clave de idempotencia no puede asociarse a dos órdenes distintas.
     */
    @Test
    @DisplayName("Debe rechazar idempotency_key duplicada")
    void testRechazaClaveIdempotenciaDuplicada() {
        Long primeraTransaccionId = crearTransaccionTest("clave-duplicada-1");
        Long segundaTransaccionId = crearTransaccionTest("clave-duplicada-2");
        insertarOrden(primeraTransaccionId, "pi_clave_duplicada_1", "refund:clave-compartida", "PENDIENTE", null);

        assertThatThrownBy(() -> insertarOrden(
            segundaTransaccionId, "pi_clave_duplicada_2", "refund:clave-compartida", "SOLICITADO", 2
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Verifica que los dos estados operativos permitidos se persisten y que cualquier otro estado
     * es rechazado por la restricción de esquema.
     */
    @Test
    @DisplayName("Debe admitir solo estados PENDIENTE y SOLICITADO")
    void testAdmiteExactamenteEstadosDefinidos() {
        Long pendienteId = insertarOrden(
            crearTransaccionTest("estado-pendiente"), "pi_estado_pendiente", "refund:estado-pendiente", "PENDIENTE", 0
        );
        Long solicitadoId = insertarOrden(
            crearTransaccionTest("estado-solicitado"), "pi_estado_solicitado", "refund:estado-solicitado", "SOLICITADO", 3
        );

        assertThat(jdbcTemplate.queryForObject(
            "SELECT estado FROM stripe_refund_outbox WHERE id = ?", String.class, pendienteId
        )).isEqualTo("PENDIENTE");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT estado FROM stripe_refund_outbox WHERE id = ?", String.class, solicitadoId
        )).isEqualTo("SOLICITADO");
        assertThatThrownBy(() -> insertarOrden(
            crearTransaccionTest("estado-invalido"), "pi_estado_invalido", "refund:estado-invalido", "FALLIDO", 0
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Verifica que el contador de reintentos acepta cero y valores positivos, pero no negativos.
     */
    @Test
    @DisplayName("Debe admitir reintentos no negativos y rechazar negativos")
    void testAdmiteReintentosNoNegativos() {
        Long ordenId = insertarOrden(
            crearTransaccionTest("reintentos-positivos"), "pi_reintentos_positivos", "refund:reintentos-positivos", "PENDIENTE", 4
        );

        assertThat(jdbcTemplate.queryForObject(
            "SELECT intentos FROM stripe_refund_outbox WHERE id = ?", Integer.class, ordenId
        )).isEqualTo(4);
        assertThatThrownBy(() -> insertarOrden(
            crearTransaccionTest("reintentos-negativos"), "pi_reintentos_negativos", "refund:reintentos-negativos", "PENDIENTE", -1
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Inserta una orden de reembolso con los datos operativos explícitos o con el default de
     * {@code intentos} cuando el parámetro correspondiente es {@code null}.
     *
     * @param transaccionId ID de la transacción que origina el reembolso.
     * @param paymentIntentId ID persistido del PaymentIntent de Stripe.
     * @param idempotencyKey clave persistida para idempotencia frente a Stripe.
     * @param estado estado operativo solicitado para la orden.
     * @param intentos cantidad de intentos, o {@code null} para usar el default de esquema.
     * @return ID de la orden de outbox creada.
     */
    private Long insertarOrden(Long transaccionId, String paymentIntentId, String idempotencyKey, String estado, Integer intentos) {
        if (intentos == null) {
            return jdbcTemplate.queryForObject(
                "INSERT INTO stripe_refund_outbox (transaccion_id, payment_intent_id, idempotency_key, estado, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
                Long.class, transaccionId, paymentIntentId, idempotencyKey, estado, OffsetDateTime.now(), OffsetDateTime.now()
            );
        }
        return jdbcTemplate.queryForObject(
            "INSERT INTO stripe_refund_outbox (transaccion_id, payment_intent_id, idempotency_key, estado, intentos, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id",
            Long.class, transaccionId, paymentIntentId, idempotencyKey, estado, intentos, OffsetDateTime.now(), OffsetDateTime.now()
        );
    }

    /**
     * Crea una transacción reservada con todas sus referencias previas para probar la FK de la
     * outbox sin depender de entidades JPA ni de servicios de dominio.
     *
     * @param sufijo valor único para los datos auxiliares de la transacción.
     * @return ID de la transacción creada.
     */
    private Long crearTransaccionTest(String sufijo) {
        Long vendedorId = crearUsuarioTest("vendedor-v12-" + sufijo + "@example.com");
        Long compradorId = crearUsuarioTest("comprador-v12-" + sufijo + "@example.com");
        Long categoriaId = crearCategoriaTest("Categoría V12 " + sufijo);
        Long subcategoriaId = crearSubcategoriaTest(categoriaId, "Subcategoría V12 " + sufijo);
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
     * Crea una publicación aprobada para el vendedor indicado.
     *
     * @param vendedorId ID del usuario dueño de la publicación.
     * @param categoriaId ID de la categoría de la publicación.
     * @param subcategoriaId ID de la subcategoría de la publicación.
     * @param sufijo valor único para la descripción de prueba.
     * @return ID de la publicación creada.
     */
    private Long crearPublicacionTest(Long vendedorId, Long categoriaId, Long subcategoriaId, String sufijo) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO publicaciones (usuario_id, categoria_id, subcategoria_id, precio, stock, estado, descripcion) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id",
            Long.class, vendedorId, categoriaId, subcategoriaId, 150000L, 10, "aprobada", "Publicación de prueba V12 " + sufijo
        );
    }
}
