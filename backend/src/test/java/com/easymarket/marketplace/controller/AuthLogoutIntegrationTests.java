package com.easymarket.marketplace.controller;

import com.easymarket.marketplace.dto.LoginRequestDto;
import com.easymarket.marketplace.model.Rol;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.LoginAttemptRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración del endpoint {@code POST /auth/logout} (PHA07TSK02; plan.md, sección
 * Autenticación — fila Logout — y Fase 7 — filas Logout y Tercero en detalle).
 *
 * <p>Usa PostgreSQL real mediante Testcontainers y aplica {@code springSecurity()} al MockMvc para
 * ejercitar la cadena de seguridad real (incluido {@code JwtAuthenticationFilter}): el caso "con
 * {@code jwt} expirada" de la fila de tarea depende de que el filtro tolere un token inválido o
 * expirado limpiando el contexto y dejando pasar la petición (la ruta {@code /auth/**} es
 * {@code permitAll}). Verifica el contrato exacto de {@code plan.md} Fase 7 — fila Logout: la
 * respuesta devuelve {@code Set-Cookie} para {@code jwt} con valor vacío, {@code Max-Age=0},
 * {@code Path=/}, {@code HttpOnly}, {@code Secure} y {@code SameSite=None}, sin invalidación
 * server-side del JWT (exclusión deliberada de spec.md, Story 0b).</p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=clave-secreta-para-pruebas-de-integracion-logout-min-32-chars"
})
class AuthLogoutIntegrationTests {

    /** Contenedor PostgreSQL real usado por Flyway y por el contexto arrancado del test. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Contexto web de la aplicación usado para construir el MockMvc con la cadena de seguridad real. */
    @Autowired
    private WebApplicationContext webApplicationContext;

    /** Repositorio de intentos de login, limpiado para aislar el rate limiting del escenario. */
    @Autowired
    private LoginAttemptRepository loginAttemptRepository;

    /** Repositorio de usuarios, limpiado y repoblado con la identidad del escenario de login real. */
    @Autowired
    private UsuarioRepository usuarioRepository;

    /** Codificador BCrypt usado para persistir la contraseña del usuario de login real. */
    @Autowired
    private PasswordEncoder passwordEncoder;

    /** Serializador JSON usado para construir el cuerpo del login real. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Cliente MockMvc configurado con la cadena de seguridad real de la aplicación. */
    private MockMvc mockMvc;

    /** Credenciales de la identidad de prueba usadas por el escenario de login real. */
    private static final String EMAIL_PRUEBA = "usuario.logout@easymarket.com";

    /** Contraseña plana de la identidad de prueba (persistida hasheada con BCrypt). */
    private static final String PASSWORD_RAW = "PasswordSeguro123";

    /**
     * Prepara el MockMvc con la cadena de seguridad real y aísla las tablas de identidad para cada
     * escenario HTTP.
     */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        loginAttemptRepository.deleteAll();
        usuarioRepository.deleteAll();

        Usuario usuario = new Usuario(
                EMAIL_PRUEBA,
                passwordEncoder.encode(PASSWORD_RAW),
                Rol.USUARIO,
                0L,
                ZonedDateTime.now(ZoneId.of("America/Lima"))
        );
        usuarioRepository.save(usuario);
    }

    /**
     * Verifica el contrato central de {@code plan.md} Fase 7 — fila Logout: una petición
     * {@code POST /auth/logout} sin cookie previa responde HTTP 200 y devuelve en {@code Set-Cookie}
     * la cookie {@code jwt} con valor vacío y los atributos exactos {@code Max-Age=0},
     * {@code Path=/}, {@code HttpOnly}, {@code Secure} y {@code SameSite=None}. El caso sin cookie
     * también cubre la idempotencia del logout: no requiere sesión previa para expirar la cookie.
     *
     * @throws Exception si la interacción HTTP o el parseo de la respuesta falla
     */
    @Test
    @DisplayName("POST /auth/logout sin cookie retorna 200 con Set-Cookie jwt vacía y Max-Age=0 con los atributos de plan.md")
    void logout_SinCookie_Retorna200ConSetCookieExpiranteConAtributosDePlan() throws Exception {
        MvcResult resultado = mockMvc.perform(post("/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(header().exists("Set-Cookie"))
                .andExpect(header().string("Set-Cookie", containsString("jwt=")))
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")))
                .andExpect(header().string("Set-Cookie", containsString("Path=/")))
                .andExpect(header().string("Set-Cookie", containsString("HttpOnly")))
                .andExpect(header().string("Set-Cookie", containsString("Secure")))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=None")))
                .andExpect(jsonPath("$.mensaje").value("Sesión cerrada"))
                .andReturn();

        Cookie cookie = resultado.getResponse().getCookie("jwt");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isEmpty();
        assertThat(cookie.getMaxAge()).isZero();
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isTrue();
    }

    /**
     * Verifica la robustez del contrato "con {@code jwt} expirada" de la fila de tarea: una cookie
     * {@code jwt} con un token corrupto (firma inválida) no bloquea el logout. El
     * {@code JwtAuthenticationFilter} falla al validarlo, limpia el contexto y deja pasar la
     * petición ({@code /auth/**} es {@code permitAll}); el endpoint responde 200 con la misma
     * {@code Set-Cookie} expirante. Un token expirado recorre el mismo camino de excepción del
     * filtro (jjwt lanza {@code JwtException} en ambos casos), por lo que el token corrupto cubre
     * determinísticamente la rama "expirada o inválida" sin depender de relojes.
     *
     * @throws Exception si la interacción HTTP o el parseo de la respuesta falla
     */
    @Test
    @DisplayName("POST /auth/logout con cookie jwt inválida retorna 200 con Set-Cookie expirante")
    void logout_ConCookieJwtInvalida_Retorna200ConSetCookieExpirante() throws Exception {
        Cookie cookieCorrupta = new Cookie("jwt", "token-corrupto-no-firmado");

        mockMvc.perform(post("/auth/logout").cookie(cookieCorrupta))
                .andExpect(status().isOk())
                .andExpect(header().exists("Set-Cookie"))
                .andExpect(header().string("Set-Cookie", containsString("jwt=")))
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")))
                .andExpect(header().string("Set-Cookie", containsString("Path=/")))
                .andExpect(header().string("Set-Cookie", containsString("HttpOnly")))
                .andExpect(header().string("Set-Cookie", containsString("Secure")))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=None")))
                .andExpect(jsonPath("$.mensaje").value("Sesión cerrada"));
    }

    /**
     * Verifica el flujo real de cierre de sesión: tras un login autenticado (cookie {@code jwt}
     * con token válido emitida por {@code POST /auth/login}), el logout responde 200 y su
     * {@code Set-Cookie} expira la cookie con valor VACÍO — el token del login ya no aparece en la
     * cabecera de respuesta ni en la cookie parseada, y los atributos del contrato se conservan.
     *
     * @throws Exception si la interacción HTTP o el parseo de la respuesta falla
     */
    @Test
    @DisplayName("POST /auth/logout tras un login real limpia el valor de la cookie jwt emitida")
    void logout_TrasLoginReal_LimpiaElValorDeLaCookieJwt() throws Exception {
        MvcResult login = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequestDto(EMAIL_PRUEBA, PASSWORD_RAW))))
                .andExpect(status().isOk())
                .andReturn();
        Cookie cookieLogin = login.getResponse().getCookie("jwt");
        assertThat(cookieLogin).isNotNull();
        assertThat(cookieLogin.getValue()).isNotBlank();

        MvcResult resultado = mockMvc.perform(post("/auth/logout").cookie(cookieLogin))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")))
                .andReturn();

        Cookie cookieLogout = resultado.getResponse().getCookie("jwt");
        assertThat(cookieLogout).isNotNull();
        assertThat(cookieLogout.getValue()).isEmpty();
        assertThat(cookieLogout.getMaxAge()).isZero();
        assertThat(resultado.getResponse().getHeader("Set-Cookie")).doesNotContain(cookieLogin.getValue());
    }
}