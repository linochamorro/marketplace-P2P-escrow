package com.easymarket.marketplace.controller;

import com.easymarket.marketplace.dto.LoginRequestDto;
import com.easymarket.marketplace.model.Categoria;
import com.easymarket.marketplace.model.EstadoPublicacion;
import com.easymarket.marketplace.model.Publicacion;
import com.easymarket.marketplace.model.Rol;
import com.easymarket.marketplace.model.Subcategoria;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.CategoriaRepository;
import com.easymarket.marketplace.repository.PublicacionRepository;
import com.easymarket.marketplace.repository.SubcategoriaRepository;
import com.easymarket.marketplace.repository.UsuarioRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * Pruebas de integración HTTP del detalle público de publicación
 * {@code GET /publicaciones/{id}} (PHA06TSK04, Stories 0b y 11 de spec.md).
 *
 * <p>Ejercita el controlador, la cadena de seguridad JWT y PostgreSQL real mediante
 * Testcontainers. Verifica el criterio de la fila de tarea: solo una publicación en estado
 * {@link EstadoPublicacion#APROBADA} es devuelta con HTTP 200 y el DTO completo
 * {@code PublicacionResponseDto}; cualquier otro estado ({@code pendiente_revision},
 * {@code cambios_solicitados}, {@code rechazada}, {@code oculta}) y cualquier {@code id}
 * inexistente devuelven HTTP 404 reutilizando {@code PublicacionNoEncontradaException} — la
 * semántica es ocultar la existencia al marketplace. Incluye además la regresión del mapping
 * literal {@code GET /publicaciones/mias} frente al nuevo template {@code /{id}}, confirmando que
 * Spring prioriza la ruta literal y el listado del vendedor sigue funcionando.</p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=clave-secreta-pruebas-detalle-publicacion-min-32-caracteres",
    "ADMIN_EMAIL=admin.seed@easymarket.com",
    "ADMIN_PASSWORD_HASH=$2a$10$R9h/cIPz0gi.URNNxrkh2OPST9/PgBkqquzi.Ss7KIUgO2t0jWMUW"
})
class PublicacionDetalleControllerIntegrationTests {

    private static final String PASSWORD_RAW = "PasswordSeguro123!";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private CategoriaRepository categoriaRepository;

    @Autowired
    private SubcategoriaRepository subcategoriaRepository;

    @Autowired
    private PublicacionRepository publicacionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /** Cliente SQL usado solo para vaciar fixtures entre pruebas de integración. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;
    private Usuario vendedor;
    private Usuario otroUsuario;
    private Categoria categoria;
    private Subcategoria subcategoria;

    /**
     * Restablece las tablas de fixtures y crea los usuarios y la jerarquía de catálogo aislados
     * de cada caso de integración.
     */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        jdbcTemplate.execute("TRUNCATE TABLE login_attempts, transacciones, publicaciones, subcategorias, categorias, usuarios CASCADE");

