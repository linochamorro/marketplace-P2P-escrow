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
 * Test de migración Flyway para la creación de la tabla {@code transacciones} (V7).
 *
 * <p>Verifica que la migración V7 crea la tabla, índices, claves foráneas y restricciones de check
 * correctamente sobre PostgreSQL real mediante Testcontainers (Story 5, spec.md — máquina de
 * estados de transacción):
 * <ul>
 *   <li>Restricción de {@code estado} a los 8 valores exactos de la máquina de estados de
 *       transacción ({@code reservada}, {@code enviado}, {@code entregado}, {@code recibido},
 *       {@code recibido_sin_respuesta}, {@code disputa}, {@code cancelada}, {@code completada})
 *       — test previo obligatorio de la fila PHA03TSK01 en tasks.md.</li>
 *   <li>Default de {@code estado} a {@code reservada}: toda transacción nace en estado
 *       {@code reservada} (Story 5, spec.md; plan.md, flujo de compra y reserva de stock PHA03).</li>
 *   <li>Claves foráneas válidas a {@code usuarios(id)} (comprador) y {@code publicaciones(id)},
 *       y rechazo de FKs inválidas hacia entidades inexistentes (DataIntegrityViolationException).</li>
 *   <li>Almacenamiento de {@code precio_snapshot} como entero en centavos (columna BIGINT),
 *       consistente con el principio 3 de constitution (dinero como enteros, nunca punto flotante).</li>
 *   <li>Nullabilidad de los timestamps de la máquina de estados: {@code fecha_reservada} NOT NULL
 *       (toda transacción nace {@code reservada}); {@code fecha_enviado} y {@code fecha_entregado}
 *       NULL hasta que la transacción transiciona a {@code enviado}/{@code entregado}.</li>
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
class FlywayMigrationV7Tests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Verifica que la tabla {@code transacciones} rechaza un valor de {@code estado} ajeno a la
     * máquina de estados de transacción definida en spec.md (8 estados exactos).
     */
    @Test
    @DisplayName("Debe rechazar un estado ajeno a la máquina de estados de transacción")
    void testRechazaEstadoInvalido() {
        Long compradorId = crearUsuarioTest("comprador-estado-invalido@example.com");
        Long publicacionId = crearPublicacionParaVendedor("vendedor-estado-invalido@example.com");

        assertThatThrownBy(() ->
            jdbcTemplate.update(
                "INSERT INTO transacciones (estado, comprador_id, publicacion_id, precio_snapshot, fecha_reservada) VALUES (?, ?, ?, ?, ?)",
                "INVALIDO", compradorId, publicacionId, 10000L, OffsetDateTime.now()
            )
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Verifica que la tabla {@code transacciones} acepta los 8 valores válidos de {@code estado}
     * de la máquina de estados de transacción de spec.md, uno por cada inserción.
     */
    @Test
    @DisplayName("Debe aceptar los 8 valores válidos de la máquina de estados de transacción")
    void testAceptaLosOchoEstadosValidos() {
        Long compradorId = crearUsuarioTest("comprador-estados-validos@example.com");
        Long publicacionId = crearPublicacionParaVendedor("vendedor-estados-validos@example.com");

        String[] estadosValidos = {
            "reservada", "enviado", "entregado", "recibido",
            "recibido_sin_respuesta", "disputa", "cancelada", "completada"
        };
        for (String estado : estadosValidos) {
            jdbcTemplate.update(
                "INSERT INTO transacciones (estado, comprador_id, publicacion_id, precio_snapshot, fecha_reservada) VALUES (?, ?, ?, ?, ?)",
                estado, compradorId, publicacionId, 10000L, OffsetDateTime.now()
            );
        }

        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM transacciones WHERE publicacion_id = ?", Integer.class, publicacionId
        );
        assertThat(count).isEqualTo(8);
    }

    /**
     * Verifica que la clave foránea {@code comprador_id} rechace un usuario inexistente.
     */
    @Test
    @DisplayName("Debe rechazar comprador_id inexistente por FK constraint")
    void testRechazaCompradorInexistente() {
        Long publicacionId = crearPublicacionParaVendedor("vendedor-comprador-inexistente@example.com");

        assertThatThrownBy(() ->
            jdbcTemplate.update(
                "INSERT INTO transacciones (estado, comprador_id, publicacion_id, precio_snapshot, fecha_reservada) VALUES (?, ?, ?, ?, ?)",
                "reservada", 999999L, publicacionId, 10000L, OffsetDateTime.now()
            )
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Verifica que la clave foránea {@code publicacion_id} rechace una publicación inexistente.
     */
    @Test
    @DisplayName("Debe rechazar publicacion_id inexistente por FK constraint")
    void testRechazaPublicacionInexistente() {
        Long compradorId = crearUsuarioTest("comprador-publicacion-inexistente@example.com");

        assertThatThrownBy(() ->
            jdbcTemplate.update(
                "INSERT INTO transacciones (estado, comprador_id, publicacion_id, precio_snapshot, fecha_reservada) VALUES (?, ?, ?, ?, ?)",
                "reservada", compradorId, 999999L, 10000L, OffsetDateTime.now()
            )
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Verifica que una transacción válida se inserta con {@code estado} por defecto
     * {@code reservada}, que {@code precio_snapshot} se almacena como entero en centavos
     * (columna BIGINT), que {@code fecha_reservada} queda poblada, y que
     * {@code fecha_enviado}/{@code fecha_entregado} quedan NULL mientras la transacción
     * permanece en {@code reservada}.
     */
    @Test
    @DisplayName("Debe insertar transacción válida con estado default 'reservada', snapshot entero en centavos y timestamps coherentes")
    void testInsertaTransaccionValidaConSnapshotEnteroYDefaultReservada() {
        Long compradorId = crearUsuarioTest("comprador-valido@example.com");
        Long publicacionId = crearPublicacionParaVendedor("vendedor-valido@example.com");

        jdbcTemplate.update(
            "INSERT INTO transacciones (comprador_id, publicacion_id, precio_snapshot, fecha_reservada) VALUES (?, ?, ?, ?)",
            compradorId, publicacionId, 150000L, OffsetDateTime.now()
        );

        Long transaccionId = jdbcTemplate.queryForObject(
            "SELECT id FROM transacciones WHERE comprador_id = ? AND publicacion_id = ?", Long.class, compradorId, publicacionId
        );

        // Estado por defecto: toda transacción nace en 'reservada' (Story 5, spec.md).
        String estado = jdbcTemplate.queryForObject(
            "SELECT estado FROM transacciones WHERE id = ?", String.class, transaccionId
        );
        assertThat(estado).isEqualTo("reservada");

        // Dinero como enteros (constitución, principio 3): el snapshot se lee de vuelta como
        // Long en centavos, sin pérdida de valor.
        Long precioSnapshot = jdbcTemplate.queryForObject(
            "SELECT precio_snapshot FROM transacciones WHERE id = ?", Long.class, transaccionId
        );
        assertThat(precioSnapshot).isEqualTo(150000L);

        // La columna es BIGINT en el esquema real (no NUMERIC/flotante) — verificación directa
        // contra information_schema.
        String dataType = jdbcTemplate.queryForObject(
            "SELECT data_type FROM information_schema.columns WHERE table_name = 'transacciones' AND column_name = 'precio_snapshot'",
            String.class
        );
        assertThat(dataType).isEqualTo("bigint");

        // Nullabilidad de la máquina de estados: fecha_reservada poblada al nacer en 'reservada';
        // fecha_enviado/fecha_entregado NULL hasta transicionar a 'enviado'/'entregado'.
        OffsetDateTime fechaReservada = jdbcTemplate.queryForObject(
            "SELECT fecha_reservada FROM transacciones WHERE id = ?", OffsetDateTime.class, transaccionId
        );
        assertThat(fechaReservada).isNotNull();

        OffsetDateTime fechaEnviado = jdbcTemplate.queryForObject(
            "SELECT fecha_enviado FROM transacciones WHERE id = ?", OffsetDateTime.class, transaccionId
        );
        assertThat(fechaEnviado).isNull();

        OffsetDateTime fechaEntregado = jdbcTemplate.queryForObject(
            "SELECT fecha_entregado FROM transacciones WHERE id = ?", OffsetDateTime.class, transaccionId
        );
        assertThat(fechaEntregado).isNull();
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
            vendedorId, categoriaId, subcategoriaId, 150000L, 10, "aprobada", "Publicación de prueba para transacciones"
        );
        return jdbcTemplate.queryForObject("SELECT id FROM publicaciones WHERE usuario_id = ?", Long.class, vendedorId);
    }
}
