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
    private static final int SUBCATEGORIAS_ESPERADAS = 6;

    /** Número esperado de publicaciones de demostración. */
    private static final int PUBLICACIONES_ESPERADAS = 7;

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

        assertThat(contarPublicacionesPorEstado("aprobada")).isEqualTo(2);
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
