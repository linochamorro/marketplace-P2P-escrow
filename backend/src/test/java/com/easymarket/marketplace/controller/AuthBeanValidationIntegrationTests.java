package com.easymarket.marketplace.controller;

import com.easymarket.marketplace.dto.LoginRequestDto;
import com.easymarket.marketplace.dto.RegistroRequestDto;
import com.easymarket.marketplace.model.Rol;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.LoginAttemptRepository;
import com.easymarket.marketplace.repository.UsuarioRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración de la validación de entrada Bean Validation en autenticación
 * (PHA16TSK06; informe de auditoría 2026-09-13, hallazgo A5; Story 0 de {@code spec.md}).
 *
 * <p>Verifica con la cadena real (Testcontainers + PostgreSQL) que los DTOs
 * {@code RegistroRequestDto} y {@code LoginRequestDto} rechazan con HTTP 400 las
 * entradas en blanco o con formato inválido, y que los contratos de éxito y las
 * reglas de dominio vigentes (política ≥8, duplicado → 409, rate limiting, 401
 * genérico) se conservan sin cambios.</p>
 *
 * <p>Cambio deliberado de contrato de error documentado en la fila de la tarea:
 * {@code POST /auth/login} con cuerpo vacío o email en blanco pasa de 401 a 400,
 * porque la validación de entrada ({@code @Valid}) se evalúa antes que la
 * verificación de credenciales y el rate limiting.</p>
 *
 * <p>Política PHA12TSK06: ningún fixture crea filas {@code ADMIN}; el usuario
 * auxiliar nace {@code Rol.USUARIO}.</p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=clave-secreta-para-pruebas-bean-validation-auth-min-32c"
})
class AuthBeanValidationIntegrationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private LoginAttemptRepository loginAttemptRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private final String emailLogin = "bean.validation.login@easymarket.com";
    private final String passwordLogin = "PasswordSeguro123";

    /**
     * Limpia las tablas y provisiona un único usuario {@code USUARIO} para los casos de login.
     */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        loginAttemptRepository.deleteAll();
        usuarioRepository.deleteAll();

        Usuario usuario = new Usuario(
                emailLogin,
                passwordEncoder.encode(passwordLogin),
                Rol.USUARIO,
                0L,
                ZonedDateTime.now(ZoneId.of("America/Lima"))
        );
        usuarioRepository.save(usuario);
    }

    @Test
    @DisplayName("POST /auth/registro con cuerpo vacío {} retorna HTTP 400")
    void registro_CuerpoVacio_Retorna400() throws Exception {
        mockMvc.perform(post("/auth/registro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        assertThat(usuarioRepository.count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("POST /auth/registro con email ausente retorna HTTP 400 y no persiste")
    void registro_EmailAusente_Retorna400() throws Exception {
        mockMvc.perform(post("/auth/registro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"PasswordValida123\"}"))
                .andExpect(status().isBadRequest());

        assertThat(usuarioRepository.count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("POST /auth/registro con email vacío retorna HTTP 400 y no crea usuario")
    void registro_EmailVacio_Retorna400() throws Exception {
        RegistroRequestDto requestDto = new RegistroRequestDto("", "PasswordValida123");

        mockMvc.perform(post("/auth/registro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isBadRequest());

        assertThat(usuarioRepository.findByEmail("")).isEmpty();
    }

    @Test
    @DisplayName("POST /auth/registro con email sin formato válido retorna HTTP 400 y no crea usuario")
    void registro_EmailFormatoInvalido_Retorna400() throws Exception {
        RegistroRequestDto requestDto = new RegistroRequestDto("no-es-un-email", "PasswordValida123");

        mockMvc.perform(post("/auth/registro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isBadRequest());

        assertThat(usuarioRepository.findByEmail("no-es-un-email")).isEmpty();
    }

    @Test
    @DisplayName("POST /auth/registro con password ausente retorna HTTP 400")
    void registro_PasswordAusente_Retorna400() throws Exception {
        mockMvc.perform(post("/auth/registro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"valido.ausente@easymarket.com\"}"))
                .andExpect(status().isBadRequest());

        assertThat(usuarioRepository.findByEmail("valido.ausente@easymarket.com")).isEmpty();
    }

    @Test
    @DisplayName("POST /auth/registro con password en blanco retorna HTTP 400")
    void registro_PasswordEnBlanco_Retorna400() throws Exception {
        RegistroRequestDto requestDto = new RegistroRequestDto("valido.blanco@easymarket.com", "   ");

        mockMvc.perform(post("/auth/registro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isBadRequest());

        assertThat(usuarioRepository.findByEmail("valido.blanco@easymarket.com")).isEmpty();
    }

    @Test
    @DisplayName("POST /auth/registro con datos válidos conserva el contrato 201 (regresión)")
    void registro_DatosValidos_Retorna201() throws Exception {
        String email = "bean.validation.nuevo@easymarket.com";
        RegistroRequestDto requestDto = new RegistroRequestDto(email, "PasswordValida123");

        mockMvc.perform(post("/auth/registro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email));

        Optional<Usuario> usuarioOpt = usuarioRepository.findByEmail(email);
        assertThat(usuarioOpt).isPresent();
    }

    @Test
    @DisplayName("POST /auth/registro con email duplicado conserva el contrato 409 (regresión)")
    void registro_EmailDuplicado_Retorna409() throws Exception {
        RegistroRequestDto requestDto = new RegistroRequestDto(emailLogin, "PasswordValida123");

        mockMvc.perform(post("/auth/registro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value("El email '" + emailLogin + "' ya se encuentra registrado"));
    }

    @Test
    @DisplayName("POST /auth/registro con contraseña corta conserva el contrato 400 de dominio (regresión)")
    void registro_PasswordCorta_Retorna400Dominio() throws Exception {
        RegistroRequestDto requestDto = new RegistroRequestDto("bean.validation.corto@easymarket.com", "12345");

        mockMvc.perform(post("/auth/registro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("La contraseña debe tener al menos 8 caracteres"));
    }

    @Test
    @DisplayName("POST /auth/login con cuerpo vacío {} retorna HTTP 400 (cambio deliberado desde 401)")
    void login_CuerpoVacio_Retorna400() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /auth/login con email en blanco retorna HTTP 400 sin tocar rate limiting")
    void login_EmailEnBlanco_Retorna400() throws Exception {
        LoginRequestDto requestDto = new LoginRequestDto("   ", passwordLogin);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isBadRequest());

        assertThat(loginAttemptRepository.findByEmailAndIp("   ", "127.0.0.1")).isEmpty();
    }

    @Test
    @DisplayName("POST /auth/login con password en blanco retorna HTTP 400")
    void login_PasswordEnBlanco_Retorna400() throws Exception {
        LoginRequestDto requestDto = new LoginRequestDto(emailLogin, "");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /auth/login con credenciales válidas conserva 200 con cookie (regresión)")
    void login_CredencialesValidas_Retorna200ConCookie() throws Exception {
        LoginRequestDto requestDto = new LoginRequestDto(emailLogin, passwordLogin);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isOk())
                .andExpect(header().exists("Set-Cookie"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("jwt=")))
                .andExpect(jsonPath("$.mensaje").value("Inicio de sesión exitoso"));
    }

    @Test
    @DisplayName("POST /auth/login con email válido inexistente conserva 401 genérico (regresión)")
    void login_CredencialesInvalidasFormatoValido_Retorna401() throws Exception {
        LoginRequestDto requestDto = new LoginRequestDto("nadie.existe@easymarket.com", "PasswordSeguro123");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.mensaje").value("Credenciales inválidas"));
    }

    @Test
    @DisplayName("POST /auth/login con password incorrecta conserva 401 genérico (regresión)")
    void login_PasswordIncorrecta_Retorna401() throws Exception {
        LoginRequestDto requestDto = new LoginRequestDto(emailLogin, "PasswordErronea123");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.mensaje").value("Credenciales inválidas"));
    }
}
