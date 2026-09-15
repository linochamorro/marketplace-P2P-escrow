package com.easymarket.marketplace.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test de integración del seed demostrativo repeatable e idempotente del perfil {@code dev}
 * (PHA06TSK08; plan.md, sección "Integración funcional y operación (PHA06)", fila
 * "Datos demostrativos").
 *
 * <p>Arranca el contexto con {@code @ActiveProfiles("dev")} sobre PostgreSQL real con
 * Testcontainers ({@code @ServiceConnection}), de modo que Flyway ejecuta la migración repeatable
 * {@code R__seed_demo.sql} desde la ubicación {@code classpath:db/dev} configurada en
 * {@code application-dev.yml}. Verifica el criterio de la fila de tarea:
 * <ul>
 *   <li>El seed crea las cuentas {@code vendedor@easymarket.dev} y {@code comprador@easymarket.dev}
 *       con rol {@code USUARIO} y contraseñas válidas (verificadas con el {@link PasswordEncoder}
 *       BCrypt real del sistema).</li>
 *   <li>El seed crea catálogo (categorías/subcategorías) y publicaciones en los estados
 *       navegables por las UIs de PHA06TSK09-14.</li>
 *   <li>El seed es idempotente: re-aplicar el script completo desde classpath no duplica filas.</li>
 *   <li>El seed NO inserta datos del flujo de pago ni de los logs de negocio (las transacciones
 *       solo nacen por el flujo real de Stripe/webhook).</li>
 * </ul>
 * </p>
 *
 * <p>La cláusula "no se activa con perfil prod" se verifica por contrato de configuración en
 * {@code SeedDemoConfigTests} (el perfil prod no define la location del seed, por lo que Flyway
 * hereda únicamente {@code classpath:db/migration}).</p>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("dev")
class SeedDemoDevIntegrationTests {

    /** Email exacto de la cuenta vendedora del contrato (plan.md, "Datos demostrativos"). */
    private static final String EMAIL_VENDEDOR = "vendedor@easymarket.dev";

    /** Email exacto de la cuenta compradora del contrato (plan.md, "Datos demostrativos"). */
    private static final String EMAIL_COMPRADOR = "comprador@easymarket.dev";

    /** Contraseña plana de la cuenta vendedora, definida literalmente en plan.md. */
    private static final String PASSWORD_VENDEDOR = "VendedorPass123!";

    /** Contraseña plana de la cuenta compradora, definida literalmente en plan.md. */
    private static final String PASSWORD_COMPRADOR = "CompradorPass123!";

    /** Número esperado de categorías del catálogo de demostración. */
    private static final int CATEGORIAS_ESPERADAS = 3;

    /** Número esperado de subcategorías del catálogo de demostración. */
    private static final int SUBCATEGORIAS_ESPERADAS = 7;

    /** Número esperado de publicaciones de demostración. */
    private static final int PUBLICACIONES_ESPERADAS = 8;

    /**
     * Descripción exacta de la publicación demo del laptop (PHA16TSK12, decisión de Lino
     * 2026-09-13 "Renombrar + seed"). Es la clave semántica de idempotencia junto al vendedor.
     */
    private static final String DESCRIPCION_LAPTOP = "Laptop HP 200 G2a con procesador AMD Ryzen 5";

    /**
     * Nombre exacto del asset renombrado a kebab-case sin espacios (PHA16TSK12), existente en
     * {@code frontend/public/imagenes/publicaciones/}.
     */
    private static final String IMAGEN_LAPTOP = "laptop-hp-200-g2a-amd-ryzen5.png";

    /** Nombre del asset viejo con espacios, que no debe existir tras el rename (PHA16TSK12). */
    private static final String IMAGEN_LAPTOP_VIEJA = "Laptop-HP-200-G2a-AMD-Ryzen 5.png";

    /** Precio demo del laptop en centavos (PEN), valor fijado por el Arquitecto (PHA16TSK12). */
    private static final int PRECIO_LAPTOP = 159900;

