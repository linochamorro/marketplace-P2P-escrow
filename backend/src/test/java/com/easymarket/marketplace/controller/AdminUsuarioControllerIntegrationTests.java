package com.easymarket.marketplace.controller;

import com.easymarket.marketplace.dto.LoginRequestDto;
import com.easymarket.marketplace.model.AdminAccion;
import com.easymarket.marketplace.model.LoginAttempt;
import com.easymarket.marketplace.model.Rol;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.AdminAccionRepository;
import com.easymarket.marketplace.repository.LoginAttemptRepository;
import com.easymarket.marketplace.repository.UsuarioRepository;
import com.easymarket.marketplace.service.RateLimitingService;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración TDD para el endpoint {@code POST /admin/usuarios/{id}/desbloquear}.
 *
 * <p>Verifica el cumplimiento de los criterios de la Story 0c (spec.md) y de la tarea PHA01TSK08:
 * <ul>
 *   <li>Acceso rechazado con HTTP 403 Forbidden cuando un usuario con rol {@code USUARIO} intenta la llamada.</li>
 *   <li>Acceso permitido con HTTP 200 OK cuando un usuario con rol {@code ADMIN} ejecuta el desbloqueo sobre una cuenta en bloqueo permanente.</li>
 *   <li>Registro obligatorio en la tabla {@code admin_acciones} con el ID real del admin extraído del token JWT.</li>
 *   <li>Rechazo con HTTP 400 Bad Request cuando la cuenta no se encuentra en bloqueo permanente.</li>
 * </ul>
 * </p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=clave-secreta-para-pruebas-de-integracion-admin-min-32-chars",
    "ADMIN_EMAIL=admin.seed@easymarket.com",
    "ADMIN_PASSWORD_HASH=$2a$10$R9h/cIPz0gi.URNNXRkh2OPST9/PgBkqquzi.Ss7KIUgO2t0jWMUW"
})
public class AdminUsuarioControllerIntegrationTests {

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
    private AdminAccionRepository adminAccionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Usuario usuarioRegular;
    private Usuario usuarioAdmin;
    private final String passwordRaw = "PasswordSeguro123!";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        adminAccionRepository.deleteAll();
        loginAttemptRepository.deleteAll();
        usuarioRepository.deleteAll();

        // 1. Crear usuario regular
        usuarioRegular = new Usuario(
                "usuario.comun@easymarket.com",
                passwordEncoder.encode(passwordRaw),
                Rol.USUARIO,
                0L,
                ZonedDateTime.now(ZoneId.of("America/Lima"))
        );
        usuarioRegular = usuarioRepository.save(usuarioRegular);

        // 2. Crear usuario admin
        usuarioAdmin = new Usuario(
                "admin.test@easymarket.com",
                passwordEncoder.encode(passwordRaw),
                Rol.ADMIN,
                0L,
                ZonedDateTime.now(ZoneId.of("America/Lima"))
        );
        usuarioAdmin = usuarioRepository.save(usuarioAdmin);
    }

    /**
     * Realiza login autenticado mediante HTTP POST /auth/login y extrae la cookie JWT producida.
     *
     * @param email correo electrónico de la cuenta
     * @param password contraseña en texto plano
     * @return cookie HTTP "jwt"
     * @throws Exception si falla el login
     */
    private Cookie obtenerCookieJwtPostLogin(String email, String password) throws Exception {
        LoginRequestDto loginRequest = new LoginRequestDto(email, password);

        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        jakarta.servlet.http.Cookie cookieJwt = result.getResponse().getCookie("jwt");
        assertThat(cookieJwt).isNotNull();
        return cookieJwt;
    }

    @Test
    @DisplayName("POST /admin/usuarios/{id}/desbloquear realizado por usuario no-admin retorna 403 Forbidden")
    void desbloquear_UsuarioNoAdmin_Retorna403Forbidden() throws Exception {
        Cookie cookieUsuarioRegular = obtenerCookieJwtPostLogin(usuarioRegular.getEmail(), passwordRaw);

        mockMvc.perform(post("/admin/usuarios/" + usuarioRegular.getId() + "/desbloquear")
                        .cookie(cookieUsuarioRegular))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /admin/usuarios/{id}/desbloquear por admin sobre usuario bloqueado permanentemente retorna 200 OK y registra auditoría append-only")
    void desbloquear_AdminAutenticadoYCuentaEnBloqueoPermanente_Retorna200YRegistraAuditoria() throws Exception {
        // Simular bloqueo permanente para usuarioRegular (12 intentos)
        LoginAttempt attempt = new LoginAttempt(
                usuarioRegular.getEmail(),
                "192.168.1.50",
                12,
                RateLimitingService.FECHA_BLOQUEO_PERMANENTE
        );
        loginAttemptRepository.save(attempt);

        Cookie cookieAdmin = obtenerCookieJwtPostLogin(usuarioAdmin.getEmail(), passwordRaw);

        mockMvc.perform(post("/admin/usuarios/" + usuarioRegular.getId() + "/desbloquear")
                        .cookie(cookieAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mensaje").value("Cuenta desbloqueada exitosamente"));

        // Verificación en PostgreSQL real de auditoría admin_acciones
        List<AdminAccion> acciones = adminAccionRepository.findAll();
        assertThat(acciones).hasSize(1);

        AdminAccion accion = acciones.get(0);
        assertThat(accion.getAdminId()).isEqualTo(usuarioAdmin.getId());
        assertThat(accion.getAccion()).isEqualTo("DESBLOQUEO_CUENTA");
        assertThat(accion.getUsuarioAfectadoId()).isEqualTo(usuarioRegular.getId());
        assertThat(accion.getDetalle()).contains(usuarioRegular.getEmail());
    }

    @Test
    @DisplayName("POST /admin/usuarios/{id}/desbloquear por admin sobre usuario no bloqueado permanentemente retorna 400 Bad Request")
    void desbloquear_AdminAutenticadoPeroUsuarioNoBloqueadoPermanentemente_Retorna400BadRequest() throws Exception {
        Cookie cookieAdmin = obtenerCookieJwtPostLogin(usuarioAdmin.getEmail(), passwordRaw);

        mockMvc.perform(post("/admin/usuarios/" + usuarioRegular.getId() + "/desbloquear")
                        .cookie(cookieAdmin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value(org.hamcrest.Matchers.containsString("bloqueo permanente")));
    }
}
