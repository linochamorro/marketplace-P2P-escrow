package com.easymarket.marketplace.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifica en PostgreSQL real que V22 crea el identificador de negocio del producto
 * ({@code publicaciones.codigo_producto}) con su secuencia y trigger de generación.
 *
 * <p>La migración agrega la columna {@code codigo_producto} {@code VARCHAR(20)}
 * {@code NOT NULL UNIQUE}, crea la secuencia global {@code seq_codigo_producto} y el trigger
 * {@code tg_publicaciones_codigo_producto} {@code BEFORE INSERT}, y realiza un backfill
 * determinístico de las publicaciones existentes. El contrato que se valida aquí es el persistente:
 * el formato del código, la unicidad de la constraint, el backfill de datos pre-existentes y la
 * cobertura del insert directo del seed del perfil dev.</p>
 *
 * <p>El trigger es la única fuente de generación del código y actúa solo en {@code BEFORE INSERT}
 * (a diferencia de los triggers append-only V10/V11/V15/V17): no bloquea {@code UPDATE} ni
 * {@code DELETE} sobre {@code publicaciones}.</p>
 *
 * <p>Las categorías y subcategorías se crean de forma idempotente ({@code ON CONFLICT DO NOTHING}).
 * El contexto de prueba NO carga {@code db/dev}: su {@code spring.flyway.locations} apunta solo a
 * {@code classpath:db/migration}, por lo que el seed repeatable {@code R__seed_demo.sql} no se
 * aplica en este test. Los helpers {@code crearCategoria} y {@code crearSubcategoria} crean o
 * recuperan (de forma idempotente) categorías y subcategorías de prueba para ser robustos ante un
 * catálogo ya existente o vacío; la idempotencia es robustez, no un requisito impuesto por el seed.</p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=test-secret-key-32-chars-minimum-ok"
})
class FlywayMigrationV22Tests {

    /** Instancia PostgreSQL real que Flyway migra para validar el contrato persistente. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Cliente JDBC usado para comprobar el esquema y el trigger sin lógica de aplicación intermedia. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Verifica que una publicación insertada sin {@code codigo_producto} recibe por el trigger un
     * código con el formato {@code {YYYY}{PREF}{NNNNN}}: cuatro dígitos del año de su
     * {@code created_at}, el prefijo de la categoría normalizado y cinco dígitos del consecutivo.
     * Se usa la categoría {@code Electrónica} para comprobar además que la normalización elimina
     * los acentos ({@code ó → o}) al derivar el prefijo {@code ELE}.
     */
    @Test
    @DisplayName("Debe asignar el código con formato YYYY PREF NNNNN a una publicación sin código")
    void testTriggerAsignaCodigoConFormatoCorrecto() {
        Long usuarioId = crearUsuario("v22-formato@example.com");
        Long categoriaId = crearCategoria("Electrónica");
        Long subcategoriaId = crearSubcategoria(categoriaId, "Auriculares");

        Long publicacionId = insertarPublicacionSinCodigo(
            usuarioId, categoriaId, subcategoriaId, "Publicación formato V22", "2026-05-05T10:00:00+00"
        );

        String codigo = jdbcTemplate.queryForObject(
            "SELECT codigo_producto FROM publicaciones WHERE id = ?", String.class, publicacionId
        );
        assertThat(codigo).matches("^2026ELE[0-9]{5}$");
    }

