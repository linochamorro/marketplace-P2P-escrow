package com.easymarket.marketplace.controller;

import com.easymarket.marketplace.dto.LoginRequestDto;
import com.easymarket.marketplace.dto.RegistroRequestDto;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.UsuarioRepository;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Iterator;
import java.util.Optional;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración HTTP del endpoint de identidad mínima de sesión
 * {@code GET /usuarios/me} (PHA06TSK04, Stories 0b y 11 de spec.md).
 *
 * <p>Ejercita el controlador, la cadena de seguridad JWT y PostgreSQL real mediante
 * Testcontainers. Cada usuario se crea con el flujo de registro HTTP real
 * ({@code POST /auth/registro}) y se autentica con el login HTTP real
 * ({@code POST /auth/login}), de modo que la cookie {@code jwt} utilizada proviene de un token
 * emitido de verdad por el sistema. Verifica el criterio de la fila de tarea: la identidad
 * devuelta por {@code GET /usuarios/me} refleja exactamente los claims del JWT
 * ({@code id}, {@code email} y {@code rol} del {@code UsuarioPrincipal}), cada usuario ve su
 * propia identidad (nunca la de otro), una petición sin cookie no expone identidad (403 vía
 * {@code anyRequest().authenticated()} de {@code SecurityConfig}) y el cuerpo de respuesta no
 * contiene campos sensibles adicionales (solo {@code id}, {@code email}, {@code rol}).</p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=clave-secreta-pruebas-usuario-me-minimo-32-caracteres",
    "ADMIN_EMAIL=admin.seed@easymarket.com",
    "ADMIN_PASSWORD_HASH=$2a$10$R9h/cIPz0gi.URNNxrkh2OPST9/PgBkqquzi.Ss7KIUgO2t0jWMUW"
})
class UsuarioControllerIntegrationTests {

    private static final String PASSWORD_RAW = "PasswordSeguro123!";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private UsuarioRepository usuarioRepository;

