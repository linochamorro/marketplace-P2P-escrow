package com.easymarket.marketplace.controller;

import com.easymarket.marketplace.dto.LoginRequestDto;
import com.easymarket.marketplace.model.Categoria;
import com.easymarket.marketplace.model.EstadoTransaccion;
import com.easymarket.marketplace.model.Notificacion;
import com.easymarket.marketplace.model.Publicacion;
import com.easymarket.marketplace.model.Rol;
import com.easymarket.marketplace.model.Subcategoria;
import com.easymarket.marketplace.model.Transaccion;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.CategoriaRepository;
import com.easymarket.marketplace.repository.NotificacionRepository;
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
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración del endpoint de listado de notificaciones in-app
 * {@code GET /notificaciones} (PHA04TSK16, Story 7b).
 *
 * <p>Usa PostgreSQL real mediante Testcontainers e identidades emitidas por el login JWT real;
 * la cookie {@code jwt} obtenida por HTTP autentica cada petición. Verifica el criterio de
 * aceptación de la Story 7b: un usuario (comprador o vendedor) recibe SOLO sus propias
 * notificaciones — nunca las de otro destinatario — sin filtros ni paginación (no existen en el
 * contrato), mapeando cada campo del DTO contra el valor real persistido en base de datos y
 * ordenando el listado por fecha descendente. Verifica además que el endpoint queda protegido
 * por la cadena de seguridad (403 sin cookie JWT) tal como lo garantiza
 * {@code anyRequest().authenticated()} de {@code SecurityConfig}.</p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=clave-secreta-pruebas-notificaciones-minimo-32-caracteres",
    "ADMIN_EMAIL=admin.seed@easymarket.com",
    "ADMIN_PASSWORD_HASH=$2a$10$R9h/cIPz0gi.URNNXRkh2OPST9/PgBkqquzi.Ss7KIUgO2t0jWMUW"
})
class NotificacionControllerIntegrationTests {