        vendedor = guardarUsuario("vendedor.detalle." + sufijo() + "@easymarket.com");
        otroUsuario = guardarUsuario("comprador.detalle." + sufijo() + "@easymarket.com");
        categoria = categoriaRepository.save(new Categoria("Categoría detalle " + sufijo()));
        subcategoria = subcategoriaRepository.save(new Subcategoria(categoria, "Subcategoría detalle " + sufijo()));
    }

    /**
     * Verifica el criterio central del detalle: una publicación {@link EstadoPublicacion#APROBADA}
     * devuelve HTTP 200 con el cuerpo de {@code PublicacionResponseDto} mapeado campo a campo
     * contra la entidad persistida en base de datos, incluido el {@code precio} en centavos
     * enteros.
     *
     * @throws Exception si la petición HTTP no puede ejecutarse
     */
    @Test
    @DisplayName("GET /publicaciones/{id} con publicación APROBADA devuelve 200 con todos los campos del DTO")
    void detalle_PublicacionAprobada_Retorna200ConTodosLosCampos() throws Exception {
        Publicacion aprobada = guardarPublicacion(vendedor, 125_000L, 7, "Laptop Core i7 aprobada", EstadoPublicacion.APROBADA);
        Publicacion guardada = publicacionRepository.findById(aprobada.getId()).orElseThrow();
        Cookie cookieJwt = obtenerCookieJwt(vendedor.getEmail());

        mockMvc.perform(get("/publicaciones/" + aprobada.getId()).cookie(cookieJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(guardada.getId()))
                .andExpect(jsonPath("$.precio").value(guardada.getPrecio()))
                .andExpect(jsonPath("$.stock").value(guardada.getStock()))
                .andExpect(jsonPath("$.estado").value("APROBADA"))
                .andExpect(jsonPath("$.descripcion").value(guardada.getDescripcion()))
                .andExpect(jsonPath("$.categoriaId").value(guardada.getCategoria().getId()))
                .andExpect(jsonPath("$.subcategoriaId").value(guardada.getSubcategoria().getId()))
                .andExpect(jsonPath("$.usuarioId").value(guardada.getUsuario().getId()));

        assertThat(guardada.getPrecio()).isEqualTo(125_000L);
    }

    /**
     * Verifica que ninguna publicación en estado distinto a {@code APROBADA} queda expuesta al
     * marketplace: {@code pendiente_revision}, {@code cambios_solicitados}, {@code rechazada} y
     * {@code oculta} devuelven HTTP 404 (ocultando la existencia mediante
     * {@code PublicacionNoEncontradaException}).
     *
     * @throws Exception si la petición HTTP no puede ejecutarse
     */
    @Test
    @DisplayName("GET /publicaciones/{id} con estados no aprobados devuelve 404 para todos ellos")
    void detalle_PublicacionesNoAprobadas_Retornan404() throws Exception {
        List<EstadoPublicacion> noAprobados = List.of(
                EstadoPublicacion.PENDIENTE_REVISION,
                EstadoPublicacion.CAMBIOS_SOLICITADOS,
                EstadoPublicacion.RECHAZADA,
                EstadoPublicacion.OCULTA
        );
        Cookie cookieJwt = obtenerCookieJwt(vendedor.getEmail());

        for (EstadoPublicacion estado : noAprobados) {
            Publicacion publicacion = guardarPublicacion(vendedor, 100_000L, 3, "No aprobada: " + estado.name(), estado);
            mockMvc.perform(get("/publicaciones/" + publicacion.getId()).cookie(cookieJwt))
                    .andExpect(status().isNotFound());

            // La fila sigue existiendo en la base: el 404 es ocultamiento, no eliminación.
            assertThat(publicacionRepository.findById(publicacion.getId())).isPresent();
        }
    }

    /**
     * Verifica que un {@code id} inexistente devuelve HTTP 404, mismo contrato de ocultamiento de
     * existencia que los estados no aprobados.
     *
     * @throws Exception si la petición HTTP no puede ejecutarse
     */
    @Test
    @DisplayName("GET /publicaciones/{id} con id inexistente devuelve 404 Not Found")
    void detalle_IdInexistente_Retorna404NotFound() throws Exception {
        Cookie cookieJwt = obtenerCookieJwt(vendedor.getEmail());

        mockMvc.perform(get("/publicaciones/999999").cookie(cookieJwt))
                .andExpect(status().isNotFound());
    }

    /**
     * Verifica que el detalle queda protegido por {@code anyRequest().authenticated()} de
     * {@code SecurityConfig}: una petición sin cookie JWT recibe HTTP 403 (mismo assert de "sin
     * autenticación" de los tests de integración existentes del proyecto).
     *
     * @throws Exception si la petición HTTP no puede ejecutarse
     */
    @Test
    @DisplayName("GET /publicaciones/{id} sin cookie retorna 403 Forbidden")
    void detalle_SinCookie_Retorna403Forbidden() throws Exception {
        Publicacion aprobada = guardarPublicacion(vendedor, 50_000L, 1, "Aprobada sin sesión", EstadoPublicacion.APROBADA);

        mockMvc.perform(get("/publicaciones/" + aprobada.getId()))
                .andExpect(status().isForbidden());
    }

    /**
     * Verifica la regresión del mapping literal {@code GET /publicaciones/mias} frente al nuevo
     * template {@code /{id}}: Spring prioriza la ruta literal, por lo que el listado del vendedor
     * sigue devolviendo 200 con únicamente sus publicaciones (nunca las de otro usuario).
     *
     * @throws Exception si la petición HTTP no puede ejecutarse
     */
    @Test
    @DisplayName("GET /publicaciones/mias sigue devolviendo 200 con el listado correcto tras agregar el mapping /{id}")
    void mias_TrasAgregarMappingDetalle_SigueRetornando200ConListadoCorrecto() throws Exception {
        Publicacion miaReciente = guardarPublicacion(vendedor, 200_000L, 2, "Mi publicación reciente", EstadoPublicacion.APROBADA);
        Publicacion miaAntigua = guardarPublicacion(vendedor, 100_000L, 5, "Mi publicación antigua", EstadoPublicacion.RECHAZADA);
        Publicacion deOtro = guardarPublicacion(otroUsuario, 300_000L, 1, "Publicación de otro vendedor", EstadoPublicacion.APROBADA);

        Cookie cookieJwt = obtenerCookieJwt(vendedor.getEmail());

        MvcResult resultado = mockMvc.perform(get("/publicaciones/mias").cookie(cookieJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andReturn();

        List<Long> ids = objectMapper.readTree(resultado.getResponse().getContentAsString())
                .findValuesAsText("id").stream().map(Long::valueOf).toList();
        assertThat(ids).containsExactlyInAnyOrder(miaReciente.getId(), miaAntigua.getId());
        assertThat(ids).doesNotContain(deOtro.getId());
    }

    /**
     * Persiste un usuario con contraseña BCrypt apta para el login real del test.
     *
     * @param email dirección de correo única del usuario
     * @return usuario persistido
     */
    private Usuario guardarUsuario(String email) {
        return usuarioRepository.save(new Usuario(
                email,
                passwordEncoder.encode(PASSWORD_RAW),
                Rol.USUARIO,
                0L,
                ZonedDateTime.now(ZoneId.of("America/Lima"))
        ));
    }

    /**
     * Persiste una publicación con el estado seleccionado para el escenario HTTP.
     *
     * @param vendedor dueño persistido de la publicación
     * @param precio precio entero en centavos
     * @param stock unidades disponibles
     * @param descripcion texto que identifica la publicación de fixture
     * @param estado estado persistido de la publicación
     * @return publicación persistida
     */
    private Publicacion guardarPublicacion(Usuario vendedor, long precio, int stock, String descripcion, EstadoPublicacion estado) {
        Publicacion publicacion = new Publicacion(vendedor, categoria, subcategoria, precio, stock, descripcion);
        publicacion.setEstado(estado);
        return publicacionRepository.save(publicacion);
    }

    /**
     * Ejecuta el login real y extrae la cookie JWT que autentica una petición posterior.
     *
     * @param email correo del usuario cuyas credenciales se usarán
     * @return cookie HTTP-only JWT emitida por el endpoint de login
     * @throws Exception si el login HTTP falla o no emite la cookie
     */
    private Cookie obtenerCookieJwt(String email) throws Exception {
        MvcResult resultado = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequestDto(email, PASSWORD_RAW))))
                .andExpect(status().isOk())
                .andReturn();
        Cookie cookie = resultado.getResponse().getCookie("jwt");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    /**
     * Genera un sufijo único basado en nanosegundos para correos aislados entre escenarios.
     *
     * @return sufijo numérico único
     */
    private String sufijo() {
        return Long.toUnsignedString(System.nanoTime());
    }
}