    /** Cliente SQL usado solo para vaciar fixtures de identidad entre pruebas de integración. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    /**
     * Prepara un contexto HTTP con la cadena de seguridad aplicada y aísla las tablas de
     * identidad y rate limiting para cada caso de integración.
     *
     * <p>{@code TRUNCATE} es DDL de limpieza exclusivo de Testcontainers: no ejecuta {@code DELETE}
     * sobre los registros y resetea las secuencias, garantizando IDs y contadores de rate limiting
     * aislados entre escenarios.</p>
     */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        jdbcTemplate.execute("TRUNCATE TABLE login_attempts, usuarios CASCADE");
    }

    /**
     * Verifica el criterio central de la fila PHA06TSK04: la identidad devuelta por
     * {@code GET /usuarios/me} refleja exactamente el JWT del usuario autenticado — el {@code id},
     * el {@code email} y el {@code rol} del {@code UsuarioPrincipal} — comparando contra la
     * entidad persistida por el registro real.
     *
     * @throws Exception si la petición HTTP no puede ejecutarse
     */
    @Test
    @DisplayName("GET /usuarios/me autenticado devuelve id, email y rol exactos del JWT (USUARIO)")
    void me_UsuarioAutenticado_DevuelveIdentidadExactaDelJwt() throws Exception {
        Usuario usuario = registrar("me.identidad." + sufijo() + "@easymarket.com");
        Cookie cookieJwt = obtenerCookieJwt(usuario.getEmail());

        mockMvc.perform(get("/usuarios/me").cookie(cookieJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(usuario.getId()))
                .andExpect(jsonPath("$.email").value(usuario.getEmail()))
                .andExpect(jsonPath("$.rol").value("USUARIO"));

        Usuario guardado = usuarioRepository.findByEmail(usuario.getEmail()).orElseThrow();
        assertThat(guardado.getId()).isEqualTo(usuario.getId());
        assertThat(guardado.getEmail()).isEqualTo(usuario.getEmail());
    }

    /**
     * Verifica el aislamiento por destinatario: dos usuarios distintos autenticados reciben cada
     * uno su propia identidad y nunca la del otro.
     *
     * @throws Exception si la petición HTTP no puede ejecutarse
     */
    @Test
    @DisplayName("GET /usuarios/me con dos usuarios devuelve a cada uno su propia identidad")
    void me_DosUsuarios_CadaUnoVeSuPropiaIdentidad() throws Exception {
        Usuario usuarioA = registrar("me.isolacion.a." + sufijo() + "@easymarket.com");
        Usuario usuarioB = registrar("me.isolacion.b." + sufijo() + "@easymarket.com");

        mockMvc.perform(get("/usuarios/me").cookie(obtenerCookieJwt(usuarioA.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(usuarioA.getId()))
                .andExpect(jsonPath("$.email").value(usuarioA.getEmail()))
                .andExpect(jsonPath("$.rol").value("USUARIO"));

        mockMvc.perform(get("/usuarios/me").cookie(obtenerCookieJwt(usuarioB.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(usuarioB.getId()))
                .andExpect(jsonPath("$.email").value(usuarioB.getEmail()))
                .andExpect(jsonPath("$.rol").value("USUARIO"));
    }

    /**
     * Verifica que una petición sin cookie JWT no expone identidad alguna: la cadena de seguridad
     * bloquea la ruta con HTTP 403 (mismo assert de "sin autenticación" de los tests de
     * integración existentes del proyecto).
     *
     * @throws Exception si la petición HTTP no puede ejecutarse
     */
    @Test
    @DisplayName("GET /usuarios/me sin cookie retorna 403 Forbidden")
    void me_SinCookie_Retorna403Forbidden() throws Exception {
        mockMvc.perform(get("/usuarios/me"))
                .andExpect(status().isForbidden());
    }

    /**
     * Verifica que el cuerpo de {@code GET /usuarios/me} no contiene campos sensibles extra: el
     * DTO expone exclusivamente {@code id}, {@code email} y {@code rol} — sin hash de contraseña,
     * saldo ni ningún otro atributo de la entidad.
     *
     * @throws Exception si la petición HTTP no puede ejecutarse
     */
    @Test
    @DisplayName("GET /usuarios/me expone solo id, email y rol — sin campos sensibles")
    void me_BodySoloExponeIdEmailYrol_SinCamposSensibles() throws Exception {
        Usuario usuario = registrar("me.sensible." + sufijo() + "@easymarket.com");
        Cookie cookieJwt = obtenerCookieJwt(usuario.getEmail());

        MvcResult resultado = mockMvc.perform(get("/usuarios/me").cookie(cookieJwt))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode nodo = objectMapper.readTree(resultado.getResponse().getContentAsString());
        Iterator<String> nombres = nodo.fieldNames();
        Set<String> campos = StreamSupport.stream(
                        Spliterators.spliteratorUnknownSize(nombres, Spliterator.ORDERED), false)
                .collect(Collectors.toSet());
        assertThat(campos).containsExactlyInAnyOrder("id", "email", "rol");
    }

    /**
     * Crea un usuario mediante el endpoint de registro HTTP real y devuelve la entidad persistida.
     *
     * @param email dirección de correo única del usuario a registrar
     * @return entidad {@link Usuario} persistida por el registro real
     * @throws Exception si la petición HTTP de registro no puede ejecutarse
     */
    private Usuario registrar(String email) throws Exception {
        mockMvc.perform(post("/auth/registro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegistroRequestDto(email, PASSWORD_RAW))))
                .andExpect(status().isCreated());

        Optional<Usuario> guardado = usuarioRepository.findByEmail(email);
        assertThat(guardado).isPresent();
        return guardado.get();
    }

    /**
     * Ejecuta el login HTTP real y extrae la cookie JWT que autentica una petición posterior.
     *
     * @param email correo del usuario cuyas credenciales se usarán
     * @return cookie HTTP-only JWT emitida por {@code POST /auth/login}
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
