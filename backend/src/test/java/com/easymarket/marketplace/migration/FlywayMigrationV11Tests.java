package com.easymarket.marketplace.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
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
 * Test de migración Flyway para la protección append-only del ledger incorporada en V11.
 *
 * <p>Verifica sobre PostgreSQL real mediante Testcontainers que {@code movimientos_saldo}
 * acepta una inserción con sus referencias válidas y que el trigger del ledger rechaza tanto
 * {@code UPDATE} como {@code DELETE} con {@link DataIntegrityViolationException} y SQLSTATE
 * {@code 23514}, conforme a PHA04TSK23 y al principio 2 de {@code constitution.md}.</p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=test-secret-key-32-chars-minimum-ok"
})
class FlywayMigrationV11Tests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Verifica que el ledger sigue admitiendo un movimiento enlazado a una transacción y vendedor
     * existentes antes de validar que sus filas persistidas son inmutables.
     */
    @Test
    @DisplayName("Debe insertar un movimiento de saldo con referencias válidas")
    void testInsertaMovimientoSaldoConReferenciasValidas() {
        Long movimientoId = crearMovimientoTest("insercion");

        assertThat(jdbcTemplate.queryForObject(
            "SELECT monto FROM movimientos_saldo WHERE id = ?", Long.class, movimientoId
        )).isEqualTo(125000L);
    }

    /**
     * Verifica que PostgreSQL rechaza con la excepción de integridad y SQLSTATE definidos toda
     * actualización de un movimiento ya persistido en el ledger.
     */
    @Test
    @DisplayName("Debe rechazar UPDATE sobre un movimiento append-only con SQLSTATE 23514")
    void testRechazaUpdateSobreMovimientoAppendOnly() {
        Long movimientoId = crearMovimientoTest("update");

        assertThatThrownBy(() -> jdbcTemplate.update(
            "UPDATE movimientos_saldo SET monto = ? WHERE id = ?", 999999L, movimientoId
        )).isInstanceOf(DataIntegrityViolationException.class)
            .hasRootCauseInstanceOf(PSQLException.class)
            .extracting(throwable -> ((PSQLException) throwable.getCause()).getSQLState())
            .isEqualTo("23514");
    }

    /**
     * Verifica que PostgreSQL rechaza con la excepción de integridad y SQLSTATE definidos toda
     * eliminación de un movimiento ya persistido en el ledger.
     */
    @Test
    @DisplayName("Debe rechazar DELETE sobre un movimiento append-only con SQLSTATE 23514")
    void testRechazaDeleteSobreMovimientoAppendOnly() {
        Long movimientoId = crearMovimientoTest("delete");

        assertThatThrownBy(() -> jdbcTemplate.update(
            "DELETE FROM movimientos_saldo WHERE id = ?", movimientoId
        )).isInstanceOf(DataIntegrityViolationException.class)
            .hasRootCauseInstanceOf(PSQLException.class)
            .extracting(throwable -> ((PSQLException) throwable.getCause()).getSQLState())
            .isEqualTo("23514");
    }

    /**
     * Crea un movimiento de saldo de prueba con sus referencias obligatorias existentes.
     *
     * @param sufijo valor único para los datos auxiliares del movimiento.
     * @return ID del movimiento persistido.
     */
    private Long crearMovimientoTest(String sufijo) {
        Long vendedorId = crearUsuarioTest("vendedor-v11-" + sufijo + "@example.com");
        Long transaccionId = crearTransaccionConVendedorExistente(
            "comprador-v11-" + sufijo + "@example.com", vendedorId, sufijo
        );
        return jdbcTemplate.queryForObject(
            "INSERT INTO movimientos_saldo (transaccion_id, vendedor_id, monto, created_at) "
                + "VALUES (?, ?, ?, ?) RETURNING id",
            Long.class, transaccionId, vendedorId, 125000L, OffsetDateTime.now()
        );
    }

    /**
     * Crea un usuario de prueba en {@code usuarios}.
     *
     * @param email email único del usuario de prueba.
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
     * Crea una transacción reservada de prueba para un vendedor existente.
     *
     * @param compradorEmail email único del comprador.
     * @param vendedorId ID del vendedor dueño de la publicación asociada.
     * @param sufijo valor único para los datos auxiliares de la publicación.
     * @return ID de la transacción creada.
     */
    private Long crearTransaccionConVendedorExistente(String compradorEmail, Long vendedorId, String sufijo) {
        Long compradorId = crearUsuarioTest(compradorEmail);
        Long categoriaId = crearCategoriaTest("Categoría V11 " + sufijo);
        Long subcategoriaId = crearSubcategoriaTest(categoriaId, "Subcategoría V11 " + sufijo);
        Long publicacionId = crearPublicacionParaVendedor(vendedorId, categoriaId, subcategoriaId);
        jdbcTemplate.update(
            "INSERT INTO transacciones (estado, comprador_id, publicacion_id, precio_snapshot, fecha_reservada) "
                + "VALUES (?, ?, ?, ?, ?)",
            "reservada", compradorId, publicacionId, 10000L, OffsetDateTime.now()
        );
        return jdbcTemplate.queryForObject(
            "SELECT id FROM transacciones WHERE comprador_id = ? AND publicacion_id = ?",
            Long.class, compradorId, publicacionId
        );
    }

    /**
     * Crea una categoría de prueba.
     *
     * @param nombre nombre único de la categoría.
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
     * @param nombre nombre único de la subcategoría dentro de la categoría.
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
     * @param categoriaId ID de la categoría de la publicación.
     * @param subcategoriaId ID de la subcategoría de la publicación.
     * @return ID de la publicación creada.
     */
    private Long crearPublicacionParaVendedor(Long vendedorId, Long categoriaId, Long subcategoriaId) {
        jdbcTemplate.update(
            "INSERT INTO publicaciones (usuario_id, categoria_id, subcategoria_id, precio, stock, estado, descripcion) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
            vendedorId, categoriaId, subcategoriaId, 150000L, 10, "aprobada", "Publicación de prueba V11"
        );
        return jdbcTemplate.queryForObject(
            "SELECT id FROM publicaciones WHERE usuario_id = ?", Long.class, vendedorId
        );
    }
}