    /**
     * Verifica que la constraint UNIQUE rechaza la inserción de un segundo producto con un código
     * de producto ya existente, mediante una {@link DataIntegrityViolationException}.
     */
    @Test
    @DisplayName("Debe rechazar un código de producto duplicado con DataIntegrityViolationException")
    void testUniqueRechazaCodigoDuplicado() {
        Long usuarioId = crearUsuario("v22-unique@example.com");
        Long categoriaId = crearCategoria("Definitiva V22");
        Long subcategoriaId = crearSubcategoria(categoriaId, "Unica V22");

        insertarPublicacionConCodigo(
            usuarioId, categoriaId, subcategoriaId, "Primera con código V22", "2026-06-01T10:00:00+00",
            "ZTESTDUP00001"
        );

        assertThatThrownBy(() -> insertarPublicacionConCodigo(
            usuarioId, categoriaId, subcategoriaId, "Segunda con código V22", "2026-06-02T10:00:00+00",
            "ZTESTDUP00001"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Verifica el backfill determinístico sobre una instalación pre-existente detenida en V21:
     * migra un esquema aislado hasta V21, inserta publicaciones históricas, aplica V22 y comprueba
     * que cada fila existente recibió un código determinístico (año de {@code created_at} + prefijo
     * de su categoría + consecutivo en orden por {@code id}) y que {@code setval} dejó la secuencia
     * lista para continuar la numeración con el siguiente producto nuevo.
     *
     * @throws Exception cuando la instalación aislada de Flyway o el harness JDBC fallan
     */
    @Test
    @DisplayName("V22 hace backfill de los productos existentes y deja la secuencia lista para continuar")
    void V22_BackfillAsignaCodigoYSetvalDejaSecuenciaLista() throws Exception {
        String schema = "flyway_v22_harness_" + System.nanoTime();
        Flyway flywayV21 = Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .schemas(schema)
            .defaultSchema(schema)
            .locations("classpath:db/migration")
            .placeholders(Map.of("admin_email", "harness-admin@example.com",
                "admin_password_hash", "harness-hash"))
            .cleanDisabled(false)
            .target("21")
            .load();
        JdbcTemplate isolated = isolatedJdbc(schema);
        Flyway flywayV22 = Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .schemas(schema)
            .defaultSchema(schema)
            .locations("classpath:db/migration")
            .placeholders(Map.of("admin_email", "harness-admin@example.com",
                "admin_password_hash", "harness-hash"))
            .cleanDisabled(false)
            .target("22")
            .load();
        try {
            flywayV21.migrate();

            Long usuarioId = isolated.queryForObject(
                "INSERT INTO usuarios (email, password_hash, rol) VALUES (?, ?, ?) RETURNING id",
                Long.class, "v22-harness@example.com", "hash", "USUARIO"
            );
            Long electronicaId = isolated.queryForObject(
                "INSERT INTO categorias (nombre) VALUES ('Electrónica') RETURNING id", Long.class
            );
            Long hogarId = isolated.queryForObject(
                "INSERT INTO categorias (nombre) VALUES ('Hogar y Decoración') RETURNING id", Long.class
            );
            Long cafeId = isolated.queryForObject(
                "INSERT INTO categorias (nombre) VALUES ('Café y más') RETURNING id", Long.class
            );
            Long subElectronica = crearSubcategoriaHarness(isolated, electronicaId, "Auriculares");
            Long subHogar = crearSubcategoriaHarness(isolated, hogarId, "Muebles");
            Long subCafe = crearSubcategoriaHarness(isolated, cafeId, "Accesorios");

            isolated.update(
                "INSERT INTO publicaciones (usuario_id, categoria_id, subcategoria_id, precio, stock, "
                    + "estado, descripcion, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?::timestamptz)",
                usuarioId, electronicaId, subElectronica, 100L, 1, "aprobada",
                "Histórica Electrónica", "2023-01-01T00:00:00+00"
            );
            isolated.update(
                "INSERT INTO publicaciones (usuario_id, categoria_id, subcategoria_id, precio, stock, "
                    + "estado, descripcion, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?::timestamptz)",
                usuarioId, hogarId, subHogar, 200L, 1, "aprobada",
                "Histórica Hogar", "2024-01-01T00:00:00+00"
            );
            isolated.update(
                "INSERT INTO publicaciones (usuario_id, categoria_id, subcategoria_id, precio, stock, "
                    + "estado, descripcion, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?::timestamptz)",
                usuarioId, cafeId, subCafe, 300L, 1, "aprobada",
                "Histórica Café", "2025-01-01T00:00:00+00"
            );

            flywayV22.migrate();

            assertThat(isolated.queryForObject(
                "SELECT codigo_producto FROM publicaciones WHERE descripcion = ?",
                String.class, "Histórica Electrónica"
            )).isEqualTo("2023ELE00001");
            assertThat(isolated.queryForObject(
                "SELECT codigo_producto FROM publicaciones WHERE descripcion = ?",
                String.class, "Histórica Hogar"
            )).isEqualTo("2024HOG00002");
            assertThat(isolated.queryForObject(
                "SELECT codigo_producto FROM publicaciones WHERE descripcion = ?",
                String.class, "Histórica Café"
            )).isEqualTo("2025CAF00003");

            Long nuevoId = isolated.queryForObject(
                "INSERT INTO publicaciones (usuario_id, categoria_id, subcategoria_id, precio, stock, "
                    + "estado, descripcion, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?::timestamptz) "
                    + "RETURNING id",
                Long.class, usuarioId, electronicaId, subElectronica, 400L, 1, "aprobada",
                "Post backfill V22", "2026-01-01T00:00:00+00"
            );
            assertThat(isolated.queryForObject(
                "SELECT codigo_producto FROM publicaciones WHERE id = ?", String.class, nuevoId
            )).isEqualTo("2026ELE00004");

            flywayV22.validate();
        } finally {
            try {
                flywayV22.clean();
            } finally {
                dropAndAssertHarnessSchema(schema);
            }
        }
    }

    /**
     * Verifica que el insert directo en {@code publicaciones} con el patrón {@code INSERT ... SELECT}
     * usado por el seed del perfil dev (PHA06TSK08) también queda con el código poblado por el
     * trigger, sin necesidad de que el SQL fije el valor.
     */
    @Test
    @DisplayName("Debe poblar el código de las publicaciones insertadas por el seed (INSERT SELECT)")
    void testSeedInsertSelectQuedaPobladoPorTrigger() {
        Long usuarioId = crearUsuario("v22-seed@example.com");
        Long electronicaId = crearCategoria("Electrónica");
        Long modaId = crearCategoria("Moda");
        crearSubcategoria(electronicaId, "Auriculares");
        crearSubcategoria(modaId, "Ropa");

        jdbcTemplate.update(
            "INSERT INTO publicaciones (usuario_id, categoria_id, subcategoria_id, precio, stock, "
                + "estado, descripcion) SELECT u.id, c.id, sub.id, p.precio, p.stock, p.estado, p.descripcion "
                + "FROM (VALUES (?, ?, ?, 18900, 5, 'aprobada'), (?, ?, ?, 15900, 2, 'aprobada')) "
                + "AS p(descripcion, categoria_nombre, subcategoria_nombre, precio, stock, estado) "
                + "JOIN usuarios u ON u.id = ? "
                + "JOIN categorias c ON c.nombre = p.categoria_nombre "
                + "JOIN subcategorias sub ON sub.nombre = p.subcategoria_nombre AND sub.categoria_id = c.id",
            "Auriculares seed V22", "Electrónica", "Auriculares",
            "Camisa seed V22", "Moda", "Ropa",
            usuarioId
        );

        Integer conCodigo = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM publicaciones WHERE descripcion IN ('Auriculares seed V22', 'Camisa seed V22') "
                + "AND codigo_producto ~ '^[0-9]{4}[A-Z]{0,3}[0-9]{5}$'",
            Integer.class
        );
        assertThat(conCodigo).isEqualTo(2);
    }

    /**
     * Crea un usuario de prueba válido.
     *
     * @param email email único del usuario
     * @return identificador del usuario creado
     */
    private Long crearUsuario(String email) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO usuarios (email, password_hash, rol) VALUES (?, ?, ?) RETURNING id",
            Long.class, email, "$2a$10$abcdefghijklmnopqrstuuu", "USUARIO"
        );
    }