    /** Stock demo del laptop, valor fijado por el Arquitecto (PHA16TSK12). */
    private static final int STOCK_LAPTOP = 2;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private DataSource dataSource;

    /**
     * Verifica que el seed dev crea exactamente las dos cuentas del contrato con rol
     * {@code USUARIO} y que las contraseñas planas definidas en plan.md son válidas contra los
     * hashes BCrypt persistidos, usando el {@link PasswordEncoder} real del sistema.
     */
    @Test
    @DisplayName("El seed dev crea las cuentas vendedor/comprador con rol USUARIO y contraseñas válidas")
    void seedDev_creaCuentasDemoConRolUsuarioYPasswordsValidas() {
        Map<String, Object> vendedor = jdbcTemplate.queryForMap(
                "SELECT email, password_hash, rol FROM usuarios WHERE email = ?", EMAIL_VENDEDOR);
        assertThat(vendedor.get("email")).isEqualTo(EMAIL_VENDEDOR);
        assertThat(vendedor.get("rol")).isEqualTo("USUARIO");
        assertThat(passwordEncoder.matches(PASSWORD_VENDEDOR, (String) vendedor.get("password_hash")))
                .as("la contraseña del vendedor debe validar contra el hash BCrypt del seed")
                .isTrue();

        Map<String, Object> comprador = jdbcTemplate.queryForMap(
                "SELECT email, password_hash, rol FROM usuarios WHERE email = ?", EMAIL_COMPRADOR);
        assertThat(comprador.get("email")).isEqualTo(EMAIL_COMPRADOR);
        assertThat(comprador.get("rol")).isEqualTo("USUARIO");
        assertThat(passwordEncoder.matches(PASSWORD_COMPRADOR, (String) comprador.get("password_hash")))
                .as("la contraseña del comprador debe validar contra el hash BCrypt del seed")
                .isTrue();
    }

