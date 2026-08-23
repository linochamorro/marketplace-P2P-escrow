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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración del API de notificaciones in-app {@code GET /notificaciones} y
 * {@code PATCH /notificaciones/{id}/leer} (PHA04TSK16 Story 7b; PHA09TSK05 recuperado en
 * PHA12TSK04).
 *
 * <p>Usa PostgreSQL real mediante Testcontainers e identidades emitidas por el login JWT real;
 * la cookie {@code jwt} obtenida por HTTP autentica cada petición. Verifica el contrato vigente:</p>
 * <ul>
 *   <li>{@code GET /notificaciones} delega en
 *       {@code NotificacionService.listarPorUsuarioYRol}: devuelve SOLO las notificaciones del
 *       usuario autenticado cuyo {@code tipo} pertenece a la lista de su rol en el JWT (ADMIN:
 *       moderación/disputas; USER: compra/venta/envío/disputa), aislamiento por destinatario,
 *       mapeo campo a campo del DTO contra el valor persistido y orden descendente con desempate
 *       por ID.</li>
 *   <li>{@code PATCH /notificaciones/{id}/leer} marca {@code leida=true} de forma idempotente
 *       (segunda llamada retorna la misma entidad), 404 si la notificación no existe, 403 si el
 *       autenticado no es su destinatario.</li>
 * </ul>
 *
 * <p>Los tipos sembrados son todos válidos frente al CHECK {@code chk_notificaciones_tipo_valido}
 * de V19 (la corrección de los fixtures que insertaban {@code AVISO_ENVIO_PENDIENTE}, inválido,
 * corresponde a PHA12TSK04) y, para las aserciones de contenido del listado, pertenecen a la lista
 * del rol correspondiente para sobrevivir al filtro. La ruta queda protegida por
 * {@code anyRequest().authenticated()} de {@code SecurityConfig} (sin requestMatcher nuevo).</p>
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
     * Verifica el criterio central de aislamiento por destinatario: cuando existen notificaciones
     * para múltiples usuarios, {@code GET /notificaciones} autenticado como el usuario A devuelve
     * 200 OK con únicamente las notificaciones de A (el array NO contiene ninguna de B), y cada
     * campo del DTO mapea el valor real persistido en base de datos (mensaje, tipo, leida y
     * createdAt).
     *
     * <p>Se siembran dos notificaciones para A — una sin transacción y una asociada a una
     * transacción reservada, ambas de tipos de la lista USER para sobrevivir al filtro por rol—
     * y dos para B. La respuesta se verifica contra el arreglo en orden descendente (la asociada
     * a transacción es la más reciente) y además se comprueba que las notificaciones de B existen
     * en BD, de modo que la ausencia en la respuesta solo puede atribuirse al aislamiento por
     * destinatario del endpoint.</p>
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("GET /notificaciones devuelve solo las del usuario autenticado y mapea los campos reales de BD")
    void obtener_UsuarioConNotificaciones_Retorna200SoloLasSuyasConCamposReales() throws Exception {
        ZonedDateTime tAntigua = ahora();
        ZonedDateTime tReciente = tAntigua.plusMinutes(5L);

        Notificacion notifA1 = notificacionRepository.save(
                new Notificacion(usuarioA, "Aviso diario: tu transacción sigue abierta", "PUBLICACION_APROBADA_RECHAZADA", tAntigua));

        Transaccion transaccion = guardarTransaccionReservada();
        Notificacion notifA2 = notificacionRepository.save(
                new Notificacion(usuarioA, transaccion, "Aviso: envío pendiente de respuesta", "ENVIO_MARCADO", tReciente));

        Notificacion notifB1 = notificacionRepository.save(
                new Notificacion(usuarioB, "Aviso de B: no debe aparecer 1", "COMPRA_CONFIRMADA", ahora()));
        Notificacion notifB2 = notificacionRepository.save(
                new Notificacion(usuarioB, "Aviso de B: no debe aparecer 2", "DISPUTA_ABIERTA", ahora()));

        Cookie cookieUsuarioA = obtenerCookieJwtPostLogin(usuarioA);

        MvcResult result = mockMvc.perform(get("/notificaciones").cookie(cookieUsuarioA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andReturn();

        List<Map<String, Object>> items = leerItems(result);

        // Orden descendente por createdAt: la notificación asociada a transacción (más reciente) primero
        assertThat(((Number) items.get(0).get("id")).longValue()).isEqualTo(notifA2.getId());
        assertThat(items.get(0).get("mensaje")).isEqualTo("Aviso: envío pendiente de respuesta");
        assertThat(items.get(0).get("tipo")).isEqualTo("ENVIO_MARCADO");
        assertThat(items.get(0).get("leida")).isEqualTo(false);
        assertThat(instantDe(items.get(0).get("createdAt"))).isEqualTo(tReciente.toInstant());
        assertThat(((Number) items.get(0).get("transaccionId")).longValue()).isEqualTo(transaccion.getId());

        assertThat(((Number) items.get(1).get("id")).longValue()).isEqualTo(notifA1.getId());
        assertThat(items.get(1).get("mensaje")).isEqualTo("Aviso diario: tu transacción sigue abierta");
        assertThat(items.get(1).get("tipo")).isEqualTo("PUBLICACION_APROBADA_RECHAZADA");
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
     * Verifica el caso sin datos para un usuario autenticado: sin notificaciones propias recibe
     * 200 OK con un array JSON vacío {@code []}.
     *
     * <p>La única notificación sembrada pertenece a B y es de un tipo de la lista USER, de modo
     * que el array vacío de A se debe a que A no tiene notificaciones y no al filtro por rol.</p>
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("GET /notificaciones sin notificaciones del usuario autenticado retorna 200 con array vacío")
    void obtener_UsuarioSinNotificaciones_Retorna200ArrayVacio() throws Exception {
        // Solo el usuario B tiene notificaciones (tipo de lista USER); A no tiene ninguna
        notificacionRepository.save(
                new Notificacion(usuarioB, "Aviso de B", "COMPRA_CONFIRMADA", ahora()));

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
     * Verifica el ordenamiento exigido por el contrato ({@code createdAt} descendente con
     * desempate por {@code id} descendente): con varias notificaciones de un mismo usuario, la
     * respuesta las devuelve en ese orden exacto.
     *
     * <p>Se siembran tres notificaciones para A de tipos de la lista USER: la más antigua (t-2h),
     * y dos con el mismo timestamp reciente (t0) insertadas en orden [X2, X3], de modo que X3
     * tiene id mayor que X2 y debe aparecer antes por el desempate {@code id desc}. El orden
     * esperado es [X3, X2, X1].</p>
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("GET /notificaciones ordena por createdAt desc con desempate por id desc")
    void obtener_VariasNotificaciones_Retorna200OrdenDescendenteConDesempatePorId() throws Exception {
        ZonedDateTime tAntigua = ahora().minusHours(2L);
        ZonedDateTime tReciente = ahora();

        Notificacion x1 = notificacionRepository.save(
                new Notificacion(usuarioA, "La más antigua", "RESPUESTA_USUARIO_PENDIENTE", tAntigua));
        Notificacion x2 = notificacionRepository.save(
                new Notificacion(usuarioA, "Reciente insertada segunda", "ENVIO_MARCADO", tReciente));
        Notificacion x3 = notificacionRepository.save(
                new Notificacion(usuarioA, "Reciente insertada tercera", "ENVIO_MARCADO", tReciente));

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
     * Verifica el filtro por rol de la story PHA09TSK05 (recuperado en PHA12TSK04) para el rol
     * USER: {@code GET /notificaciones} devuelve SOLO las notificaciones cuyo tipo pertenece a la
     * lista del rol del JWT (compra/venta/envío/disputa) y excluye tipos válidos en base de datos
     * pero ajenos a esa lista.
     *
     * <p>Se siembran para A una notificación visible ({@code COMPRA_CONFIRMADA}) y tres fuera de
     * la lista USER pero válidas frente al CHECK de V19 ({@code AVISO_TRANSACCION_ABIERTA},
     * {@code ENVIO_PENDIENTE_48H} y {@code NUEVA_PUBLICACION_PENDIENTE}); la respuesta debe
     * contener únicamente la visible aunque todas sean del propio destinatario.</p>
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("GET /notificaciones como USER excluye tipos válidos que no pertenecen a su rol")
    void obtener_UsuarioTiposFueraDeSuRol_NoAparecenEnRespuesta() throws Exception {
        Notificacion visible = notificacionRepository.save(
                new Notificacion(usuarioA, "Tu compra fue confirmada", "COMPRA_CONFIRMADA", ahora()));
        Notificacion ajena1 = notificacionRepository.save(
                new Notificacion(usuarioA, "Tu transacción sigue abierta", "AVISO_TRANSACCION_ABIERTA", ahora()));
        Notificacion ajena2 = notificacionRepository.save(
                new Notificacion(usuarioA, "Tu envío sigue pendiente", "ENVIO_PENDIENTE_48H", ahora()));
        Notificacion ajena3 = notificacionRepository.save(
                new Notificacion(usuarioA, "Nueva publicación pendiente", "NUEVA_PUBLICACION_PENDIENTE", ahora()));

        Cookie cookieUsuarioA = obtenerCookieJwtPostLogin(usuarioA);

        MvcResult result = mockMvc.perform(get("/notificaciones").cookie(cookieUsuarioA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andReturn();

        List<Map<String, Object>> items = leerItems(result);

        assertThat(((Number) items.get(0).get("id")).longValue()).isEqualTo(visible.getId());

        // Sanidad: las excluidas existen en BD y pertenecen al propio A; su ausencia es del filtro por rol
        assertThat(notificacionRepository.findAll())
                .extracting(Notificacion::getId)
                .contains(visible.getId(), ajena1.getId(), ajena2.getId(), ajena3.getId());
    }

    /**
     * Verifica el filtro por rol para el rol ADMIN: {@code GET /notificaciones} autenticado como
     * el administrador devuelve SOLO los tipos de moderación/disputas
     * ({@code PUBLICACION_PENDIENTE_APROBAR}, {@code DISPUTA_PENDIENTE_RESOLVER}) y excluye los
     * tipos de compra/venta/envío/disputa y los avisos diarios.
     *
     * <p>Como la contraseña en claro del admin sembrado por V6 no es conocida por la suite, este
     * escenario reconstruye el estado siguiendo el patrón de
     * {@code AdminConsultaControllerIntegrationTests}: limpia las tablas con dependencias de FK y
     * crea EXACTAMENTE UN usuario con rol {@code ADMIN} (la invariante de admin único se conserva
     * durante todo el test porque el borrado previo elimina también el admin sembrado).</p>
     *
     * @throws Exception si falla la interacción HTTP o la preparación del escenario
     */
    @Test
    @DisplayName("GET /notificaciones como ADMIN devuelve solo moderación/disputas de su rol")
    void obtener_AdminVeSoloModeracionYDisputas_Retorna200FiltradasPorRol() throws Exception {
        prepararEscenarioAdminUnico();

        String sufijo = Long.toUnsignedString(System.nanoTime());
        Usuario admin = guardarUsuario("admin.notificaciones.filtro." + sufijo + "@easymarket.com", Rol.ADMIN);

        ZonedDateTime tAntigua = ahora().minusMinutes(2L);
        ZonedDateTime tReciente = ahora().minusMinutes(1L);
        Notificacion moderacion = notificacionRepository.save(
                new Notificacion(admin, "Publicación pendiente de aprobar", "PUBLICACION_PENDIENTE_APROBAR", tAntigua));
        Notificacion disputa = notificacionRepository.save(
                new Notificacion(admin, "Disputa pendiente de resolver", "DISPUTA_PENDIENTE_RESOLVER", tReciente));
        Notificacion ajena1 = notificacionRepository.save(
                new Notificacion(admin, "Compra confirmada fuera del rol ADMIN", "COMPRA_CONFIRMADA", ahora()));
        Notificacion ajena2 = notificacionRepository.save(
                new Notificacion(admin, "Aviso diario fuera del rol ADMIN", "AVISO_TRANSACCION_ABIERTA", ahora()));

        Cookie cookieAdmin = obtenerCookieJwtPostLogin(admin);

        MvcResult result = mockMvc.perform(get("/notificaciones").cookie(cookieAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andReturn();

        List<Map<String, Object>> items = leerItems(result);

        // Orden descendente: la disputa (más reciente) primero
        assertThat(((Number) items.get(0).get("id")).longValue()).isEqualTo(disputa.getId());
        assertThat(items.get(0).get("tipo")).isEqualTo("DISPUTA_PENDIENTE_RESOLVER");
        assertThat(((Number) items.get(1).get("id")).longValue()).isEqualTo(moderacion.getId());
        assertThat(items.get(1).get("tipo")).isEqualTo("PUBLICACION_PENDIENTE_APROBAR");

        // Sanidad: las excluidas existen en BD y pertenecen al propio admin
        assertThat(notificacionRepository.findAll())
                .extracting(Notificacion::getId)
                .contains(moderacion.getId(), disputa.getId(), ajena1.getId(), ajena2.getId());
    }

    /**
     * Verifica {@code PATCH /notificaciones/{id}/leer} para el destinatario: 200 OK con el DTO de
     * la entidad actualizada ({@code leida=true}), persistencia efectiva en BD e idempotencia —
     * una segunda llamada sobre la misma notificación retorna 200 con la misma entidad ya leída,
     * sin errores ni duplicados.
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("PATCH /notificaciones/{id}/leer marca leida=true, persiste y es idempotente")
    void marcarComoLeida_Propietario_Retorna200LeidaTrueYEsIdempotente() throws Exception {
        Notificacion notificacion = notificacionRepository.save(
                new Notificacion(usuarioA, "Tu compra fue confirmada", "COMPRA_CONFIRMADA", ahora()));
        Cookie cookieUsuarioA = obtenerCookieJwtPostLogin(usuarioA);

        mockMvc.perform(patch("/notificaciones/" + notificacion.getId() + "/leer").cookie(cookieUsuarioA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(notificacion.getId()))
                .andExpect(jsonPath("$.leida").value(true))
                .andExpect(jsonPath("$.tipo").value("COMPRA_CONFIRMADA"))
                .andExpect(jsonPath("$.mensaje").value("Tu compra fue confirmada"));

        Notificacion releida = notificacionRepository.findById(notificacion.getId()).orElseThrow();
        assertThat(releida.isLeida()).isTrue();

        // Idempotencia: segunda llamada sobre la misma notificación
        mockMvc.perform(patch("/notificaciones/" + notificacion.getId() + "/leer").cookie(cookieUsuarioA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(notificacion.getId()))
                .andExpect(jsonPath("$.leida").value(true));

        assertThat(notificacionRepository.findById(notificacion.getId()).orElseThrow().isLeida()).isTrue();
        // La idempotencia no duplica filas: el usuario A de este escenario conserva EXACTAMENTE
        // una notificación (el conteo global no sirve aquí porque el contenedor PostgreSQL es
        // compartido por todos los métodos de la clase y otros escenarios siembran sus filas).
        List<Notificacion> propiasDeA = notificacionRepository.findAll().stream()
                .filter(n -> n.getUsuario().getId().equals(usuarioA.getId()))
                .toList();
        assertThat(propiasDeA).hasSize(1);
    }

    /**
     * Verifica el 404 de {@code PATCH /notificaciones/{id}/leer} para una notificación que no
     * existe: el cuerpo JSON porta el mensaje de dominio servido por
     * {@code GlobalExceptionHandler} (campo {@code mensaje}), no el error genérico del framework.
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("PATCH /notificaciones/{id}/leer sobre notificación inexistente retorna 404 con mensaje")
    void marcarComoLeida_Inexistente_Retorna404ConMensajeDeDominio() throws Exception {
        Cookie cookieUsuarioA = obtenerCookieJwtPostLogin(usuarioA);

        mockMvc.perform(patch("/notificaciones/999999/leer").cookie(cookieUsuarioA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.mensaje").isNotEmpty());
    }

    /**
     * Verifica el 403 de {@code PATCH /notificaciones/{id}/leer} cuando el autenticado no es el
     * destinatario: el usuario B intenta marcar como leída una notificación de A, recibe
     * 403 Forbidden con mensaje de dominio y la notificación permanece SIN mutar
     * ({@code leida=false} releyendo de BD).
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("PATCH /notificaciones/{id}/leer por un tercero retorna 403 y no muta la notificación")
    void marcarComoLeida_DeOtroUsuario_Retorna403YSinMutacion() throws Exception {
        Notificacion notificacionDeA = notificacionRepository.save(
                new Notificacion(usuarioA, "Tu compra fue confirmada", "COMPRA_CONFIRMADA", ahora()));
        Cookie cookieUsuarioB = obtenerCookieJwtPostLogin(usuarioB);

        mockMvc.perform(patch("/notificaciones/" + notificacionDeA.getId() + "/leer").cookie(cookieUsuarioB))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.mensaje").isNotEmpty());

        assertThat(notificacionRepository.findById(notificacionDeA.getId()).orElseThrow().isLeida()).isFalse();
    }

    /**
     * Verifica la protección de la cadena de seguridad para la ruta nueva: {@code PATCH
     * /notificaciones/{id}/leer} sin cookie JWT retorna 403 Forbidden (cubierto por
     * {@code anyRequest().authenticated()} de {@code SecurityConfig}, sin requestMatcher nuevo).
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("PATCH /notificaciones/{id}/leer sin autenticación retorna 403 Forbidden")
    void marcarComoLeida_SinAutenticacion_Retorna403Forbidden() throws Exception {
        Notificacion notificacion = notificacionRepository.save(
                new Notificacion(usuarioA, "Tu compra fue confirmada", "COMPRA_CONFIRMADA", ahora()));

        mockMvc.perform(patch("/notificaciones/" + notificacion.getId() + "/leer"))
                .andExpect(status().isForbidden());
    }

    /**
     * Deja la base de datos en el estado que exige el escenario de admin único: borra las tablas
     * respetando el orden inverso de sus dependencias de FK (notificaciones &rarr; transacciones
     * &rarr; publicaciones &rarr; catálogo &rarr; usuarios), incluido el admin sembrado por V6,
     * de modo que el único {@code ADMIN} existente durante el test sea el que crea el propio
     * escenario (patrón de {@code AdminConsultaControllerIntegrationTests}). Los logs append-only
     * de negocio no se pueblan en esta clase, por lo que el borrado no afecta evidencia alguna.
     */
    private void prepararEscenarioAdminUnico() {
        notificacionRepository.deleteAll();
        transaccionRepository.deleteAll();
        publicacionRepository.deleteAll();
        subcategoriaRepository.deleteAll();
        categoriaRepository.deleteAll();
        usuarioRepository.deleteAll();
    }

    /**
     * Persiste un usuario regular de prueba con la contraseña compartida de la clase.
     *
     * @param email correo único del usuario
     * @return usuario persistido
     */
    private Usuario guardarUsuario(String email) {
        return guardarUsuario(email, Rol.USUARIO);
    }

    /**
     * Persiste un usuario de prueba del rol indicado con la contraseña compartida de la clase.
     *
     * @param email correo único del usuario
     * @param rol rol del usuario ({@link Rol#USUARIO} o {@link Rol#ADMIN})
     * @return usuario persistido
     */
    private Usuario guardarUsuario(String email, Rol rol) {
        return usuarioRepository.save(new Usuario(email, passwordEncoder.encode(PASSWORD_RAW), rol,
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