    /**
     * Crea (o recupera) una categoría por nombre único. Idempotente: si la categoría ya existe en el
     * catálogo (que puede estar poblado o vacío, sin depender de la semilla dev), no la duplica y
     * devuelve su identificador.
     *
     * @param nombre nombre único de la categoría
     * @return identificador de la categoría existente o recién creada
     */
    private Long crearCategoria(String nombre) {
        jdbcTemplate.update(
            "INSERT INTO categorias (nombre) VALUES (?) ON CONFLICT (nombre) DO NOTHING", nombre
        );
        return jdbcTemplate.queryForObject(
            "SELECT id FROM categorias WHERE nombre = ?", Long.class, nombre
        );
    }

    /**
     * Crea (o recupera) una subcategoría bajo una categoría existente. Idempotente: si la
     * subcategoría ya existe dentro de la categoría, no la duplica y devuelve su identificador.
     *
     * @param categoriaId identificador de la categoría padre
     * @param nombre nombre único de la subcategoría dentro de la categoría
     * @return identificador de la subcategoría existente o recién creada
     */
    private Long crearSubcategoria(Long categoriaId, String nombre) {
        jdbcTemplate.update(
            "INSERT INTO subcategorias (categoria_id, nombre) VALUES (?, ?) "
                + "ON CONFLICT (categoria_id, nombre) DO NOTHING",
            categoriaId, nombre
        );
        return jdbcTemplate.queryForObject(
            "SELECT id FROM subcategorias WHERE categoria_id = ? AND nombre = ?",
            Long.class, categoriaId, nombre
        );
    }