    /**
     * Verifica que el seed dev crea el catálogo (categorías con sus subcategorías) y las
     * publicaciones de demostración en los estados navegables por las UIs de PHA06TSK09-14:
     * dos aprobadas (mercado/detalle/compra), dos pendientes de revisión (moderación), una
     * rechazada y una con cambios solicitados (Story 3) y una oculta con stock cero.
     */
    @Test
    @DisplayName("El seed dev crea catálogo y publicaciones en estados navegables")
    void seedDev_creaCatalogoYPublicacionesEnEstadosNavegables() {
        Integer categorias = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM categorias", Integer.class);
        assertThat(categorias).isEqualTo(CATEGORIAS_ESPERADAS);

        Integer subcategorias = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM subcategorias", Integer.class);
        assertThat(subcategorias).isEqualTo(SUBCATEGORIAS_ESPERADAS);

        Integer publicaciones = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM publicaciones", Integer.class);
        assertThat(publicaciones).isEqualTo(PUBLICACIONES_ESPERADAS);

        assertThat(contarPublicacionesPorEstado("aprobada")).isEqualTo(3);
        assertThat(contarPublicacionesPorEstado("pendiente_revisión")).isEqualTo(2);
        assertThat(contarPublicacionesPorEstado("rechazada")).isEqualTo(1);
        assertThat(contarPublicacionesPorEstado("cambios_solicitados")).isEqualTo(1);
        assertThat(contarPublicacionesPorEstado("oculta")).isEqualTo(1);

        // Todas las publicaciones del seed pertenecen al vendedor de demostración.
        Integer delVendedor = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM publicaciones p JOIN usuarios u ON u.id = p.usuario_id WHERE u.email = ?",
                Integer.class, EMAIL_VENDEDOR);
        assertThat(delVendedor).isEqualTo(PUBLICACIONES_ESPERADAS);
    }

    /**
     * Verifica la idempotencia real del seed: vuelve a aplicar el script completo
     * {@code R__seed_demo.sql} desde classpath (la misma ruta que escanea Flyway) y comprueba
     * que ningún conteo cambia y que no aparecen duplicados por la clave semántica determinística
     * {@code (usuario_id, descripcion)} con la que el seed protege las publicaciones.
     */
    @Test
    @DisplayName("El seed dev es idempotente: re-aplicar el script completo no duplica filas")
    void seedDev_esIdempotenteAlReaplicarElScriptCompleto() {
        // Precondición: el seed ya aplicó al arrancar el contexto (en Red fase falla aquí,
        // porque los datos de demostración aún no existen — la razón correcta).
        Integer publicacionesIniciales = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM publicaciones", Integer.class);
        assertThat(publicacionesIniciales).as("el seed debe haber creado publicaciones al arrancar")
                .isEqualTo(PUBLICACIONES_ESPERADAS);
        Integer usuariosIniciales = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM usuarios WHERE email IN (?, ?)", Integer.class,
                EMAIL_VENDEDOR, EMAIL_COMPRADOR);
        assertThat(usuariosIniciales).as("el seed debe haber creado las dos cuentas demo al arrancar")
                .isEqualTo(2);

        // Re-aplicar el script completo del seed, simulando la re-ejecución de la migración
        // repeatable por cambio de checksum. UTF-8 explícito por el valor 'pendiente_revisión'.
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.setSqlScriptEncoding("UTF-8");
        populator.addScript(new ClassPathResource("db/dev/R__seed_demo.sql"));
        populator.execute(dataSource);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM usuarios WHERE email IN (?, ?)",
                Integer.class, EMAIL_VENDEDOR, EMAIL_COMPRADOR)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM categorias", Integer.class))
                .isEqualTo(CATEGORIAS_ESPERADAS);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM subcategorias", Integer.class))
                .isEqualTo(SUBCATEGORIAS_ESPERADAS);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM publicaciones", Integer.class))
                .isEqualTo(PUBLICACIONES_ESPERADAS);

        Integer duplicados = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ("
                        + "  SELECT usuario_id, descripcion, COUNT(*) AS c"
                        + "  FROM publicaciones"
                        + "  GROUP BY usuario_id, descripcion"
                        + "  HAVING COUNT(*) > 1"
                        + ") duplicados",
                Integer.class);
        assertThat(duplicados).as("la clave semántica (usuario_id, descripcion) no debe duplicarse")
                .isZero();
    }

    /**
     * Verifica que el seed dev NO inserta datos del flujo de pago ni de los logs de negocio:
     * las transacciones y sus artefactos solo nacen por el flujo real de Stripe/webhook
     * (plan.md, "Datos demostrativos"; constitution, principios 2 y 4).
     */
    @Test
    @DisplayName("El seed dev no inserta transacciones, movimientos ni logs de negocio")
    void seedDev_noInsertaDatosDelFlujoDePago() {
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transacciones", Integer.class))
                .as("transacciones debe permanecer vacía").isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM movimientos_saldo", Integer.class))
                .as("movimientos_saldo debe permanecer vacía").isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM idempotency_keys", Integer.class))
                .as("idempotency_keys debe permanecer vacía").isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM processed_stripe_events", Integer.class))
                .as("processed_stripe_events debe permanecer vacía").isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transaccion_eventos", Integer.class))
                .as("transaccion_eventos debe permanecer vacía").isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notificaciones", Integer.class))
                .as("notificaciones debe permanecer vacía").isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stripe_refund_outbox", Integer.class))
                .as("stripe_refund_outbox debe permanecer vacía").isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM admin_acciones", Integer.class))
                .as("admin_acciones debe permanecer vacía").isZero();
    }

    /**
     * Verifica que el seed dev asigna {@code imagen_filename} a las ocho publicaciones de
     * demostración según el mapeo descripción → archivo declarado en PHA13TSK01 y extendido en
     * PHA16TSK12 (coincidencia semántica contra archivos reales existentes en
     * {@code frontend/public/imagenes/publicaciones/}).
     *
     * <p>Los UPDATEs del seed usan la guardia {@code AND imagen_filename IS NULL}, por lo que la
     * asignación es idempotente y no sobrescribe decisiones de imagen ya tomadas por el usuario
     * en bases donde el seed corrió previamente (en una BD limpia de Testcontainers todas las
     * filas nacen con NULL, así que aquí se verifica exactamente el mapeo completo).</p>
     */
    @Test
    @DisplayName("El seed dev asigna imagen_filename a las 8 publicaciones según el mapeo declarado")
    void seedDev_asignaImagenFilenameSegunMapeoDeclarado() {
        Map<String, String> mapeoEsperado = Map.of(
                "Auriculares inalámbricos Bluetooth con cancelación de ruido", "auriculares-bluetooth.jpg",
                "Smartwatch deportivo con GPS y monitor de ritmo cardíaco", "smartwatch-gps.jpg",
                "Mesa de centro de madera de cedro de 90 cm", "mesa-centro-cedro.jpg",
                "Lámpara de escritorio LED regulable", "lampara-escritorio-led.jpg",
                "Zapatillas urbanas de cuero talla 42", "zapatillas-cuero-42.jpg",
                "Camisa de lino de manga larga color azul", "camisa-lino-azul.jpg",
                "Auriculares con cable de estudio", "auriculares-estudio.jpg",
                DESCRIPCION_LAPTOP, IMAGEN_LAPTOP);

        List<Map<String, Object>> filas = jdbcTemplate.queryForList(
                "SELECT descripcion, imagen_filename FROM publicaciones");
        assertThat(filas).as("el seed debe haber creado las publicaciones de demostración")
                .hasSize(PUBLICACIONES_ESPERADAS);

        for (Map<String, Object> fila : filas) {
            String descripcion = (String) fila.get("descripcion");
            assertThat(descripcion)
                    .as("descripción inesperada encontrada en publicaciones del seed: %s", descripcion)
                    .isIn(mapeoEsperado.keySet());
            assertThat((String) fila.get("imagen_filename"))
                    .as("la publicación '%s' debe tener imagen_filename según el mapeo", descripcion)
                    .isNotNull()
                    .isEqualTo(mapeoEsperado.get(descripcion));
        }
    }

    /**
     * Verifica que el seed dev crea la publicación demo del laptop (PHA16TSK12, decisión de Lino
     * 2026-09-13 "Renombrar + seed") con los valores fijados por el Arquitecto: precio demo 159900
     * centavos, stock 2, estado {@code aprobada}, subcategoría {@code Laptops} bajo
     * {@code Electrónica} e {@code imagen_filename} exacto del asset renombrado a kebab-case.
     *
     * <p>En Red phase (sin el seed extendido) falla por ausencia: la fila no existe y
     * {@code queryForMap} lanza {@code EmptyResultDataAccessException} — la razón correcta,
     * no un fallo de setup.</p>
     */
    @Test
    @DisplayName("El seed dev crea la publicación del laptop con precio, stock, estado e imagen fijados")
    void seedDev_creaPublicacionLaptopConValoresFijados() {
        Map<String, Object> laptop = jdbcTemplate.queryForMap(
                "SELECT p.precio, p.stock, p.estado, p.imagen_filename, p.codigo_producto, "
                        + "c.nombre AS categoria, sub.nombre AS subcategoria "
                        + "FROM publicaciones p "
                        + "JOIN usuarios u ON u.id = p.usuario_id "
                        + "JOIN categorias c ON c.id = p.categoria_id "
                        + "JOIN subcategorias sub ON sub.id = p.subcategoria_id "
                        + "WHERE u.email = ? AND p.descripcion = ?",
                EMAIL_VENDEDOR, DESCRIPCION_LAPTOP);
        assertThat(((Number) laptop.get("precio")).intValue())
                .as("precio demo del laptop en centavos (valor fijado PHA16TSK12)")
                .isEqualTo(PRECIO_LAPTOP);
        assertThat(((Number) laptop.get("stock")).intValue())
                .as("stock demo del laptop (valor fijado PHA16TSK12)")
                .isEqualTo(STOCK_LAPTOP);
        assertThat(laptop.get("estado"))
                .as("estado demo del laptop (valor fijado PHA16TSK12)")
                .isEqualTo("aprobada");
        assertThat(laptop.get("categoria")).isEqualTo("Electrónica");
        assertThat(laptop.get("subcategoria")).isEqualTo("Laptops");
        assertThat((String) laptop.get("imagen_filename"))
                .as("la publicación del laptop debe mapear al asset renombrado")
                .isEqualTo(IMAGEN_LAPTOP);
        assertThat((String) laptop.get("codigo_producto"))
                .as("el trigger V22 debe haber asignado codigo_producto al insertar el seed")
                .isNotNull();
    }

    /**
     * Verifica que re-ejecutar el seed dev no duplica la publicación del laptop y no sobrescribe
     * una imagen ya asignada (PHA16TSK12, criterio b).
     *
     * <p>Re-ejecución con el patrón existente de la clase: {@link ResourceDatabasePopulator} sobre
     * {@code db/dev/R__seed_demo.sql} desde classpath con UTF-8 explícito (el mismo script que
     * Flyway aplica como migración repeatable). La no-sobrescritura se prueba de forma real:
     * se asigna manualmente una imagen distinta a la del laptop, se re-ejecuta el seed y se
     * verifica que la imagen manual sobrevive — ejerciendo la guardia
     * {@code AND imagen_filename IS NULL} del UPDATE (patrón PHA13TSK01).</p>
     */
    @Test
    @DisplayName("Re-ejecutar el seed no duplica el laptop ni sobrescribe su imagen ya asignada")
    void seedDev_reaplicarNoDuplicaLaptopNiSobrescribeImagen() {
        // Precondición: el seed ya creó la publicación del laptop al arrancar el contexto.
        Integer laptopInicial = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM publicaciones p JOIN usuarios u ON u.id = p.usuario_id "
                        + "WHERE u.email = ? AND p.descripcion = ?",
                Integer.class, EMAIL_VENDEDOR, DESCRIPCION_LAPTOP);
        assertThat(laptopInicial)
                .as("el seed debe haber creado la publicación del laptop al arrancar")
                .isEqualTo(1);

        // Asignar manualmente una imagen distinta: simula la decisión de un usuario en una BD
        // ya sembrada, que el seed nunca debe pisar.
        String imagenManual = "mi-imagen-manual.jpg";
        jdbcTemplate.update("UPDATE publicaciones SET imagen_filename = ? WHERE descripcion = ?",
                imagenManual, DESCRIPCION_LAPTOP);

        try {
            reaplicarSeed();

            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM publicaciones", Integer.class))
                    .as("re-ejecutar el seed no debe crear publicaciones adicionales")
                    .isEqualTo(PUBLICACIONES_ESPERADAS);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM publicaciones p JOIN usuarios u ON u.id = p.usuario_id "
                            + "WHERE u.email = ? AND p.descripcion = ?",
                    Integer.class, EMAIL_VENDEDOR, DESCRIPCION_LAPTOP))
                    .as("la clave semántica (usuario_id, descripcion) del laptop no debe duplicarse")
                    .isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT imagen_filename FROM publicaciones WHERE descripcion = ?",
                    String.class, DESCRIPCION_LAPTOP))
                    .as("la guardia IS NULL debe preservar la imagen asignada manualmente")
                    .isEqualTo(imagenManual);
        } finally {
            // Restaurar la imagen del seed: la clase comparte una sola BD por contexto Spring y
            // JUnit no garantiza el orden entre métodos — sin esta limpieza, la imagen manual
            // contaminaría los tests del mapeo (defecto de aislamiento detectado en Green).
            jdbcTemplate.update("UPDATE publicaciones SET imagen_filename = ? WHERE descripcion = ?",
                    IMAGEN_LAPTOP, DESCRIPCION_LAPTOP);
        }
    }

    /**
     * Verifica que las 7 publicaciones previas al laptop conservan su mapeo
     * descripción → {@code imagen_filename} declarado en PHA13TSK01 (PHA16TSK12, criterio c):
     * la extensión del seed no altera ninguna fila existente.
     */
    @Test
    @DisplayName("Las 7 publicaciones previas conservan su mapeo de imagen tras extender el seed")
    void seedDev_sietePreviasConservanMapeo() {
        Map<String, String> mapeoPrevio = Map.of(
                "Auriculares inalámbricos Bluetooth con cancelación de ruido", "auriculares-bluetooth.jpg",
                "Smartwatch deportivo con GPS y monitor de ritmo cardíaco", "smartwatch-gps.jpg",
                "Mesa de centro de madera de cedro de 90 cm", "mesa-centro-cedro.jpg",
                "Lámpara de escritorio LED regulable", "lampara-escritorio-led.jpg",
                "Zapatillas urbanas de cuero talla 42", "zapatillas-cuero-42.jpg",
                "Camisa de lino de manga larga color azul", "camisa-lino-azul.jpg",
                "Auriculares con cable de estudio", "auriculares-estudio.jpg");

        assertThat(mapeoPrevio).hasSize(7);
        for (Map.Entry<String, String> entrada : mapeoPrevio.entrySet()) {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT imagen_filename FROM publicaciones WHERE descripcion = ?",
                    String.class, entrada.getKey()))
                    .as("la publicación previa '%s' debe conservar su imagen", entrada.getKey())
                    .isEqualTo(entrada.getValue());
        }
    }

    /**
     * Verifica el rename del asset en disco (PHA16TSK12, criterio d): el archivo kebab-case sin
     * espacios existe en {@code frontend/public/imagenes/publicaciones/} y el nombre viejo con
     * espacios ya no existe.
     *
     * <p>Las rutas se resuelven desde el directorio de trabajo del módulo backend
     * ({@code user.dir} = {@code backend/} bajo Maven) hacia el frontend hermano. La ausencia de
     * referencias al nombre viejo en código se verifica además con grep (evidencia en el
     * Artifact): antes del move el grep sobre {@code frontend/src}, {@code backend/src} y
     * {@code frontend/public} es vacío — solo quedan menciones documentales en
     * {@code docs/avance}, {@code plan.md}, {@code tasks.md}, {@code CHANGELOG.md} y
     * {@code ESTADO_PROYECTO.md}.</p>
     *
     * <p>En Red phase (sin el rename) falla por ausencia: el archivo nuevo no existe — la razón
     * correcta, no un fallo de setup.</p>
     */
    @Test
    @DisplayName("El asset del laptop existe con nombre kebab-case y el nombre viejo ya no existe")
    void seedDev_archivoLaptopRenombradoExiste() {
        java.nio.file.Path directorio = java.nio.file.Paths.get(
                System.getProperty("user.dir"), "..", "frontend", "public", "imagenes",
                "publicaciones").normalize();
        assertThat(java.nio.file.Files.isRegularFile(directorio.resolve(IMAGEN_LAPTOP)))
                .as("el asset renombrado %s debe existir en %s", IMAGEN_LAPTOP, directorio)
                .isTrue();
        assertThat(java.nio.file.Files.exists(directorio.resolve(IMAGEN_LAPTOP_VIEJA)))
                .as("el asset con el nombre viejo con espacios ya no debe existir")
                .isFalse();
    }

    /**
     * Re-aplica el script completo del seed dev, simulando la re-ejecución de la migración
     * repeatable por cambio de checksum (patrón existente de la clase, PHA06TSK08).
     * UTF-8 explícito por el valor 'pendiente_revisión'.
     */
    private void reaplicarSeed() {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.setSqlScriptEncoding("UTF-8");
        populator.addScript(new ClassPathResource("db/dev/R__seed_demo.sql"));
        populator.execute(dataSource);
    }

    /**
     * Cuenta las publicaciones del seed en un estado exacto de la máquina de estados.
     *
     * @param estado valor literal del estado en el esquema (ej. {@code aprobada})
     * @return cantidad de publicaciones en ese estado
     */
    private Integer contarPublicacionesPorEstado(String estado) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM publicaciones WHERE estado = ?", Integer.class, estado);
    }
}
