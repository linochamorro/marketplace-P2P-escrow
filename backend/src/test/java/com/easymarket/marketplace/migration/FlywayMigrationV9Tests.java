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
 * Test de migración Flyway para los datos de ciclo de vida incorporados en V9.
 *
 * <p>Verifica sobre PostgreSQL real mediante Testcontainers los criterios de la fila PHA04TSK01:
 * los campos opcionales de entrega y cancelación de {@code transacciones} aceptan {@code NULL};
 * {@code movimientos_saldo} conserva referencias válidas y rechaza las inexistentes mediante
 * {@link DataIntegrityViolationException}; y {@code notificaciones} persiste y devuelve una fila
 * con sus campos obligatorios. Cubre Stories 6b, 7, 7b y 12 de {@code spec.md}.</p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=test-secret-key-32-chars-minimum-ok"
})
class FlywayMigrationV9Tests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Verifica que una transacción recién creada puede conservar en {@code NULL} tanto la
     * descripción opcional de prueba de entrega como el motivo de cancelación hasta que una
     * transición posterior los requiera.
     */
    @Test
    @DisplayName("Debe aceptar NULL en descripcion_prueba_entrega y motivo_cancelacion")
    void testAceptaCamposOpcionalesNulos() {
        Long transaccionId = crearTransaccionTest(
            "comprador-v9-campos-nulos@example.com", "vendedor-v9-campos-nulos@example.com"
        );

        String descripcionPruebaEntrega = jdbcTemplate.queryForObject(
            "SELECT descripcion_prueba_entrega FROM transacciones WHERE id = ?", String.class, transaccionId
        );
        String motivoCancelacion = jdbcTemplate.queryForObject(
            "SELECT motivo_cancelacion FROM transacciones WHERE id = ?", String.class, transaccionId
        );

        assertThat(descripcionPruebaEntrega).isNull();
        assertThat(motivoCancelacion).isNull();
    }

    /**
     * Verifica que {@code movimientos_saldo} almacena un movimiento de crédito válido enlazado a
     * la transacción que lo originó y al vendedor que recibe el saldo.
     */
    @Test
    @DisplayName("Debe insertar y leer un movimiento de saldo con transacción y vendedor válidos")
    void testInsertaYLeeMovimientoSaldoConReferenciasValidas() {
        Long vendedorId = crearUsuarioTest("vendedor-v9-movimiento-valido@example.com");
        Long transaccionId = crearTransaccionConVendedorExistente(
            "comprador-v9-movimiento-valido@example.com", vendedorId
        );

        jdbcTemplate.update(
            "INSERT INTO movimientos_saldo (transaccion_id, vendedor_id, monto, created_at) VALUES (?, ?, ?, ?)",
            transaccionId, vendedorId, 125000L, OffsetDateTime.now()
        );

        Long transaccionLeida = jdbcTemplate.queryForObject(
            "SELECT transaccion_id FROM movimientos_saldo WHERE vendedor_id = ?", Long.class, vendedorId
        );
        Long vendedorLeido = jdbcTemplate.queryForObject(
            "SELECT vendedor_id FROM movimientos_saldo WHERE transaccion_id = ?", Long.class, transaccionId
        );
        Long montoLeido = jdbcTemplate.queryForObject(
            "SELECT monto FROM movimientos_saldo WHERE transaccion_id = ?", Long.class, transaccionId
        );

        assertThat(transaccionLeida).isEqualTo(transaccionId);
        assertThat(vendedorLeido).isEqualTo(vendedorId);
        assertThat(montoLeido).isEqualTo(125000L);
    }

    /**
     * Verifica que {@code movimientos_saldo.transaccion_id} no puede apuntar a una transacción
     * inexistente, preservando la trazabilidad de auditoría del movimiento a su origen.
     */
    @Test
    @DisplayName("Debe rechazar transaccion_id inexistente en movimientos_saldo por FK")
    void testRechazaTransaccionInexistenteEnMovimientoSaldo() {
        Long vendedorId = crearUsuarioTest("vendedor-v9-transaccion-inexistente@example.com");

        assertThatThrownBy(() -> jdbcTemplate.update(
            "INSERT INTO movimientos_saldo (transaccion_id, vendedor_id, monto, created_at) VALUES (?, ?, ?, ?)",
            999999L, vendedorId, 10000L, OffsetDateTime.now()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Verifica que {@code movimientos_saldo.vendedor_id} no puede apuntar a un usuario inexistente,
     * preservando que cada crédito del ledger pertenece a un vendedor real.
     */
    @Test
    @DisplayName("Debe rechazar vendedor_id inexistente en movimientos_saldo por FK")
    void testRechazaVendedorInexistenteEnMovimientoSaldo() {
        Long transaccionId = crearTransaccionTest(
            "comprador-v9-vendedor-inexistente@example.com", "vendedor-v9-vendedor-inexistente@example.com"
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
            "INSERT INTO movimientos_saldo (transaccion_id, vendedor_id, monto, created_at) VALUES (?, ?, ?, ?)",
            transaccionId, 999999L, 10000L, OffsetDateTime.now()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Verifica una inserción y lectura real de {@code notificaciones}, incluidos los defaults
     * obligatorios de lectura y fecha de creación definidos por la migración.
     */
    @Test
    @DisplayName("Debe insertar y leer una notificación con sus campos obligatorios")
    void testInsertaYLeeNotificacion() {
        Long usuarioId = crearUsuarioTest("usuario-v9-notificacion@example.com");

        jdbcTemplate.update(
            "INSERT INTO notificaciones (usuario_id, mensaje, tipo) VALUES (?, ?, ?)",
            usuarioId, "Tu transacción sigue abierta.", "AVISO_TRANSACCION_ABIERTA"
        );

        String mensaje = jdbcTemplate.queryForObject(
            "SELECT mensaje FROM notificaciones WHERE usuario_id = ?", String.class, usuarioId
        );
        String tipo = jdbcTemplate.queryForObject(
            "SELECT tipo FROM notificaciones WHERE usuario_id = ?", String.class, usuarioId
        );
        Boolean leida = jdbcTemplate.queryForObject(
            "SELECT leida FROM notificaciones WHERE usuario_id = ?", Boolean.class, usuarioId
        );
        OffsetDateTime createdAt = jdbcTemplate.queryForObject(
            "SELECT created_at FROM notificaciones WHERE usuario_id = ?", OffsetDateTime.class, usuarioId
        );

        assertThat(mensaje).isEqualTo("Tu transacción sigue abierta.");
        assertThat(tipo).isEqualTo("AVISO_TRANSACCION_ABIERTA");
        assertThat(leida).isFalse();
        assertThat(createdAt).isNotNull();
    }

    /**
     * Crea un usuario de prueba en {@code usuarios}.
     *
     * @param email Email único del usuario de prueba.
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
     * Crea una categoría de prueba.
     *
     * @param nombre Nombre único de la categoría.
     * @return ID de la categoría creada.
     */
    private Long crearCategoriaTest(String nombre) {
        jdbcTemplate.update("INSERT INTO categorias (nombre) VALUES (?)", nombre);
        return jdbcTemplate.queryForObject("SELECT id FROM categorias WHERE nombre = ?", Long.class, nombre);
    }

    /**
     * Crea una subcategoría de prueba para una categoría existente.
     *
     * @param categoriaId ID de la categoría padre.
     * @param nombre Nombre único de la subcategoría dentro de la categoría.
     * @return ID de la subcategoría creada.
     */
    private Long crearSubcategoriaTest(Long categoriaId, String nombre) {
        jdbcTemplate.update("INSERT INTO subcategorias (categoria_id, nombre) VALUES (?, ?)", categoriaId, nombre);
        return jdbcTemplate.queryForObject(
            "SELECT id FROM subcategorias WHERE categoria_id = ? AND nombre = ?", Long.class, categoriaId, nombre
        );
    }

    /**
     * Crea una publicación aprobada para un vendedor existente.
     *
     * @param vendedorId ID del usuario dueño de la publicación.
     * @param sufijo Sufijo único para los nombres auxiliares de la publicación.
     * @return ID de la publicación creada.
     */
    private Long crearPublicacionParaVendedor(Long vendedorId, String sufijo) {
        Long categoriaId = crearCategoriaTest("Categoría V9 " + sufijo);
        Long subcategoriaId = crearSubcategoriaTest(categoriaId, "Subcategoría V9 " + sufijo);
        jdbcTemplate.update(
            "INSERT INTO publicaciones (usuario_id, categoria_id, subcategoria_id, precio, stock, estado, descripcion) VALUES (?, ?, ?, ?, ?, ?, ?)",
            vendedorId, categoriaId, subcategoriaId, 150000L, 10, "aprobada", "Publicación de prueba V9"
        );
        return jdbcTemplate.queryForObject(
            "SELECT id FROM publicaciones WHERE usuario_id = ?", Long.class, vendedorId
        );
    }

    /**
     * Crea una transacción reservada de prueba con un vendedor nuevo.
     *
     * @param compradorEmail Email único del comprador.
     * @param vendedorEmail Email único del vendedor.
     * @return ID de la transacción creada.
     */
    private Long crearTransaccionTest(String compradorEmail, String vendedorEmail) {
        Long vendedorId = crearUsuarioTest(vendedorEmail);
        return crearTransaccionConVendedorExistente(compradorEmail, vendedorId);
    }

    /**
     * Crea una transacción reservada de prueba para un vendedor existente.
     *
     * @param compradorEmail Email único del comprador.
     * @param vendedorId ID del vendedor dueño de la publicación asociada.
     * @return ID de la transacción creada.
     */
    private Long crearTransaccionConVendedorExistente(String compradorEmail, Long vendedorId) {
        Long compradorId = crearUsuarioTest(compradorEmail);
        Long publicacionId = crearPublicacionParaVendedor(vendedorId, compradorEmail);
        jdbcTemplate.update(
            "INSERT INTO transacciones (estado, comprador_id, publicacion_id, precio_snapshot, fecha_reservada) VALUES (?, ?, ?, ?, ?)",
            "reservada", compradorId, publicacionId, 10000L, OffsetDateTime.now()
        );
        return jdbcTemplate.queryForObject(
            "SELECT id FROM transacciones WHERE comprador_id = ? AND publicacion_id = ?", Long.class, compradorId, publicacionId
        );
    }
}