    /**
     * Inserta una publicación sin {@code codigo_producto} para que el trigger lo genere.
     *
     * @param usuarioId identificador del vendedor existente
     * @param categoriaId identificador de la categoría existente
     * @param subcategoriaId identificador de la subcategoría existente
     * @param descripcion descripción de la publicación
     * @param createdAt timestamp {@code created_at} en ISO-8601 con offset
     * @return identificador de la publicación creada
     */
    private Long insertarPublicacionSinCodigo(
        Long usuarioId, Long categoriaId, Long subcategoriaId, String descripcion, String createdAt
    ) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO publicaciones (usuario_id, categoria_id, subcategoria_id, precio, stock, "
                + "estado, descripcion, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?::timestamptz) "
                + "RETURNING id",
            Long.class, usuarioId, categoriaId, subcategoriaId, 100L, 1, "aprobada", descripcion,
            createdAt
        );
    }

    /**
     * Inserta una publicación con un {@code codigo_producto} explícito (el trigger no lo sobrescribe).
     *
     * @param usuarioId identificador del vendedor existente
     * @param categoriaId identificador de la categoría existente
     * @param subcategoriaId identificador de la subcategoría existente
     * @param descripcion descripción de la publicación
     * @param createdAt timestamp {@code created_at} en ISO-8601 con offset
     * @param codigoProducto código de producto a fijar explícitamente
     */
    private void insertarPublicacionConCodigo(
        Long usuarioId, Long categoriaId, Long subcategoriaId, String descripcion, String createdAt,
        String codigoProducto
    ) {
        jdbcTemplate.update(
            "INSERT INTO publicaciones (usuario_id, categoria_id, subcategoria_id, precio, stock, "
                + "estado, descripcion, created_at, codigo_producto) VALUES (?, ?, ?, ?, ?, ?, ?, "
                + "?::timestamptz, ?)",
            usuarioId, categoriaId, subcategoriaId, 100L, 1, "aprobada", descripcion, createdAt,
            codigoProducto
        );
    }

    /**
     * Crea una subcategoría en el esquema aislado del harness.
     *
     * @param isolated cliente JDBC del esquema aislado
     * @param categoriaId identificador de la categoría padre en el esquema aislado
     * @param nombre nombre de la subcategoría
     * @return identificador de la subcategoría creada
     */
    private Long crearSubcategoriaHarness(JdbcTemplate isolated, Long categoriaId, String nombre) {
        return isolated.queryForObject(
            "INSERT INTO subcategorias (categoria_id, nombre) VALUES (?, ?) RETURNING id",
            Long.class, categoriaId, nombre
        );
    }

    /**
     * Crea un cliente JDBC cuyas conexiones resuelven los objetos de la migración en el esquema aislado.
     *
     * @param schema esquema aislado creado para este test
     * @return cliente SQL vinculado al esquema aislado
     */
    private JdbcTemplate isolatedJdbc(String schema) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            postgres.getJdbcUrl() + "?currentSchema=" + schema, postgres.getUsername(), postgres.getPassword());
        return new JdbcTemplate(dataSource);
    }

    /**
     * Elimina el esquema aislado del harness y verifica que no queda ni el esquema ni su historial Flyway.
     *
     * @param schema nombre del esquema generado internamente, perteneciente exclusivamente a este test
     * @throws IllegalArgumentException cuando el nombre no respeta el formato controlado
     */
    private void dropAndAssertHarnessSchema(String schema) {
        String quotedSchema = quoteHarnessSchema(schema);
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + quotedSchema + " CASCADE");

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ?",
            Integer.class, schema
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? "
                + "AND table_name = 'flyway_schema_history'",
            Integer.class, schema
        )).isZero();
    }

    /**
     * Valida y entrecomilla el identificador del esquema antes de incrustarlo en la sentencia DDL.
     *
     * @param schema nombre del esquema generado internamente a entrecomillar
     * @return identificador PostgreSQL entrecomillado, seguro para la sentencia DDL controlada
     * @throws IllegalArgumentException cuando el nombre no es el identificador generado esperado
     */
    private String quoteHarnessSchema(String schema) {
        if (schema == null || !schema.matches("flyway_v22_harness_[0-9]+")) {
            throw new IllegalArgumentException("Unexpected Flyway harness schema name");
        }
        return "\"" + schema.replace("\"", "\"\"") + "\"";
    }
}
