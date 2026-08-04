package com.easymarket.marketplace.controller;

import com.easymarket.marketplace.dto.RegistroRequestDto;
import com.easymarket.marketplace.model.Rol;
import com.easymarket.marketplace.model.Usuario;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración para el endpoint {@code POST /auth/registro} y su persistencia real
 * en PostgreSQL utilizando Testcontainers.
 *
 * <p>Verifica los requerimientos de la Story 0 de {@code spec.md} y la API REST:
 * <ul>
 *   <li>Respuesta HTTP 201 Created con DTO seguro (sin campo {@code passwordHash}) tras registro válido.</li>
 *   <li>Respuesta HTTP 409 Conflict cuando el email ya existe en la base de datos.</li>
 *   <li>Respuesta HTTP 400 Bad Request cuando la contraseña no cumple la política mínima (≥8 caracteres).</li>
 * </ul>
 * </p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=clave-secreta-para-pruebas-de-integracion-registro-min-32-chars"
})
class AuthRegistrationIntegrationTests {

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
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        usuarioRepository.deleteAll();
    }

    @Test
    @DisplayName("POST /auth/registro con datos válidos retorna HTTP 201 Created, guarda en PostgreSQL real y no expone passwordHash")
    void registro_DatosValidos_Retorna201CreatedYRespuestaSinPasswordHash() throws Exception {
        String email = "nuevo.usuario@easymarket.com";
        String password = "PasswordValida123";
        RegistroRequestDto requestDto = new RegistroRequestDto(email, password);

        mockMvc.perform(post("/auth/registro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.rol").value("USUARIO"))
                .andExpect(jsonPath("$.saldoDisponible").value(0))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());

        // Verificación en PostgreSQL real mediante Testcontainers
        Optional<Usuario> usuarioOpt = usuarioRepository.findByEmail(email);
        assertThat(usuarioOpt).isPresent();
        Usuario usuarioGuardado = usuarioOpt.get();
        assertThat(usuarioGuardado.getRol()).isEqualTo(Rol.USUARIO);
        assertThat(usuarioGuardado.getSaldoDisponible()).isEqualTo(0L);
        assertThat(passwordEncoder.matches(password, usuarioGuardado.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("POST /auth/registro con email duplicado retorna HTTP 409 Conflict vía GlobalExceptionHandler")
    void registro_EmailDuplicado_Retorna409Conflict() throws Exception {
        String emailExistente = "duplicado@easymarket.com";
        RegistroRequestDto requestDto = new RegistroRequestDto(emailExistente, "PasswordSeguro123");

        // Registrar primer usuario en DB
        mockMvc.perform(post("/auth/registro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isCreated());

        // Intentar registrar el mismo email de nuevo
        mockMvc.perform(post("/auth/registro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value("El email '" + emailExistente + "' ya se encuentra registrado"));
    }

    @Test
    @DisplayName("POST /auth/registro con contraseña corta (<8 caracteres) retorna HTTP 400 Bad Request vía GlobalExceptionHandler")
    void registro_PasswordCorta_Retorna400BadRequest() throws Exception {
        RegistroRequestDto requestDto = new RegistroRequestDto("usuario.corto@easymarket.com", "12345");

        mockMvc.perform(post("/auth/registro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("La contraseña debe tener al menos 8 caracteres"));
    }
}
