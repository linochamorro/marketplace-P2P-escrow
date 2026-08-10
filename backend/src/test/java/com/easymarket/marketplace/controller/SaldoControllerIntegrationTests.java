package com.easymarket.marketplace.controller;

import com.easymarket.marketplace.dto.LoginRequestDto;
import com.easymarket.marketplace.model.Categoria;
import com.easymarket.marketplace.model.EstadoTransaccion;
import com.easymarket.marketplace.model.MovimientoSaldo;
import com.easymarket.marketplace.model.Publicacion;
import com.easymarket.marketplace.model.Rol;
import com.easymarket.marketplace.model.Subcategoria;
import com.easymarket.marketplace.model.Transaccion;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.CategoriaRepository;
import com.easymarket.marketplace.repository.MovimientoSaldoRepository;
import com.easymarket.marketplace.repository.PublicacionRepository;
import com.easymarket.marketplace.repository.SubcategoriaRepository;
import com.easymarket.marketplace.repository.TransaccionRepository;
import com.easymarket.marketplace.repository.UsuarioRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración del endpoint de consulta de saldo del usuario autenticado
 * {@code GET /usuarios/me/saldo} (PHA04TSK17, Story 12).
 *
 * <p>Usa PostgreSQL real mediante Testcontainers e identidades emitidas por el login JWT real;
 * la cookie {@code jwt} obtenida por HTTP autentica cada petición. Verifica el criterio de
 * aceptación de la Story 12 y la fila de la tarea: un vendedor autenticado ve su
 * {@code saldo_disponible} cacheado (el valor leído del campo cacheado por
 * {@code ConsultaSaldoService}, sin recálculo sobre el ledger) y el detalle de los movimientos
 * append-only que lo componen, mapeado campo a campo contra los valores reales persistidos.
 * Verifica además el aislamiento por destinatario (nunca movimientos de otro vendedor, ni saldo
 * que los incluya), el caso sin movimientos ({@code movimientos: []}) y la protección por
 * {@code anyRequest().authenticated()} de {@code SecurityConfig} (403 sin cookie JWT). El test
 * no depende del orden del detalle: el contrato de dominio (plan.md) no define orden, por lo que
 * cada movimiento de la respuesta se coteja contra su gemelo sembrado por identificador.</p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=clave-secreta-pruebas-saldo-minimo-32-caracteres",
    "ADMIN_EMAIL=admin.seed@easymarket.com",
    "ADMIN_PASSWORD_HASH=$2a$10$R9h/cIPz0gi.URNNXRkh2OPST9/PgBkqquzi.Ss7KIUgO2t0jWMUW"
})
class SaldoControllerIntegrationTests {

    private static final String PASSWORD_RAW = "PasswordSeguro123!";
    private static final long MONTO_UNITARIO_CENTAVOS = 125_000L;
    private static final ZoneId ZONA_LIMA = ZoneId.of("America/Lima");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private CategoriaRepository categoriaRepository;
    @Autowired private SubcategoriaRepository subcategoriaRepository;
    @Autowired private PublicacionRepository publicacionRepository;
    @Autowired private TransaccionRepository transaccionRepository;
    @Autowired private MovimientoSaldoRepository movimientoSaldoRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;
    private Usuario vendedorA;
    private Usuario vendedorB;
    private Usuario comprador;
    private Categoria categoria;
    private Subcategoria subcategoria;

