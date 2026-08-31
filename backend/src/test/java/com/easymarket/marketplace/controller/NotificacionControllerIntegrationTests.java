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
 *       {@code NotificacionService.listarPorUsuarioYRol}: devuelve las notificaciones del
 *       usuario autenticado cuyo {@code tipo} pertenece a la lista VISIBLE de su rol en el JWT
 *       (desde PHA15TSK06, decisión de Lino 2026-08-30, el listado es un SUPERCONJUNTO del
 *       badge: ADMIN ve moderación/disputas MÁS {@code NUEVA_PUBLICACION_PENDIENTE}; USER ve
 *       compra/venta/envío/disputa MÁS los recordatorios periódicos
 *       {@code COMPRA_PENDIENTE_DIARIA}, {@code VENTA_POR_ENTREGAR_DIARIA} y
 *       {@code ENVIO_PENDIENTE_48H}), aislamiento por destinatario, mapeo campo a campo del DTO
 *       contra el valor persistido y orden descendente con desempate por ID.</li>
 *   <li>{@code PATCH /notificaciones/{id}/leer} marca {@code leida=true} de forma idempotente
 *       (segunda llamada retorna la misma entidad), 404 si la notificación no existe, 403 si el
 *       autenticado no es su destinatario.</li>
 *   <li>{@code GET /notificaciones/no-leidas/count} (PHA15TSK05) devuelve {@code {"cantidad": N}}
 *       contando EXCLUSIVAMENTE las notificaciones accionables del rol del JWT con
 *       {@code leida=false}: el MISMO criterio accionable por rol del badge (ADMIN:
 *       moderación/disputas; USER: compra/venta/envío/disputa), un SUBCONJUNTO del listado
 *       visible desde PHA15TSK06. Los avisos diarios y periódicos
 *       ({@code COMPRA_PENDIENTE_DIARIA}, {@code VENTA_POR_ENTREGAR_DIARIA},
 *       {@code ENVIO_PENDIENTE_48H}) no inflan el contador (plan.md §Notificaciones, fila
 *       "Visibilidad del panel (listado vs badge)"). No acepta ni lee parámetro alguno del
 *       cliente: usuario y rol salen del JWT (constitution, principio 7). Marcar una
 *       notificación como leída con el PATCH existente actualiza el conteo.</li>
 * </ul>
 *
 * <p>Los tipos sembrados son todos válidos frente al CHECK {@code chk_notificaciones_tipo_valido}
 * de V19 (la corrección de los fixtures que insertaban {@code AVISO_ENVIO_PENDIENTE}, inválido,
 * corresponde a PHA12TSK04) y, para las aserciones de contenido del listado, pertenecen a la lista
 * del rol correspondiente para sobrevivir al filtro. La ruta queda protegida por
 * {@code anyRequest().authenticated()} de {@code SecurityConfig} (sin requestMatcher nuevo).</p>
 *
 * <p><strong>Política única de fixtures ADMIN (PHA12TSK06).</strong> Esta clase NO crea ninguna
 * fila ADMIN: el escenario de filtro por rol ADMIN inicia sesión exclusivamente con el
 * administrador único provisionado por el contexto de test (seed V6 con
 * {@code ADMIN_EMAIL}/{@code ADMIN_PASSWORD_HASH}, cuyo hash corresponde a la contraseña plana
 * compartida {@code PASSWORD_RAW}), resuelto mediante {@link #obtenerAdminUnico()} tras una
 * limpieza que lo conserva. Motivo: {@code UsuarioRepository.findByRol(Rol.ADMIN)} es Optional
 * por diseño (invariante de admin único, PHA06TSK02) y cualquier fixture con un ADMIN adicional
 * rompe esa invariante.</p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=clave-secreta-pruebas-notificaciones-minimo-32-caracteres",
    "ADMIN_EMAIL=admin.seed@easymarket.com",
    "ADMIN_PASSWORD_HASH=$2a$10$ezbtTwVogv0lR8nXJuHRk.RGzMVbhLBUjDAt4zzBdJhbqR.U53k6u"
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

    /** Email del ADMIN único provisionado por el contexto de test (propiedad {@code ADMIN_EMAIL}, seed V6). */
    @Value("${ADMIN_EMAIL}")
    private String adminEmail;

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
     * Verifica el criterio de visibilidad del panel (PHA15TSK06, plan.md §Notificaciones fila
     * "Visibilidad del panel (listado vs badge)", decisión de Lino 2026-08-30) para el rol
     * USER: {@code GET /notificaciones} devuelve un SUPERCONJUNTO del badge — los 7 tipos
     * accionables del rol MÁS los 3 recordatorios periódicos del propio rol
     * ({@code COMPRA_PENDIENTE_DIARIA}, {@code VENTA_POR_ENTREGAR_DIARIA},
     * {@code ENVIO_PENDIENTE_48H}) — y excluye los tipos del OTRO rol.
     *
     * <p>Este test reemplaza al de PHA12TSK04 que afirmaba la exclusión de los recordatorios
     * periódicos del listado: los tipos del MISMO rol ahora SÍ aparecen; los del otro rol
     * ({@code NUEVA_PUBLICACION_PENDIENTE}, tipo ADMIN) y los sin rol
     * ({@code AVISO_TRANSACCION_ABIERTA}) siguen excluidos.</p>
     *
     * <p>Se siembran para A cuatro notificaciones visibles (una accionable y los tres
     * recordatorios periódicos, todos válidos frente al CHECK de V19) y dos fuera del rol
     * ({@code NUEVA_PUBLICACION_PENDIENTE} y {@code AVISO_TRANSACCION_ABIERTA}); la respuesta
     * debe contener las cuatro visibles aunque todas sean del propio destinatario.</p>
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("GET /notificaciones como USER ve accionables y recordatorios de su rol, excluye los del rol ADMIN")
    void obtener_UsuarioVeAccionablesYRecordatoriosDeSuRol_ExcluyeLosDelOtroRol() throws Exception {
        Notificacion visibleAccionable = notificacionRepository.save(
                new Notificacion(usuarioA, "Tu compra fue confirmada", "COMPRA_CONFIRMADA", ahora()));
        Notificacion visibleRecordatorio1 = notificacionRepository.save(
                new Notificacion(usuarioA, "Tu compra sigue pendiente", "COMPRA_PENDIENTE_DIARIA", ahora()));
        Notificacion visibleRecordatorio2 = notificacionRepository.save(
                new Notificacion(usuarioA, "Tu venta sigue por entregar", "VENTA_POR_ENTREGAR_DIARIA", ahora()));
        Notificacion visibleRecordatorio3 = notificacionRepository.save(
                new Notificacion(usuarioA, "Tu envío sigue pendiente", "ENVIO_PENDIENTE_48H", ahora()));
        Notificacion ajenaRolAdmin = notificacionRepository.save(
                new Notificacion(usuarioA, "Nueva publicación pendiente", "NUEVA_PUBLICACION_PENDIENTE", ahora()));
        Notificacion ajenaSinRol = notificacionRepository.save(
                new Notificacion(usuarioA, "Tu transacción sigue abierta", "AVISO_TRANSACCION_ABIERTA", ahora()));

        Cookie cookieUsuarioA = obtenerCookieJwtPostLogin(usuarioA);

        MvcResult result = mockMvc.perform(get("/notificaciones").cookie(cookieUsuarioA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andReturn();

        List<Map<String, Object>> items = leerItems(result);

        assertThat(items)
                .extracting(item -> ((Number) item.get("id")).longValue())
                .containsExactlyInAnyOrder(
                        visibleAccionable.getId(),
                        visibleRecordatorio1.getId(),
                        visibleRecordatorio2.getId(),
                        visibleRecordatorio3.getId())
                .doesNotContain(ajenaRolAdmin.getId(), ajenaSinRol.getId());

        // Sanidad: las excluidas existen en BD y pertenecen al propio A; su ausencia es del
        // filtro por rol (tipos del rol ADMIN y tipo sin rol), no del aislamiento por destinatario.
        assertThat(notificacionRepository.findAll())
                .extracting(Notificacion::getId)
                .contains(visibleAccionable.getId(), visibleRecordatorio1.getId(), visibleRecordatorio2.getId(),
                        visibleRecordatorio3.getId(), ajenaRolAdmin.getId(), ajenaSinRol.getId());
    }

    /**
     * Verifica el criterio de visibilidad del panel (PHA15TSK06, plan.md §Notificaciones fila
     * "Visibilidad del panel (listado vs badge)", decisión de Lino 2026-08-30) para el rol
     * ADMIN: {@code GET /notificaciones} devuelve los 2 tipos accionables del rol (moderación y
     * disputas) MÁS {@code NUEVA_PUBLICACION_PENDIENTE} (superconjunto del badge), y excluye
     * los tipos del rol USER (accionables y recordatorios periódicos) y los sin rol.
     *
     * <p>Este test reemplaza al de PHA12TSK04 que afirmaba un listado exclusivo de
     * moderación/disputas: {@code NUEVA_PUBLICACION_PENDIENTE} del MISMO rol ahora SÍ aparece;
     * los tipos del rol USER ({@code COMPRA_CONFIRMADA}, {@code COMPRA_PENDIENTE_DIARIA}) y el
     * tipo sin rol ({@code AVISO_TRANSACCION_ABIERTA}) siguen excluidos.</p>
     *
     * <p>Política única de fixtures ADMIN (PHA12TSK06): este escenario NO crea ninguna fila ADMIN.
     * {@link #prepararEscenarioAdminUnico()} limpia las tablas con dependencias de FK conservando
     * al administrador único provisionado por el contexto de test (seed V6 con
     * {@code ADMIN_EMAIL}/{@code ADMIN_PASSWORD_HASH}, cuyo hash corresponde a la contraseña plana
     * compartida {@code PASSWORD_RAW}) y la identidad se resuelve con
     * {@link #obtenerAdminUnico()}; así la invariante de admin único ({@code findByRol(Rol.ADMIN)},
     * Optional por diseño, PHA06TSK02) se conserva durante todo el test.</p>
     *
     * @throws Exception si falla la interacción HTTP o la preparación del escenario
     */
    @Test
    @DisplayName("GET /notificaciones como ADMIN ve moderación, disputas y nueva publicación, excluye los del rol USER")
    void obtener_AdminVeModeracionDisputasYNuevaPublicacion_ExcluyeLosDelRolUsuario() throws Exception {
        prepararEscenarioAdminUnico();
        Usuario admin = obtenerAdminUnico();

        ZonedDateTime tAntigua = ahora().minusMinutes(4L);
        ZonedDateTime tMedia = ahora().minusMinutes(2L);
        ZonedDateTime tReciente = ahora().minusMinutes(1L);
        Notificacion moderacion = notificacionRepository.save(
                new Notificacion(admin, "Publicación pendiente de aprobar", "PUBLICACION_PENDIENTE_APROBAR", tAntigua));
        Notificacion nuevaPublicacion = notificacionRepository.save(
                new Notificacion(admin, "Nueva publicación pendiente de revisión", "NUEVA_PUBLICACION_PENDIENTE", tMedia));
        Notificacion disputa = notificacionRepository.save(
                new Notificacion(admin, "Disputa pendiente de resolver", "DISPUTA_PENDIENTE_RESOLVER", tReciente));
        Notificacion ajenaTipoUsuario = notificacionRepository.save(
                new Notificacion(admin, "Compra confirmada fuera del rol ADMIN", "COMPRA_CONFIRMADA", ahora()));
        Notificacion ajenaRecordatorioUsuario = notificacionRepository.save(
                new Notificacion(admin, "Recordatorio diario fuera del rol ADMIN", "COMPRA_PENDIENTE_DIARIA", ahora()));
        Notificacion ajenaSinRol = notificacionRepository.save(
                new Notificacion(admin, "Aviso diario fuera del rol ADMIN", "AVISO_TRANSACCION_ABIERTA", ahora()));

        Cookie cookieAdmin = obtenerCookieJwtPostLogin(admin);

        MvcResult result = mockMvc.perform(get("/notificaciones").cookie(cookieAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andReturn();

        List<Map<String, Object>> items = leerItems(result);

        // Orden descendente: disputa (tReciente), nueva publicación (tMedia), moderación (tAntigua)
        assertThat(((Number) items.get(0).get("id")).longValue()).isEqualTo(disputa.getId());
        assertThat(items.get(0).get("tipo")).isEqualTo("DISPUTA_PENDIENTE_RESOLVER");
        assertThat(((Number) items.get(1).get("id")).longValue()).isEqualTo(nuevaPublicacion.getId());
        assertThat(items.get(1).get("tipo")).isEqualTo("NUEVA_PUBLICACION_PENDIENTE");
        assertThat(((Number) items.get(2).get("id")).longValue()).isEqualTo(moderacion.getId());
        assertThat(items.get(2).get("tipo")).isEqualTo("PUBLICACION_PENDIENTE_APROBAR");

        // Sanidad: las excluidas existen en BD y pertenecen al propio admin
        assertThat(notificacionRepository.findAll())
                .extracting(Notificacion::getId)
                .contains(moderacion.getId(), disputa.getId(), nuevaPublicacion.getId(),
                        ajenaTipoUsuario.getId(), ajenaRecordatorioUsuario.getId(), ajenaSinRol.getId());
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

    // ------------------------------------------------------------------
    // PHA15TSK05 — GET /notificaciones/no-leidas/count
    // ------------------------------------------------------------------

    /**
     * Verifica el criterio central del contador de pendientes (PHA15TSK05): {@code GET
     * /notificaciones/no-leidas/count} autenticado como USUARIO retorna 200 OK con
     * {@code {"cantidad": N}} donde N cuenta EXCLUSIVAMENTE las notificaciones accionables de
     * su rol con {@code leida=false}.
     *
     * <p>Fixture para A: dos accionables no leídas (cuentan), una accionable ya leída (no
     * cuenta), una diaria no leída {@code COMPRA_PENDIENTE_DIARIA} (no cuenta — fuera del set
     * accionable según plan.md §Notificaciones, fila "Unicidad por elemento pendiente") y una
     * fuera de rol no leída {@code AVISO_TRANSACCION_ABIERTA} (no cuenta). B posee una
     * accionable no leída que tampoco debe contar (aislamiento por destinatario).</p>
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("GET /notificaciones/no-leidas/count como USER cuenta solo accionables no leídas propias")
    void contar_UsuarioConNoLeidasAccionables_Retorna200CantidadCorrecta() throws Exception {
        notificacionRepository.save(
                new Notificacion(usuarioA, "Compra confirmada 1", "COMPRA_CONFIRMADA", ahora()));
        notificacionRepository.save(
                new Notificacion(usuarioA, "Envío marcado 2", "ENVIO_MARCADO", ahora()));

        Notificacion leida = notificacionRepository.save(
                new Notificacion(usuarioA, "Compra confirmada ya leída", "COMPRA_CONFIRMADA", ahora()));
        leida.setLeida(true);
        notificacionRepository.save(leida);

        notificacionRepository.save(
                new Notificacion(usuarioA, "Recordatorio diario de compra", "COMPRA_PENDIENTE_DIARIA", ahora()));
        notificacionRepository.save(
                new Notificacion(usuarioA, "Aviso fuera del set accionable", "AVISO_TRANSACCION_ABIERTA", ahora()));

        notificacionRepository.save(
                new Notificacion(usuarioB, "Accionable de B: no cuenta para A", "COMPRA_CONFIRMADA", ahora()));

        Cookie cookieUsuarioA = obtenerCookieJwtPostLogin(usuarioA);

        mockMvc.perform(get("/notificaciones/no-leidas/count").cookie(cookieUsuarioA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cantidad").value(2));
    }

    /**
     * Verifica que ADMIN recibe SU PROPIA cantidad (PHA15TSK05): el contador usa la lista de
     * tipos accionables del rol ADMIN (moderación/disputas), excluye leídas, excluye tipos de
     * otros roles y excluye las diarias.
     *
     * <p>Política única de fixtures ADMIN (PHA12TSK06): este escenario NO crea ninguna fila
     * ADMIN; inicia sesión exclusivamente con el administrador único provisionado por el
     * contexto de test (seed V6, {@code ADMIN_EMAIL}/{@code ADMIN_PASSWORD_HASH}) mediante
     * {@link #prepararEscenarioAdminUnico()} y {@link #obtenerAdminUnico()}.</p>
     *
     * @throws Exception si falla la interacción HTTP o la preparación del escenario
     */
    @Test
    @DisplayName("GET /notificaciones/no-leidas/count como ADMIN cuenta solo moderación/disputas no leídas")
    void contar_AdminRecibeSuCantidadPropia_Retorna200CantidadCorrecta() throws Exception {
        prepararEscenarioAdminUnico();
        Usuario admin = obtenerAdminUnico();

        notificacionRepository.save(
                new Notificacion(admin, "Publicación pendiente 1", "PUBLICACION_PENDIENTE_APROBAR", ahora()));
        notificacionRepository.save(
                new Notificacion(admin, "Disputa pendiente 2", "DISPUTA_PENDIENTE_RESOLVER", ahora()));

        Notificacion leida = notificacionRepository.save(
                new Notificacion(admin, "Disputa ya leída", "DISPUTA_PENDIENTE_RESOLVER", ahora()));
        leida.setLeida(true);
        notificacionRepository.save(leida);

        notificacionRepository.save(
                new Notificacion(admin, "Tipo de rol USER: no cuenta", "COMPRA_CONFIRMADA", ahora()));
        notificacionRepository.save(
                new Notificacion(admin, "Diaria: no cuenta", "VENTA_POR_ENTREGAR_DIARIA", ahora()));

        Cookie cookieAdmin = obtenerCookieJwtPostLogin(admin);

        mockMvc.perform(get("/notificaciones/no-leidas/count").cookie(cookieAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cantidad").value(2));
    }

    /**
     * Verifica el caso sin datos para un usuario autenticado (PHA15TSK05): sin notificaciones
     * propias recibe 200 OK con {@code {"cantidad": 0}} — cero es una respuesta válida, no un
     * error. La única notificación sembrada pertenece a B, de modo que el cero acredita a la vez
     * el aislamiento por destinatario.
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("GET /notificaciones/no-leidas/count sin notificaciones propias retorna cantidad 0")
    void contar_UsuarioSinNotificaciones_Retorna200CantidadCero() throws Exception {
        notificacionRepository.save(
                new Notificacion(usuarioB, "Solo B tiene notificaciones", "COMPRA_CONFIRMADA", ahora()));

        Cookie cookieUsuarioA = obtenerCookieJwtPostLogin(usuarioA);

        mockMvc.perform(get("/notificaciones/no-leidas/count").cookie(cookieUsuarioA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cantidad").value(0));
    }

    /**
     * Verifica la protección de la cadena de seguridad para la ruta nueva (PHA15TSK05): {@code
     * GET /notificaciones/no-leidas/count} sin cookie JWT retorna 403 Forbidden (cubierto por
     * {@code anyRequest().authenticated()} de {@code SecurityConfig}, sin requestMatcher nuevo).
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("GET /notificaciones/no-leidas/count sin autenticación retorna 403 Forbidden")
    void contar_SinAutenticacion_Retorna403Forbidden() throws Exception {
        mockMvc.perform(get("/notificaciones/no-leidas/count"))
                .andExpect(status().isForbidden());
    }

    /**
     * Verifica la integración con el PATCH existente (regresión exigida por PHA15TSK05): marcar
     * la única notificación no leída de A mediante {@code PATCH /notificaciones/{id}/leer}
     * reduce el conteo de 1 a 0, sin recargar ni recrear datos.
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("PATCH /notificaciones/{id}/leer actualiza el conteo de no leídas (1 -> 0)")
    void contar_MarcarComoLeidaActualizaElConteo() throws Exception {
        Notificacion notificacion = notificacionRepository.save(
                new Notificacion(usuarioA, "Única no leída de A", "COMPRA_CONFIRMADA", ahora()));
        Cookie cookieUsuarioA = obtenerCookieJwtPostLogin(usuarioA);

        mockMvc.perform(get("/notificaciones/no-leidas/count").cookie(cookieUsuarioA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cantidad").value(1));

        mockMvc.perform(patch("/notificaciones/" + notificacion.getId() + "/leer").cookie(cookieUsuarioA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leida").value(true));

        mockMvc.perform(get("/notificaciones/no-leidas/count").cookie(cookieUsuarioA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cantidad").value(0));
    }

    /**
     * Verifica constitution principio 7 aplicado al contador (PHA15TSK05): el endpoint NO lee
     * parámetro alguno del cliente — usuario y rol salen exclusivamente del JWT. Un query param
     * Client-side ({@code usuarioId} de otro usuario, {@code rol} suplantado) es ignorado por
     * completo: la respuesta sigue siendo la cantidad del autenticado.
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("GET /notificaciones/no-leidas/count ignora parámetros del cliente (identidad solo del JWT)")
    void contar_ParametrosDelClienteNoInfluyen_RetornaCantidadDelJwt() throws Exception {
        notificacionRepository.save(
                new Notificacion(usuarioB, "Accionable de B", "COMPRA_CONFIRMADA", ahora()));

        Cookie cookieUsuarioA = obtenerCookieJwtPostLogin(usuarioA);

        mockMvc.perform(get("/notificaciones/no-leidas/count")
                        .queryParam("usuarioId", String.valueOf(usuarioB.getId()))
                        .queryParam("rol", "ADMIN")
                        .cookie(cookieUsuarioA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cantidad").value(0));
    }

    /**
     * Deja la base de datos en el estado que exige el escenario de admin único: borra las tablas
     * respetando el orden inverso de sus dependencias de FK (notificaciones &rarr; transacciones
     * &rarr; publicaciones &rarr; catálogo &rarr; usuarios auxiliares). El ADMIN único sembrado
     * por V6 NO se borra (política PHA12TSK06): sobrevive a la limpieza y el escenario inicia
     * sesión con él, sin que ningún fixture cree filas ADMIN nuevas. Los logs append-only
     * de negocio no se pueblan en esta clase, por lo que el borrado no afecta evidencia alguna.
     */
    private void prepararEscenarioAdminUnico() {
        notificacionRepository.deleteAll();
        transaccionRepository.deleteAll();
        publicacionRepository.deleteAll();
        subcategoriaRepository.deleteAll();
        categoriaRepository.deleteAll();
        eliminarUsuariosSalvoAdminUnico();
    }

    /**
     * Elimina los usuarios de fixtures conservando únicamente las filas con rol {@code ADMIN}
     * (política PHA12TSK06): el ADMIN único provisionado por el seed V6 sobrevive a la limpieza
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
     * Persiste un usuario regular de prueba con la contraseña compartida de la clase
     * (política PHA12TSK06: todos los usuarios nacidos aquí son {@link Rol#USUARIO}).
     *
     * @param email correo único del usuario
     * @return usuario persistido
     */
    private Usuario guardarUsuario(String email) {
        return usuarioRepository.save(new Usuario(email, passwordEncoder.encode(PASSWORD_RAW),
                Rol.USUARIO, 0L, ZonedDateTime.now(ZONA_LIMA)));
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
