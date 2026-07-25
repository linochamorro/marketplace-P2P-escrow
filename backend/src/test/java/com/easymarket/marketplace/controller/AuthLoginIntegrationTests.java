package com.easymarket.marketplace.controller;

import com.easymarket.marketplace.dto.LoginRequestDto;
import com.easymarket.marketplace.model.LoginAttempt;
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
 * Pruebas de integración para el endpoint {@code POST /auth/login} y su interacción completa
 * con la base de datos PostgreSQL real mediante Testcontainers.
 *
 * <p>Verifica los requerimientos de la Story 0b de {@code spec.md} y el contrato de {@code plan.md}:
 * <ul>
 *   <li>Respuesta HTTP 200 OK con cookie {@code httpOnly; Secure; SameSite=None} tras un login exitoso.</li>
 *   <li>Respuesta HTTP 401 Unauthorized con mensaje genérico tras un intento fallido de credenciales.</li>
 *   <li>Ejecución atómica real de {@code registrarFalloAtomic()} y {@code registrarExitoAtomic()} contra PostgreSQL.</li>
 *   <li>Respuesta HTTP 429 Too Many Requests al superar el umbral de intentos fallidos.</li>
 * </ul>
 * </p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=clave-secreta-para-pruebas-de-integracion-login-min-32-chars"
})
class AuthLoginIntegrationTests {

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

    private final String emailPrueba = "usuario.login@easymarket.com";
    private final String rawPassword = "PasswordSeguro123";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        loginAttemptRepository.deleteAll();
        usuarioRepository.deleteAll();

        Usuario usuario = new Usuario(
                emailPrueba,
                passwordEncoder.encode(rawPassword),
                Rol.USUARIO,
                0L,
                ZonedDateTime.now(ZoneId.of("America/Lima"))
        );
        usuarioRepository.save(usuario);
    }

    @Test
    @DisplayName("POST /auth/login con credenciales válidas retorna HTTP 200 y setea cookie httpOnly Secure SameSite=None")
    void login_CredencialesValidas_Retorna200YSeteaCookie() throws Exception {
        LoginRequestDto requestDto = new LoginRequestDto(emailPrueba, rawPassword);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isOk())
                .andExpect(header().exists("Set-Cookie"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("jwt=")))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("HttpOnly")))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Secure")))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("SameSite=None")))
                .andExpect(jsonPath("$.mensaje").value("Inicio de sesión exitoso"));
    }

    @Test
    @DisplayName("POST /auth/login con contraseña incorrecta ejecuta registrarFalloAtomic en Postgres y retorna HTTP 401")
    void login_PasswordIncorrecta_EjecutaRegistrarFalloAtomicEnPostgresYRetorna401() throws Exception {
        LoginRequestDto requestDto = new LoginRequestDto(emailPrueba, "PasswordErronea123");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.mensaje").value("Credenciales inválidas"));

        // Verificación en PostgreSQL real mediante Testcontainers
        Optional<LoginAttempt> attemptOpt = loginAttemptRepository.findByEmailAndIp(emailPrueba, "127.0.0.1");
        assertThat(attemptOpt).isPresent();
        assertThat(attemptOpt.get().getIntentos()).isEqualTo(1);
    }

    @Test
    @DisplayName("POST /auth/login tras 3 fallos retorna HTTP 429 Too Many Requests y no incrementa intentos durante bloqueo")
    void login_3FallosConsecutivos_Retorna429yNoProcesaNuevosIntentos() throws Exception {
        LoginRequestDto requestDtoFallido = new LoginRequestDto(emailPrueba, "PasswordErronea123");

        // Ejecutar 3 fallos consecutivos
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(requestDtoFallido)))
                    .andExpect(status().isUnauthorized());
        }

        // Verificar en Postgres que se registró el bloqueo de 5m (intentos = 3)
        Optional<LoginAttempt> attemptOpt = loginAttemptRepository.findByEmailAndIp(emailPrueba, "127.0.0.1");
        assertThat(attemptOpt).isPresent();
        assertThat(attemptOpt.get().getIntentos()).isEqualTo(3);
        assertThat(attemptOpt.get().getBloqueadoHasta()).isNotNull();

        // 4º intento: Debe ser rechazado inmediatamente con HTTP 429 por evaluarAcceso()
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDtoFallido)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.mensaje").value("Cuenta o IP temporalmente bloqueada por demasiados intentos fallidos"));

        // Verificar que los intentos no incrementaron a 4 durante el bloqueo
        Optional<LoginAttempt> attemptPostBloqueo = loginAttemptRepository.findByEmailAndIp(emailPrueba, "127.0.0.1");
        assertThat(attemptPostBloqueo).isPresent();
        assertThat(attemptPostBloqueo.get().getIntentos()).isEqualTo(3);
    }

    @Test
    @DisplayName("POST /auth/login exitoso tras fallos previos ejecuta registrarExitoAtomic en Postgres y reinicia contador")
    void login_ExitoTrasFallosPrevios_EjecutaRegistrarExitoAtomicEnPostgres() throws Exception {
        LoginRequestDto requestDtoFallido = new LoginRequestDto(emailPrueba, "PasswordErronea123");
        LoginRequestDto requestDtoValido = new LoginRequestDto(emailPrueba, rawPassword);

        // 2 fallos previos
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(requestDtoFallido)))
                    .andExpect(status().isUnauthorized());
        }

        Optional<LoginAttempt> attemptPrevio = loginAttemptRepository.findByEmailAndIp(emailPrueba, "127.0.0.1");
        assertThat(attemptPrevio).isPresent();
        assertThat(attemptPrevio.get().getIntentos()).isEqualTo(2);

        // Intento exitoso
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDtoValido)))
                .andExpect(status().isOk());

        // Verificación en PostgreSQL real de registrarExitoAtomic
        Optional<LoginAttempt> attemptPostExito = loginAttemptRepository.findByEmailAndIp(emailPrueba, "127.0.0.1");
        assertThat(attemptPostExito).isPresent();
        assertThat(attemptPostExito.get().getIntentos()).isEqualTo(0);
        assertThat(attemptPostExito.get().getBloqueadoHasta()).isNull();
    }
}