    /**
     * Configura identidades y catálogo aislados para cada escenario HTTP.
     *
     * <p>Cada test recibe usuarios y categorías con sufijo único (patrón de los tests de
     * la misma familia), de modo que ninguna aserción padece residuos de escenarios previos.</p>
     */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        String sufijo = Long.toUnsignedString(System.nanoTime());
        vendedorA = guardarUsuario("vendedor.a.saldo." + sufijo + "@easymarket.com");
        vendedorB = guardarUsuario("vendedor.b.saldo." + sufijo + "@easymarket.com");
        comprador = guardarUsuario("comprador.saldo." + sufijo + "@easymarket.com");
        categoria = categoriaRepository.save(new Categoria("Categoría " + sufijo));
        subcategoria = subcategoriaRepository.save(new Subcategoria(categoria, "Subcategoría " + sufijo));
    }

    /**
     * Verifica el criterio central de la Story 12: un vendedor autenticado con saldo cacheado y
     * movimientos recibe 200 OK con {@code saldoDisponible} igual al valor cacheado sembrado en
     * {@code usuarios.saldo_disponible} y {@code movimientos} con el detalle append-only
     * exacto — cada campo (id, monto, createdAt y transaccionId no nulo) cotejado contra el valor
     * real persistido, sin depender del orden del detalle.
     *
     * <p>Se siembran dos movimientos para A (cada uno con su transacción real) y su saldo
     * cacheado a {@code 2 * MONTO_UNITARIO_CENTAVOS}. La respuesta se valida campo a campo: por
     * cada ítem devuelto se localiza el movimiento sembrado por su ID y se comparan monto,
     * instante de creación y transacción de origen contra la base de datos.</p>
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("GET /usuarios/me/saldo devuelve 200 con saldo cacheado y movimientos campo a campo")
    void obtener_VendedorConSaldoYMovimientos_Retorna200ConSaldoCacheadoYDetalle() throws Exception {
        long saldoCacheadoA = 2 * MONTO_UNITARIO_CENTAVOS;
        vendedorA.setSaldoDisponible(saldoCacheadoA);
        usuarioRepository.saveAndFlush(vendedorA);

        ZonedDateTime t1 = ahora();
        ZonedDateTime t2 = t1.plusMinutes(10L);
        MovimientoSaldo m1 = guardarMovimiento(vendedorA, MONTO_UNITARIO_CENTAVOS, t1);
        MovimientoSaldo m2 = guardarMovimiento(vendedorA, MONTO_UNITARIO_CENTAVOS, t2);

        MvcResult result = mockMvc.perform(get("/usuarios/me/saldo")
                        .cookie(obtenerCookieJwtPostLogin(vendedorA)))
                .andExpect(status().isOk())
                .andReturn();

        Map<String, Object> cuerpo = leerCuerpo(result);
        assertThat(((Number) cuerpo.get("saldoDisponible")).longValue()).isEqualTo(saldoCacheadoA);

        List<Map<String, Object>> movimientos = leerMovimientos(cuerpo);
        assertThat(movimientos).hasSize(2);

        Map<Long, MovimientoSaldo> sembradosPorId = new HashMap<>();
        sembradosPorId.put(m1.getId(), m1);
        sembradosPorId.put(m2.getId(), m2);

        for (Map<String, Object> item : movimientos) {
            Long idMovimiento = ((Number) item.get("id")).longValue();
            MovimientoSaldo sembrado = sembradosPorId.get(idMovimiento);
            assertThat(sembrado).as("movimiento %s debe corresponder a un movimiento sembrado", idMovimiento)
                    .isNotNull();
            assertThat(((Number) item.get("monto")).longValue()).isEqualTo(sembrado.getMonto());
            assertThat(instantDe(item.get("createdAt"))).isEqualTo(sembrado.getCreatedAt().toInstant());
            assertThat(((Number) item.get("transaccionId")).longValue())
                    .isEqualTo(sembrado.getTransaccion().getId());
        }
    }

    /**
     * Verifica el aislamiento por destinatario: cuando existen movimientos de OTRO vendedor (B)
     * además de los del autenticado (A), la respuesta NO contiene ninguno de B y el
     * {@code saldoDisponible} es el cacheado de A — no suma ni incluye los ajenos.
     *
     * <p>Se siembran 1 movimiento para A (saldo cacheado de A = 1 monto) y 2 movimientos para B
     * (saldo cacheado de B = 2 montos). La suma de TODOS los movimientos de la tabla (3 montos)
     * difiere del saldo cacheado de A, de modo que si el endpoint recomputara o mezclara saldos la
     * aserción fallaría. Como sanidad se comprueba que los movimientos de B existen en BD: su
     * ausencia en la respuesta solo puede atribuirse al aislamiento del endpoint.</p>
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("GET /usuarios/me/saldo no incluye saldo ni movimientos de otro vendedor")
    void obtener_VendedorConMovimientosAjenos_Retorna200SoloLosSuyos() throws Exception {
        long saldoCacheadoA = MONTO_UNITARIO_CENTAVOS;
        long saldoCacheadoB = 2 * MONTO_UNITARIO_CENTAVOS;
        vendedorA.setSaldoDisponible(saldoCacheadoA);
        vendedorB.setSaldoDisponible(saldoCacheadoB);
        usuarioRepository.saveAndFlush(vendedorA);
        usuarioRepository.saveAndFlush(vendedorB);

        MovimientoSaldo propio = guardarMovimiento(vendedorA, MONTO_UNITARIO_CENTAVOS, ahora());
        MovimientoSaldo ajeno1 = guardarMovimiento(vendedorB, MONTO_UNITARIO_CENTAVOS, ahora());
        MovimientoSaldo ajeno2 = guardarMovimiento(vendedorB, MONTO_UNITARIO_CENTAVOS, ahora());

        MvcResult result = mockMvc.perform(get("/usuarios/me/saldo")
                        .cookie(obtenerCookieJwtPostLogin(vendedorA)))
                .andExpect(status().isOk())
                .andReturn();

        Map<String, Object> cuerpo = leerCuerpo(result);
        assertThat(((Number) cuerpo.get("saldoDisponible")).longValue()).isEqualTo(saldoCacheadoA);

        List<Map<String, Object>> movimientos = leerMovimientos(cuerpo);
        assertThat(movimientos).hasSize(1);
        assertThat(((Number) movimientos.get(0).get("id")).longValue()).isEqualTo(propio.getId());
        assertThat(movimientos)
                .extracting(item -> ((Number) item.get("id")).longValue())
                .doesNotContain(ajeno1.getId(), ajeno2.getId());

        // Sanidad: los movimientos de B existen en BD; su ausencia es del endpoint, no del fixture
        assertThat(movimientoSaldoRepository.findAll())
                .extracting(MovimientoSaldo::getId)
                .contains(ajeno1.getId(), ajeno2.getId());
    }

    /**
     * Verifica el caso sin datos de la Story 12: un vendedor autenticado sin movimientos recibe
     * 200 OK con {@code movimientos: []} y el {@code saldoDisponible} cacheado tal cual.
     *
     * <p>El saldo cacheado se siembra a un valor no nulo (50 000 centavos) sin ningún movimiento
     * en el ledger: el contrato de plan.md dice que la consulta lee el cache en O(1) y NO agrega
     * sobre {@code movimientos_saldo}, de modo que un usuario con cache poblado y sin movimientos
     * debe ver su cache y una lista vacía — no un saldo recalculado desde el ledger.</p>
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("GET /usuarios/me/saldo sin movimientos retorna 200 con lista vacía y saldo cacheado")
    void obtener_VendedorSinMovimientos_Retorna200ConSaldoCacheadoYListaVacia() throws Exception {
        long saldoCacheadoA = 50_000L;
        vendedorA.setSaldoDisponible(saldoCacheadoA);
        usuarioRepository.saveAndFlush(vendedorA);

        MvcResult result = mockMvc.perform(get("/usuarios/me/saldo")
                        .cookie(obtenerCookieJwtPostLogin(vendedorA)))
                .andExpect(status().isOk())
                .andReturn();

        Map<String, Object> cuerpo = leerCuerpo(result);
        assertThat(((Number) cuerpo.get("saldoDisponible")).longValue()).isEqualTo(saldoCacheadoA);
        assertThat(leerMovimientos(cuerpo)).isEmpty();
    }

    /**
     * Verifica la protección de la cadena de seguridad: {@code GET /usuarios/me/saldo} sin cookie
     * JWT retorna 403 Forbidden (la ruta queda cubierta por {@code anyRequest().authenticated()}
     * de {@code SecurityConfig}, sin requestMatcher nuevo).
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("GET /usuarios/me/saldo sin autenticación retorna 403 Forbidden")
    void obtener_SinAutenticacion_Retorna403Forbidden() throws Exception {
        mockMvc.perform(get("/usuarios/me/saldo"))
                .andExpect(status().isForbidden());
    }

    /**
     * Persiste un vendedor de prueba con la contraseña compartida de la clase y saldo cacheado 0.
     *
     * @param email correo único del vendedor
     * @return vendedor persistido con {@code saldo_disponible} = 0
     */
    private Usuario guardarUsuario(String email) {
        return usuarioRepository.save(new Usuario(email, passwordEncoder.encode(PASSWORD_RAW), Rol.USUARIO,
                0L, ZonedDateTime.now(ZONA_LIMA)));
    }

    /**
     * Persiste una transacción reservada del comprador sobre una publicación del vendedor en
     * stock 0, reproduciendo la condición física de una unidad ya consumida por el webhook al
     * reservar (patrón de siembra de la familia TSK13-15) y usada como origen real de un
     * movimiento de saldo (FK {@code transaccion_id} NOT NULL, V9).
     *
     * @param vendedor dueño de la publicación acreditada
     * @return transacción persistida en estado {@code RESERVADA}
     */
    private Transaccion guardarTransaccionReservada(Usuario vendedor) {
        Publicacion publicacion = publicacionRepository.save(new Publicacion(vendedor, categoria, subcategoria,
                MONTO_UNITARIO_CENTAVOS, 0, "Artículo de prueba"));
        Transaccion transaccion = new Transaccion(comprador, publicacion, MONTO_UNITARIO_CENTAVOS,
                ZonedDateTime.now(ZONA_LIMA));
        transaccion.setEstado(EstadoTransaccion.RESERVADA);
        return transaccionRepository.saveAndFlush(transaccion);
    }

    /**
     * Persiste un movimiento append-only de saldo para el vendedor indicado (solo INSERT; el
     * trigger V11 impide UPDATE/DELETE sobre {@code movimientos_saldo}).
     *
     * @param vendedor vendedor acreditado
     * @param monto monto entero en centavos del movimiento
     * @param createdAt instante de creación del registro
     * @return movimiento persistido con su transacción real de origen
     */
    private MovimientoSaldo guardarMovimiento(Usuario vendedor, long monto, ZonedDateTime createdAt) {
        Transaccion transaccion = guardarTransaccionReservada(vendedor);
        return movimientoSaldoRepository.save(new MovimientoSaldo(transaccion, vendedor, monto, createdAt));
    }

    /**
     * Obtiene mediante el endpoint de login la cookie JWT del usuario indicado.
     *
     * @param usuario usuario cuyas credenciales se autentican
     * @return cookie httpOnly {@code jwt} emitida por la aplicación
     * @throws Exception si el login HTTP no se completa correctamente
     */
    private Cookie obtenerCookieJwtPostLogin(Usuario usuario) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequestDto(usuario.getEmail(), PASSWORD_RAW))))
                .andExpect(status().isOk())
                .andReturn();
        Cookie cookie = result.getResponse().getCookie("jwt");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    /**
     * Lee el cuerpo JSON de la respuesta como mapa raíz ({@code saldoDisponible} y
     * {@code movimientos}).
     *
     * @param result resultado de la petición HTTP ya validada
     * @return mapa con los campos del DTO de respuesta
     * @throws Exception si el cuerpo no puede leerse o parsearse
     */
    private Map<String, Object> leerCuerpo(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$");
    }

    /**
     * Extrae la lista de movimientos del cuerpo raíz de la respuesta.
     *
     * @param cuerpo cuerpo raíz parseado de la respuesta
     * @return lista de mapas con los campos del DTO de movimiento
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> leerMovimientos(Map<String, Object> cuerpo) {
        return (List<Map<String, Object>>) cuerpo.get("movimientos");
    }

    /**
     * Convierte la representación ISO-8601 de {@code createdAt} de la respuesta a instante.
     *
     * @param iso valor textual del campo {@code createdAt} serializado por Jackson 3
     * @return instante representado por el ISO-8601 recibido
     */
    private static java.time.Instant instantDe(Object iso) {
        return ZonedDateTime.parse((String) iso).toInstant();
    }

    /**
     * Devuelve el instante actual en la zona del proyecto (Perú) truncado a milisegundos.
     *
     * <p>El truncado a milisegundos evita la pérdida de precisión nanosegundo &rarr; microsegundo
     * de PostgreSQL al persistir {@code timestamptz}: el valor vuelto a leer y serializar coincide
     * exactamente con el esperado en memoria.</p>
     *
     * @return instante actual en {@code America/Lima} sin fracciones sub-milisegundo
     */
    private static ZonedDateTime ahora() {
        return ZonedDateTime.now(ZONA_LIMA).truncatedTo(ChronoUnit.MILLIS);
    }
}