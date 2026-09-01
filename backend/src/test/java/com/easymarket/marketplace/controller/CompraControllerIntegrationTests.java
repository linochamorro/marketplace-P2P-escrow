package com.easymarket.marketplace.controller;

import com.easymarket.marketplace.dto.LoginRequestDto;
import com.easymarket.marketplace.model.Categoria;
import com.easymarket.marketplace.model.EstadoPublicacion;
import com.easymarket.marketplace.model.Publicacion;
import com.easymarket.marketplace.model.Rol;
import com.easymarket.marketplace.model.Subcategoria;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.CategoriaRepository;
import com.easymarket.marketplace.repository.IdempotencyKeyRepository;
import com.easymarket.marketplace.repository.NotificacionRepository;
import com.easymarket.marketplace.repository.PublicacionRepository;
import com.easymarket.marketplace.repository.SubcategoriaRepository;
import com.easymarket.marketplace.repository.UsuarioRepository;
import com.easymarket.marketplace.service.CrearPaymentIntentResult;
import com.easymarket.marketplace.service.StripePaymentService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración TDD para el controlador REST {@code POST /compras} (PHA03TSK09,
 * Story 5, spec.md) y test previo de la fila en tasks.md: <strong>201 con datos válidos; 409
 * en auto-compra; 422 sin stock</strong>.
 *
 * <p>Usa Testcontainers (PostgreSQL real), login JWT real ({@code POST /auth/login}) y el
 * patrón de {@code PublicacionControllerIntegrationTests} / {@code AuthRegistrationIntegrationTests}.</p>
 *
 * <p><strong>Aislamiento de Stripe:</strong> el caso 201 requiere la creación de un PaymentIntent
 * contra la API de Stripe. Para que el test sea determinista y sin credenciales de sandbox, el
 * bean {@link StripePaymentService} se mockea con {@link MockitoBean @MockitoBean} (patrón ya
 * usado en el proyecto) para devolver un PaymentIntent falso con patrón {@code pi_...}. Los casos
 * de auto-compra (409) y stock agotado (422) fallan en la validación de dominio
 * ({@code ValidacionCompraService}) ANTES de alcanzar Stripe, por lo que no requieren stubbing.</p>
 *
 * <p><strong>Orden de limpieza del {@code setUp} (hardening preventivo PHA15TSK09, decisión de
 * plan.md 2026-08-31, patrón PHA15TSK04-L07):</strong> las filas de {@code notificaciones} se
 * eliminan PRIMERO porque referencian a {@code transacciones}
 * ({@code fk_notificaciones_transaccion}, migración V14), a {@code publicaciones}
 * ({@code fk_notificaciones_publicacion}, migración V20) y a {@code usuarios}
 * ({@code fk_notificaciones_usuario}, migración V9), y ninguna otra tabla las referencia.
 * Hardening preventivo: hoy ningún escenario de esta clase genera notificaciones, pero el orden
 * evita una violación de FK en {@code setUp} si un cambio futuro las introduce.</p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=clave-secreta-para-pruebas-de-integracion-compras-min-32-ch",
    "ADMIN_EMAIL=admin.seed@easymarket.com",
    "ADMIN_PASSWORD_HASH=$2a$10$R9h/cIPz0gi.URNNXRkh2OPST9/PgBkqquzi.Ss7KIUgO2t0jWMUW"
})
class CompraControllerIntegrationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private StripePaymentService stripePaymentService;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private CategoriaRepository categoriaRepository;

    @Autowired
    private SubcategoriaRepository subcategoriaRepository;

    @Autowired
    private PublicacionRepository publicacionRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private NotificacionRepository notificacionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Usuario vendedor;
    private Usuario comprador;
    private Categoria categoria;
    private Subcategoria subcategoria;

    private final String passwordRaw = "PasswordSeguro123!";

    /**
     * Prepara MockMvc y deja la base de datos vacía antes de crear los fixtures compartidos
     * (vendedor y comprador).
     *
     * <p><strong>Orden de limpieza (fix preventivo PHA15TSK09, decisión de plan.md 2026-08-31,
     * patrón PHA15TSK04-L07):</strong> {@code notificaciones} se elimina PRIMERO porque
     * referencian a {@code transacciones} ({@code fk_notificaciones_transaccion}, V14), a
     * {@code publicaciones} ({@code fk_notificaciones_publicacion}, V20) y a {@code usuarios}
     * ({@code fk_notificaciones_usuario}, V9), y ninguna otra tabla las referencia; las demás
     * tablas se borran en orden inverso a sus FKs salientes (p. ej. {@code idempotency_keys}
     * antes que {@code transacciones}), de modo que ninguna fila hija sobreviva a su tabla
     * padre.</p>
     */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        // Fix preventivo PHA15TSK09 (decisión de plan.md 2026-08-31, patrón PHA15TSK04-L07): las
        // notificaciones referencian transacciones (fk_notificaciones_transaccion, V14),
        // publicaciones (fk_notificaciones_publicacion, V20) y usuarios (V9), y ninguna tabla las
        // referencia, por lo que se borran PRIMERO para no violar esas FKs si algún escenario
        // llegara a crearlas.
        notificacionRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();
        publicacionRepository.deleteAll();
        subcategoriaRepository.deleteAll();
        categoriaRepository.deleteAll();
        usuarioRepository.deleteAll();

        vendedor = usuarioRepository.save(new Usuario(
                "vendedor.compras@easymarket.com",
                passwordEncoder.encode(passwordRaw),
                Rol.USUARIO,
                0L,
                ZonedDateTime.now(ZoneId.of("America/Lima"))
        ));

        comprador = usuarioRepository.save(new Usuario(
                "comprador.compras@easymarket.com",
                passwordEncoder.encode(passwordRaw),
                Rol.USUARIO,
                0L,
                ZonedDateTime.now(ZoneId.of("America/Lima"))
        ));

        categoria = categoriaRepository.save(new Categoria("Vehículos"));
        subcategoria = subcategoriaRepository.save(new Subcategoria(categoria, "Autos"));
    }

    /**
     * Crea una publicación en el repositorio con el precio, stock, estado y dueño dados.
     *
     * @param precio precio en centavos
     * @param stock stock disponible (entero)
     * @param estado estado de la publicación
     * @param duenio usuario vendedor dueño
     * @return la publicación persistida
     */
    private Publicacion guardarPublicacion(long precio, int stock, EstadoPublicacion estado, Usuario duenio) {
        Publicacion publicacion = new Publicacion(duenio, categoria, subcategoria, precio, stock, "Vehículo en venta");
        publicacion.setEstado(estado);
        return publicacionRepository.save(publicacion);
    }

    /**
     * Realiza un login real con las credenciales y devuelve la cookie JWT httpOnly del usuario.
     *
     * @param email correo del usuario
     * @param password contraseña en claro del usuario
     * @return cookie {@code jwt} emitida por POST /auth/login
     * @throws Exception si la petición de login falla
     */
    private Cookie obtenerCookieJwtPostLogin(String email, String password) throws Exception {
        LoginRequestDto loginRequest = new LoginRequestDto(email, password);

        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        Cookie cookieJwt = result.getResponse().getCookie("jwt");
        assertThat(cookieJwt).isNotNull();
        return cookieJwt;
    }

    @Test
    @DisplayName("POST /compras sin autenticación retorna 403 Forbidden")
    void crearCompra_SinAutenticacion_Retorna403Forbidden() throws Exception {
        mockMvc.perform(post("/compras")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("publicacionId", 1L))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /compras con datos válidos retorna 201 Created con clientSecret y paymentIntentId")
    void crearCompra_DatosValidos_Retorna201ConClientSecretYPaymentIntentId() throws Exception {
        Publicacion publicacion = guardarPublicacion(2500000L, 3, EstadoPublicacion.APROBADA, vendedor);
        String idempotencyKey = UUID.randomUUID().toString();

        when(stripePaymentService.crearPaymentIntent(anyLong(), eq("pen"), anyString(), anyLong(), anyLong()))
                .thenReturn(new CrearPaymentIntentResult("pi_test_abc123", "pi_test_abc123_secret_xyz"));

        Cookie cookieComprador = obtenerCookieJwtPostLogin(comprador.getEmail(), passwordRaw);

        mockMvc.perform(post("/compras")
                        .cookie(cookieComprador)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("publicacionId", publicacion.getId()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.clientSecret").value("pi_test_abc123_secret_xyz"))
                .andExpect(jsonPath("$.paymentIntentId").value("pi_test_abc123"));

        assertThat(idempotencyKeyRepository.existsById(idempotencyKey)).isTrue();
    }

    @Test
    @DisplayName("POST /compras en auto-compra (comprador es el dueño) retorna 409 Conflict")
    void crearCompra_AutoCompra_Retorna409Conflict() throws Exception {
        Publicacion publicacion = guardarPublicacion(1500000L, 3, EstadoPublicacion.APROBADA, vendedor);

        Cookie cookieVendedor = obtenerCookieJwtPostLogin(vendedor.getEmail(), passwordRaw);

        mockMvc.perform(post("/compras")
                        .cookie(cookieVendedor)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("publicacionId", publicacion.getId()))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("POST /compras sin stock disponible (stock=0) retorna 422 Unprocessable Entity")
    void crearCompra_SinStock_Retorna422() throws Exception {
        Publicacion publicacion = guardarPublicacion(1000000L, 0, EstadoPublicacion.APROBADA, vendedor);

        Cookie cookieComprador = obtenerCookieJwtPostLogin(comprador.getEmail(), passwordRaw);

        mockMvc.perform(post("/compras")
                        .cookie(cookieComprador)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("publicacionId", publicacion.getId()))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("POST /compras sin header Idempotency-Key retorna 400 Bad Request")
    void crearCompra_SinIdempotency_Key_Retorna400BadRequest() throws Exception {
        Publicacion publicacion = guardarPublicacion(800000L, 1, EstadoPublicacion.APROBADA, vendedor);

        Cookie cookieComprador = obtenerCookieJwtPostLogin(comprador.getEmail(), passwordRaw);

        mockMvc.perform(post("/compras")
                        .cookie(cookieComprador)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("publicacionId", publicacion.getId()))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /compras con publicación inexistente retorna 404 Not Found")
    void crearCompra_PublicacionInexistente_Retorna404NotFound() throws Exception {
        Cookie cookieComprador = obtenerCookieJwtPostLogin(comprador.getEmail(), passwordRaw);

        mockMvc.perform(post("/compras")
                        .cookie(cookieComprador)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("publicacionId", 999999L))))
                .andExpect(status().isNotFound());
    }
}