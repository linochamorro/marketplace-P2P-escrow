package com.easymarket.marketplace.controller;

import com.easymarket.marketplace.dto.LoginRequestDto;
import com.easymarket.marketplace.model.Categoria;
import com.easymarket.marketplace.model.EstadoPublicacion;
import com.easymarket.marketplace.model.EstadoTransaccion;
import com.easymarket.marketplace.model.LoginAttempt;
import com.easymarket.marketplace.model.MovimientoSaldo;
import com.easymarket.marketplace.model.Publicacion;
import com.easymarket.marketplace.model.Rol;
import com.easymarket.marketplace.model.Subcategoria;
import com.easymarket.marketplace.model.Transaccion;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.CategoriaRepository;
import com.easymarket.marketplace.repository.LoginAttemptRepository;
import com.easymarket.marketplace.repository.MovimientoSaldoRepository;
import com.easymarket.marketplace.repository.PublicacionRepository;
import com.easymarket.marketplace.repository.SubcategoriaRepository;
import com.easymarket.marketplace.repository.TransaccionRepository;
import com.easymarket.marketplace.repository.UsuarioRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración de las consultas administrativas de solo lectura (PHA06TSK07; Stories 0c,
 * 9 y 13 de spec.md; plan.md, "Lecturas administrativas" y "Tablero administrativo").
 *
 * <p>Usa PostgreSQL real mediante Testcontainers e identidades emitidas por el login JWT real.
 * Verifica el criterio de la fila de tarea: un usuario no-admin recibe HTTP 403 en los tres
 * endpoints ({@code GET /admin/disputas}, {@code GET /admin/usuarios/bloqueados} y
 * {@code GET /admin/tablero}); {@code GET /admin/disputas} devuelve únicamente transacciones en
 * estado {@code disputa} campo a campo; {@code GET /admin/usuarios/bloqueados} devuelve solo
 * usuarios reales con al menos una combinación {@code login_attempts.intentos >= 12}
 * (deduplicados por email, excluyendo emails huérfanos sin cuenta real); y el tablero suma
 * exactamente los estados y movimientos definidos: volumen de escrow como suma de
 * {@code precio_snapshot} de {@code reservada}/{@code enviado}/{@code entregado}/{@code disputa},
 * fondos liberados como suma de movimientos positivos del ledger, finalizadas como conteo de
 * {@code recibido}/{@code recibido_sin_respuesta}/{@code completada}. Todo se coteja contra
 * PostgreSQL real (sin mocks).</p>
 *
 * <p>El orden de ejecución de los métodos está fijado con {@link Order}: los escenarios que
 * siembran transacciones y movimientos corren después del escenario del tablero, de modo que la
 * verificación "sin datos devuelve ceros" del tablero se ejecuta sobre una base limpia (cada
 * {@code @BeforeEach} elimina las tablas borrables; {@code movimientos_saldo} es append-only por
 * el trigger de V11 y solo lo puebla el último escenario). El Red phase de esta clase depende de
 * que los endpoints bajo {@code /admin} de lectura aún no existan y por eso los escenarios 200
 * fallan con HTTP 404 (endpoint no mapeado); los escenarios 403 pasan desde la cadena de
 * seguridad porque {@code SecurityConfig} ya exige rol {@code ADMIN} en {@code /admin/**}.</p>
 *
 * <p><strong>Política única de fixtures ADMIN (PHA12TSK06).</strong> Esta clase NO crea ninguna
 * fila ADMIN: la limpieza de {@code @BeforeEach} conserva al único administrador provisionado
 * por el contexto de test (seed V6 con {@code ADMIN_EMAIL}/{@code ADMIN_PASSWORD_HASH}, cuyo
 * hash corresponde a la contraseña plana compartida {@code PASSWORD_RAW}) y todo escenario que
 * necesita iniciar sesión tras un gate {@code hasRole("ADMIN")} lo hace exclusivamente con esa
 * identidad única, resuelta mediante {@link #obtenerAdminUnico()}. Los usuarios auxiliares nacen
 * todos {@code Rol.USUARIO}. Motivo: {@code UsuarioRepository.findByRol(Rol.ADMIN)} es Optional
 * por diseño (invariante de admin único, PHA06TSK02) y cualquier fixture con un ADMIN adicional
 * rompe esa invariante.</p>
 */
@SpringBootTest
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=clave-secreta-pruebas-admin-consulta-minimo-32-caracteres",
    "ADMIN_EMAIL=admin.seed@easymarket.com",
    "ADMIN_PASSWORD_HASH=$2a$10$ezbtTwVogv0lR8nXJuHRk.RGzMVbhLBUjDAt4zzBdJhbqR.U53k6u"
})
class AdminConsultaControllerIntegrationTests {