    private static final String PASSWORD_RAW = "PasswordSeguro123!";
    private static final long PRECIO_CENTAVOS = 125_000L;
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
    @Autowired private NotificacionRepository notificacionRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;
    private Usuario usuarioA;
    private Usuario usuarioB;
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
        usuarioA = guardarUsuario("usuario.a.notificaciones." + sufijo + "@easymarket.com");
        usuarioB = guardarUsuario("usuario.b.notificaciones." + sufijo + "@easymarket.com");
        categoria = categoriaRepository.save(new Categoria("Categoría " + sufijo));
        subcategoria = subcategoriaRepository.save(new Subcategoria(categoria, "Subcategoría " + sufijo));
    }

    /**
     * Verifica el criterio central de la Story 7b: cuando existen notificaciones para múltiples
     * usuarios, {@code GET /notificaciones} autenticado como el usuario A devuelve 200 OK con
     * únicamente las notificaciones de A (el array NO contiene ninguna de B), y cada campo del
     * DTO mapea el valor real persistido en base de datos (mensaje, tipo, leida y createdAt).
     *
     * <p>Se siembran dos notificaciones para A — una sin transacción y una asociada a una
     * transacción reservada— y dos para B. La respuesta se verifica contra el arreglo en orden
     * descendente (la asociada a transacción es la más reciente) y además se comprueba que las
     * notificaciones de B existen en BD, de modo que la ausencia en la respuesta solo puede
     * atribuirse al aislamiento por destinatario del endpoint.</p>
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("GET /notificaciones devuelve solo las del usuario autenticado y mapea los campos reales de BD")
    void obtener_UsuarioConNotificaciones_Retorna200SoloLasSuyasConCamposReales() throws Exception {
        ZonedDateTime tAntigua = ahora();
        ZonedDateTime tReciente = tAntigua.plusMinutes(5L);

        Notificacion notifA1 = notificacionRepository.save(
                new Notificacion(usuarioA, "Aviso diario: tu transacción sigue abierta", "AVISO_TRANSACCION_ABIERTA", tAntigua));

        Transaccion transaccion = guardarTransaccionReservada();
        Notificacion notifA2 = notificacionRepository.save(
                new Notificacion(usuarioA, transaccion, "Aviso: envío pendiente de respuesta", "AVISO_ENVIO_PENDIENTE", tReciente));

        Notificacion notifB1 = notificacionRepository.save(
                new Notificacion(usuarioB, "Aviso de B: no debe aparecer 1", "AVISO_TRANSACCION_ABIERTA", ahora()));
        Notificacion notifB2 = notificacionRepository.save(
                new Notificacion(usuarioB, "Aviso de B: no debe aparecer 2", "AVISO_ENVIO_PENDIENTE", ahora()));

        Cookie cookieUsuarioA = obtenerCookieJwtPostLogin(usuarioA);

        MvcResult result = mockMvc.perform(get("/notificaciones").cookie(cookieUsuarioA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andReturn();

        List<Map<String, Object>> items = leerItems(result);

        // Orden descendente por createdAt: la notificación asociada a transacción (más reciente) primero
        assertThat(((Number) items.get(0).get("id")).longValue()).isEqualTo(notifA2.getId());
        assertThat(items.get(0).get("mensaje")).isEqualTo("Aviso: envío pendiente de respuesta");
        assertThat(items.get(0).get("tipo")).isEqualTo("AVISO_ENVIO_PENDIENTE");
        assertThat(items.get(0).get("leida")).isEqualTo(false);
        assertThat(instantDe(items.get(0).get("createdAt"))).isEqualTo(tReciente.toInstant());
        assertThat(((Number) items.get(0).get("transaccionId")).longValue()).isEqualTo(transaccion.getId());

        assertThat(((Number) items.get(1).get("id")).longValue()).isEqualTo(notifA1.getId());
        assertThat(items.get(1).get("mensaje")).isEqualTo("Aviso diario: tu transacción sigue abierta");
        assertThat(items.get(1).get("tipo")).isEqualTo("AVISO_TRANSACCION_ABIERTA");
        assertThat(items.get(1).get("leida")).isEqualTo(false);
        assertThat(instantDe(items.get(1).get("createdAt"))).isEqualTo(tAntigua.toInstant());
        assertThat(items.get(1).get("transaccionId")).isNull();

        // Aislamiento por destinatario: ninguna notificación de B aparece en la respuesta
        assertThat(items)
                .extracting(item -> ((Number) item.get("id")).longValue())
                .doesNotContain(notifB1.getId(), notifB2.getId());

        // Sanidad: las notificaciones de B existen en BD; su ausencia es del endpoint, no del fixture
        assertThat(notificacionRepository.findAll())
                .extracting(Notificacion::getId)
                .contains(notifB1.getId(), notifB2.getId());
    }

    /**
     * Verifica el criterio de la Story 7b para el caso sin datos: un usuario autenticado sin
     * notificaciones recibe 200 OK con un array JSON vacío {@code []}.
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("GET /notificaciones sin notificaciones del usuario autenticado retorna 200 con array vacío")
    void obtener_UsuarioSinNotificaciones_Retorna200ArrayVacio() throws Exception {
        // Solo el usuario B tiene notificaciones; A no tiene ninguna
        notificacionRepository.save(
                new Notificacion(usuarioB, "Aviso de B", "AVISO_TRANSACCION_ABIERTA", ahora()));

        Cookie cookieUsuarioA = obtenerCookieJwtPostLogin(usuarioA);

        mockMvc.perform(get("/notificaciones").cookie(cookieUsuarioA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /**
     * Verifica la protección de la cadena de seguridad: {@code GET /notificaciones} sin cookie JWT
     * retorna 403 Forbidden (la ruta queda cubierta por {@code anyRequest().authenticated()} de
     * {@code SecurityConfig}, sin requestMatcher nuevo).
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("GET /notificaciones sin autenticación retorna 403 Forbidden")
    void obtener_SinAutenticacion_Retorna403Forbidden() throws Exception {
        mockMvc.perform(get("/notificaciones"))
                .andExpect(status().isForbidden());
    }

    /**
     * Verifica el ordenamiento exigido por la tarea (patrón
     * {@code findByUsuarioIdOrderByCreatedAtDescIdDesc}): con varias notificaciones de un mismo
     * usuario, la respuesta las devuelve por {@code createdAt} descendente y, ante timestamps
     * idénticos, por {@code id} descendente como desempate.
     *
     * <p>Se siembran tres notificaciones para A: la más antigua (t-2h), y dos con el mismo
     * timestamp reciente (t0) insertadas en orden [X2, X3], de modo que X3 tiene id mayor que X2
     * y debe aparecer antes por el desempate {@code id desc}. El orden esperado es [X3, X2, X1].</p>
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("GET /notificaciones ordena por createdAt desc con desempate por id desc")
    void obtener_VariasNotificaciones_Retorna200OrdenDescendenteConDesempatePorId() throws Exception {
        ZonedDateTime tAntigua = ahora().minusHours(2L);
        ZonedDateTime tReciente = ahora();

        Notificacion x1 = notificacionRepository.save(
                new Notificacion(usuarioA, "La más antigua", "AVISO_TRANSACCION_ABIERTA", tAntigua));
        Notificacion x2 = notificacionRepository.save(
                new Notificacion(usuarioA, "Reciente insertada segunda", "AVISO_ENVIO_PENDIENTE", tReciente));
        Notificacion x3 = notificacionRepository.save(
                new Notificacion(usuarioA, "Reciente insertada tercera", "AVISO_ENVIO_PENDIENTE", tReciente));

        Cookie cookieUsuarioA = obtenerCookieJwtPostLogin(usuarioA);

        MvcResult result = mockMvc.perform(get("/notificaciones").cookie(cookieUsuarioA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andReturn();

        List<Map<String, Object>> items = leerItems(result);

        assertThat(((Number) items.get(0).get("id")).longValue()).isEqualTo(x3.getId());
        assertThat(((Number) items.get(1).get("id")).longValue()).isEqualTo(x2.getId());
        assertThat(((Number) items.get(2).get("id")).longValue()).isEqualTo(x1.getId());

        assertThat(instantDe(items.get(0).get("createdAt"))).isEqualTo(tReciente.toInstant());
        assertThat(instantDe(items.get(1).get("createdAt"))).isEqualTo(tReciente.toInstant());
        assertThat(instantDe(items.get(2).get("createdAt"))).isEqualTo(tAntigua.toInstant());
    }

    /**
     * Persiste un usuario regular de prueba con la contraseña compartida de la clase.
     *
     * @param email correo único del usuario
     * @return usuario persistido
     */
    private Usuario guardarUsuario(String email) {
        return usuarioRepository.save(new Usuario(email, passwordEncoder.encode(PASSWORD_RAW), Rol.USUARIO,
                0L, ZonedDateTime.now(ZONA_LIMA)));
    }

    /**
     * Persiste una transacción reservada sobre una publicación en stock 0 (condición física de
     * una unidad ya consumida por el webhook al reservar), usada como asociación opcional de una
     * notificación con {@code transaccionId}.
     *
     * @return transacción persistida en estado {@code RESERVADA}
     */
    private Transaccion guardarTransaccionReservada() {
        Publicacion publicacion = publicacionRepository.save(new Publicacion(usuarioB, categoria, subcategoria,
                PRECIO_CENTAVOS, 0, "Artículo de prueba"));
        Transaccion transaccion = new Transaccion(usuarioA, publicacion, PRECIO_CENTAVOS, ZonedDateTime.now(ZONA_LIMA));
        transaccion.setEstado(EstadoTransaccion.RESERVADA);
        return transaccionRepository.saveAndFlush(transaccion);
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
     * Lee el cuerpo JSON de la respuesta como lista de ítems planos.
     *
     * @param result resultado de la petición HTTP ya validada
     * @return lista de mapas con los campos del DTO por notificación
     * @throws Exception si el cuerpo no puede leerse o parsearse
     */
    private List<Map<String, Object>> leerItems(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$");
    }

    /**
     * Convierte la representación ISO-8601 de {@code createdAt} de la respuesta a instante.
     *
     * @param iso valor textual del campo {@code createdAt} serializado por Jackson 3
     * @return instante representado por el ISO-8601 recibido
     */
    private static Instant instantDe(Object iso) {
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