    private static final String PASSWORD_RAW = "PasswordSeguro123!";
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
    @Autowired private LoginAttemptRepository loginAttemptRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    /** Email del ADMIN único provisionado por el contexto de test (propiedad {@code ADMIN_EMAIL}, seed V6). */
    @Value("${ADMIN_EMAIL}")
    private String adminEmail;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;
    private Usuario vendedor;
    private Usuario comprador;
    private Usuario admin;
    private Usuario usuarioRegular;
    private Categoria categoria;
    private Subcategoria subcategoria;

    /**
     * Configura identidades y catálogo aislados para cada escenario HTTP.
     *
     * <p>Elimina primero las tablas con dependencias de FK y luego las entidades raíz — salvo el
     * ADMIN único del seed V6, que sobrevive a la limpieza (política PHA12TSK06) —, de modo que
     * cada método arranca sin residuos de escenarios previos. {@code movimientos_saldo} es
     * append-only (trigger de V11) y por eso nunca se borra aquí: el único escenario que lo
     * puebla es el último ({@link #tablero_Admin_SumaExactaYCasoVacioConDatosControlados}), que
     * verifica el caso vacío antes de sembrar sus propios movimientos.</p>
     */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        loginAttemptRepository.deleteAll();
        transaccionRepository.deleteAll();
        publicacionRepository.deleteAll();
        subcategoriaRepository.deleteAll();
        categoriaRepository.deleteAll();
        eliminarUsuariosSalvoAdminUnico();

        String sufijo = Long.toUnsignedString(System.nanoTime());
        vendedor = guardarUsuario("vendedor.admin.consulta." + sufijo + "@easymarket.com", Rol.USUARIO);
        comprador = guardarUsuario("comprador.admin.consulta." + sufijo + "@easymarket.com", Rol.USUARIO);
        admin = obtenerAdminUnico();
        usuarioRegular = guardarUsuario("regular.consulta." + sufijo + "@easymarket.com", Rol.USUARIO);
        categoria = categoriaRepository.save(new Categoria("Categoría admin consulta " + sufijo));
        subcategoria = subcategoriaRepository.save(new Subcategoria(categoria, "Subcategoría admin consulta " + sufijo));
    }

    /**
     * Elimina los usuarios de fixtures conservando únicamente las filas con rol {@code ADMIN}
     * (política PHA12TSK06): el ADMIN único provisionado por el seed V6 sobrevive a cada limpieza
     * para que los gates {@code hasRole("ADMIN")} se autentiquen con él sin que ningún fixture
     * cree filas ADMIN nuevas.
     */
    private void eliminarUsuariosSalvoAdminUnico() {
        usuarioRepository.findAll().stream()
                .filter(usuario -> usuario.getRol() != Rol.ADMIN)
                .forEach(usuarioRepository::delete);
    }

    /**
     * Resuelve la identidad persistida del ADMIN único provisionado por el contexto de test
     * (seed V6, email leído de la propiedad {@code ADMIN_EMAIL}).
     *
     * @return la entidad persistida del único administrador
     * @throws IllegalStateException si el admin sembrado no está presente (provisión de datos inconsistente)
     */
    private Usuario obtenerAdminUnico() {
        return usuarioRepository.findByEmail(adminEmail)
                .orElseThrow(() -> new IllegalStateException(
                        "El ADMIN único provisionado por el contexto (" + adminEmail + ") no está presente"));
    }

    /**
     * Verifica el criterio de autorización de la fila de tarea ("no-admin recibe 403") para los
     * tres endpoints de lectura administrativa: un usuario autenticado con rol {@code USUARIO}
     * recibe HTTP 403 Forbidden en {@code GET /admin/disputas},
     * {@code GET /admin/usuarios/bloqueados} y {@code GET /admin/tablero}.
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @Order(1)
    @DisplayName("No-admin recibe 403 en GET /admin/disputas, /admin/usuarios/bloqueados y /admin/tablero")
    void noAdmin_EnLosTresEndpointsDeLectura_Retorna403() throws Exception {
        Cookie cookieRegular = obtenerCookieJwtPostLogin(usuarioRegular);

        mockMvc.perform(get("/admin/disputas").cookie(cookieRegular))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/usuarios/bloqueados").cookie(cookieRegular))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/tablero").cookie(cookieRegular))
                .andExpect(status().isForbidden());
    }

    /**
     * Verifica {@code GET /admin/disputas}: con transacciones en varios estados
     * ({@code disputa}, {@code reservada}, {@code recibido} y {@code cancelada}) devuelve
     * únicamente la transacción en {@code disputa}, mapeada campo a campo contra la entidad
     * persistida: {@code id}, {@code estado} (código en minúscula), {@code precioSnapshot},
     * las fechas y la descripción literal de la publicación.
     *
     * @throws Exception si falla la interacción HTTP o el parseo de la respuesta
     */
    @Test
    @Order(2)
    @DisplayName("GET /admin/disputas devuelve solo las transacciones en disputa, campo a campo")
    void disputas_AdminVeSoloDisputasCampoACampo() throws Exception {
        Publicacion publicacionDisputada = guardarPublicacion(vendedor, "Auriculares en disputa única");
        Publicacion publicacionReservada = guardarPublicacion(vendedor, "Reserva sin disputa");
        Publicacion publicacionRecibida = guardarPublicacion(vendedor, "Recepción sin disputa");
        Publicacion publicacionCancelada = guardarPublicacion(vendedor, "Cancelación sin disputa");

        Transaccion disputa = guardarTransaccion(comprador, publicacionDisputada, EstadoTransaccion.DISPUTA);
        Transaccion reservada = guardarTransaccion(comprador, publicacionReservada, EstadoTransaccion.RESERVADA);
        Transaccion recibida = guardarTransaccion(comprador, publicacionRecibida, EstadoTransaccion.RECIBIDO);
        Transaccion cancelada = guardarTransaccion(comprador, publicacionCancelada, EstadoTransaccion.CANCELADA);
        Cookie cookieAdmin = obtenerCookieJwtPostLogin(admin);

        MvcResult resultado = mockMvc.perform(get("/admin/disputas").cookie(cookieAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andReturn();

        List<Long> ids = objectMapper.readTree(resultado.getResponse().getContentAsString())
                .findValuesAsText("id").stream().map(Long::valueOf).toList();
        assertThat(ids).containsExactly(disputa.getId());
        assertThat(ids).doesNotContain(reservada.getId(), recibida.getId(), cancelada.getId());

        JsonNode item = objectMapper.readTree(resultado.getResponse().getContentAsString()).get(0);
        assertThat(item.get("id").asLong()).isEqualTo(disputa.getId());
        assertThat(item.get("estado").asText()).isEqualTo("disputa");
        assertThat(item.get("precioSnapshot").asLong()).isEqualTo(disputa.getPrecioSnapshot());
        Transaccion disputaReleida = transaccionRepository.findById(disputa.getId()).orElseThrow();
        assertThat(ZonedDateTime.parse(item.get("fechaReservada").asText()).toInstant())
                .isEqualTo(disputaReleida.getFechaReservada().toInstant());
        assertThat(item.get("publicacionDescripcion").asText())
                .isEqualTo(publicacionDisputada.getDescripcion());
    }

    /**
     * Verifica {@code GET /admin/usuarios/bloqueados}: devuelve únicamente usuarios reales con al
     * menos un registro {@code login_attempts.intentos >= 12}, deduplicados por email, con el par
     * mínimo {@code usuarioId}/{@code email}. Excluye: usuarios sin bloqueo permanente (intentos
     * menores a 12), usuarios sin registros de intentos, y emails presentes en
     * {@code login_attempts} que no corresponden a ninguna cuenta real en {@code usuarios}.
     *
     * @throws Exception si falla la interacción HTTP o el parseo de la respuesta
     */
    @Test
    @Order(3)
    @DisplayName("GET /admin/usuarios/bloqueados devuelve solo cuentas reales con intentos >= 12, deduplicadas por email")
    void usuariosBloqueados_AdminVeSoloCuentasRealesConBloqueoPermanente() throws Exception {
        // Usuario real con bloqueo permanente desde dos IPs distintas: debe aparecer UNA vez.
        loginAttemptRepository.save(new LoginAttempt(vendedor.getEmail(), "10.0.0.1", 12,
                com.easymarket.marketplace.service.RateLimitingService.FECHA_BLOQUEO_PERMANENTE));
        loginAttemptRepository.save(new LoginAttempt(vendedor.getEmail(), "10.0.0.2", 14,
                com.easymarket.marketplace.service.RateLimitingService.FECHA_BLOQUEO_PERMANENTE));

        // Usuario real con intentos por debajo del umbral de bloqueo permanente: NO debe aparecer.
        loginAttemptRepository.save(new LoginAttempt(usuarioRegular.getEmail(), "10.0.0.3", 5, null));

        // Email sin cuenta real en usuarios, con intentos >= 12: NO debe aparecer (no es una cuenta).
        loginAttemptRepository.save(new LoginAttempt("huérfano.bloqueado@easymarket.com", "10.0.0.4", 12,
                com.easymarket.marketplace.service.RateLimitingService.FECHA_BLOQUEO_PERMANENTE));

        // Usuario real sin ningún registro de intentos: NO debe aparecer.
        Usuario usuarioSinIntentos = guardarUsuario("sin.intentos." + System.nanoTime() + "@easymarket.com", Rol.USUARIO);
        Cookie cookieAdmin = obtenerCookieJwtPostLogin(admin);

        MvcResult resultado = mockMvc.perform(get("/admin/usuarios/bloqueados").cookie(cookieAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andReturn();

        JsonNode items = objectMapper.readTree(resultado.getResponse().getContentAsString());
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("usuarioId").asLong()).isEqualTo(vendedor.getId());
        assertThat(items.get(0).get("email").asText()).isEqualTo(vendedor.getEmail());

        List<String> emails = items.findValuesAsText("email");
        assertThat(emails).containsExactly(vendedor.getEmail());
        assertThat(emails).doesNotContain(usuarioRegular.getEmail(), usuarioSinIntentos.getEmail(),
                "huérfano.bloqueado@easymarket.com");
    }

    /**
     * Verifica {@code GET /admin/tablero} en sus dos frentes: (a) sobre una base sin datos
     * devuelve ceros en todos los campos (no {@code null}, no error); (b) con datos controlados
     * siembra publicaciones, transacciones en todos los estados relevantes, movimientos positivos
     * y no positivos, y bloqueos permanentes, y verifica que cada campo suma exactamente lo
     * definido por plan.md: el volumen incluye solo los cuatro estados escrow (una
     * {@code cancelada} NO suma), los fondos liberados suman solo movimientos {@code monto > 0} y
     * las finalizadas cuentan solo los tres estados finales.
     *
     * <p>Este escenario corre al final ({@code @Order(4)}): el caso vacío se verifica antes de
     * sembrar y los movimientos append-only que siembra no contaminan ningún escenario posterior.</p>
     *
     * @throws Exception si falla la interacción HTTP o el parseo de la respuesta
     */
    @Test
    @Order(4)
    @DisplayName("GET /admin/tablero: sin datos devuelve ceros y con datos controlados suma exacta por campo")
    void tablero_Admin_SumaExactaYCasoVacioConDatosControlados() throws Exception {
        Cookie cookieAdmin = obtenerCookieJwtPostLogin(admin);

        // (a) Caso vacío: la base fue limpiada en @BeforeEach y aún no se siembran datos.
        mockMvc.perform(get("/admin/tablero").cookie(cookieAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicacionesPendientes").value(0))
                .andExpect(jsonPath("$.disputasAbiertas").value(0))
                .andExpect(jsonPath("$.cuentasBloqueadas").value(0))
                .andExpect(jsonPath("$.volumenEscrowCentavos").value(0))
                .andExpect(jsonPath("$.fondosLiberadosCentavos").value(0))
                .andExpect(jsonPath("$.transaccionesFinalizadas").value(0));

        // (b) Datos controlados.
        // Publicaciones: dos pendientes de revisión y una aprobada (no pendiente).
        Publicacion pendiente1 = guardarPublicacion(vendedor, "Pendiente uno");
        Publicacion pendiente2 = guardarPublicacion(vendedor, "Pendiente dos");
        Publicacion aprobada = guardarPublicacion(vendedor, "Aprobada para no sumar");
        aprobada.setEstado(EstadoPublicacion.APROBADA);
        publicacionRepository.saveAndFlush(aprobada);

        // Transacciones: los 4 estados escrow, los 3 finales y una cancelada que NO debe sumar.
        Transaccion tReservada = guardarTransaccionPrecio(comprador, pendiente1, EstadoTransaccion.RESERVADA, 1_000L);
        Transaccion tEnviado = guardarTransaccionPrecio(comprador, pendiente1, EstadoTransaccion.ENVIADO, 2_000L);
        Transaccion tEntregado = guardarTransaccionPrecio(comprador, pendiente2, EstadoTransaccion.ENTREGADO, 3_000L);
        Transaccion tDisputa = guardarTransaccionPrecio(comprador, pendiente2, EstadoTransaccion.DISPUTA, 4_000L);
        Transaccion tRecibido = guardarTransaccionPrecio(comprador, aprobada, EstadoTransaccion.RECIBIDO, 5_000L);
        Transaccion tRecibidoSR = guardarTransaccionPrecio(comprador, aprobada, EstadoTransaccion.RECIBIDO_SIN_RESPUESTA, 6_000L);
        Transaccion tCompletada = guardarTransaccionPrecio(comprador, aprobada, EstadoTransaccion.COMPLETADA, 7_000L);
        Transaccion tCancelada = guardarTransaccionPrecio(comprador, aprobada, EstadoTransaccion.CANCELADA, 8_000L);

        // Movimientos del ledger: dos positivos, uno cero y uno negativo; solo los positivos suman.
        guardarMovimiento(tRecibido, vendedor, 1_500L);
        guardarMovimiento(tCompletada, vendedor, 2_500L);
        guardarMovimiento(tEntregado, vendedor, 0L);
        guardarMovimiento(tCancelada, vendedor, -500L);

        // Bloqueo permanente de una cuenta real (vendedor).
        loginAttemptRepository.save(new LoginAttempt(vendedor.getEmail(), "10.0.0.9", 12,
                com.easymarket.marketplace.service.RateLimitingService.FECHA_BLOQUEO_PERMANENTE));

        mockMvc.perform(get("/admin/tablero").cookie(cookieAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicacionesPendientes").value(2))
                .andExpect(jsonPath("$.disputasAbiertas").value(1))
                .andExpect(jsonPath("$.cuentasBloqueadas").value(1))
                .andExpect(jsonPath("$.volumenEscrowCentavos").value(1_000L + 2_000L + 3_000L + 4_000L))
                .andExpect(jsonPath("$.fondosLiberadosCentavos").value(1_500L + 2_500L))
                .andExpect(jsonPath("$.transaccionesFinalizadas").value(3));

        // Verificación contra la base real (sin mocks): las sumas sembradas existen en PostgreSQL.
        assertThat(transaccionRepository.findAll()).hasSize(8);
        assertThat(movimientoSaldoRepository.findAll()).hasSize(4);
        assertThat(publicacionRepository.findAll()).hasSize(3);
        assertThat(tCancelada.getEstado()).isEqualTo(EstadoTransaccion.CANCELADA);
    }

    /**
     * Persiste un usuario auxiliar de prueba con el rol solicitado (política PHA12TSK06: en esta
     * clase solo se invoca con {@link Rol#USUARIO}; el ADMIN único nunca nace aquí).
     *
     * @param email correo único del usuario
     * @param rol rol persistido para la identidad
     * @return usuario persistido
     */
    private Usuario guardarUsuario(String email, Rol rol) {
        return usuarioRepository.save(new Usuario(email, passwordEncoder.encode(PASSWORD_RAW), rol, 0L,
                ZonedDateTime.now()));
    }

    /**
     * Persiste una publicación del vendedor indicado con la descripción dada, stock 1 y precio
     * fijo de 125.000 centavos en estado inicial {@code pendiente_revisión}.
     *
     * @param vendedor dueño persistido de la publicación
     * @param descripcion texto literal que se verificará contra el DTO
     * @return publicación persistida
     */
    private Publicacion guardarPublicacion(Usuario vendedor, String descripcion) {
        return publicacionRepository.save(new Publicacion(vendedor, categoria, subcategoria,
                125_000L, 1, descripcion));
    }

    /**
     * Crea una transacción de la publicación del vendedor para el comprador indicado con precio
     * fijo de 125.000 centavos y el estado solicitado.
     *
     * @param comprador comprador persistido de la transacción
     * @param publicacion publicación adquirida
     * @param estado estado de la transacción
     * @return transacción persistida
     */
    private Transaccion guardarTransaccion(Usuario comprador, Publicacion publicacion, EstadoTransaccion estado) {
        return guardarTransaccionPrecio(comprador, publicacion, estado, 125_000L);
    }

    /**
     * Crea una transacción con precio explícito en centavos y el estado solicitado, con la
     * reserva en el instante actual.
     *
     * @param comprador comprador persistido de la transacción
     * @param publicacion publicación adquirida
     * @param estado estado de la transacción
     * @param precioSnapshot precio en centavos de la transacción
     * @return transacción persistida
     */
    private Transaccion guardarTransaccionPrecio(Usuario comprador, Publicacion publicacion,
                                                 EstadoTransaccion estado, long precioSnapshot) {
        Transaccion transaccion = new Transaccion(comprador, publicacion, precioSnapshot,
                ZonedDateTime.now(ZONA_LIMA));
        transaccion.setEstado(estado);
        return transaccionRepository.saveAndFlush(transaccion);
    }

    /**
     * Persiste un movimiento de saldo del ledger para la transacción y el vendedor indicados,
     * con el monto (positivo, cero o negativo) solicitado.
     *
     * @param transaccion transacción que origina el movimiento
     * @param vendedor vendedor del movimiento
     * @param monto monto en centavos
     * @return movimiento persistido
     */
    private MovimientoSaldo guardarMovimiento(Transaccion transaccion, Usuario vendedor, long monto) {
        return movimientoSaldoRepository.save(new MovimientoSaldo(transaccion, vendedor, monto,
                ZonedDateTime.now(ZONA_LIMA)));
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
}
