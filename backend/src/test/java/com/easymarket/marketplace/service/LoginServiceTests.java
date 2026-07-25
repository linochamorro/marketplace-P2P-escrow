package com.easymarket.marketplace.service;

import com.easymarket.marketplace.exception.CredencialesInvalidasException;
import com.easymarket.marketplace.model.Rol;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias para {@link LoginService}.
 *
 * <p>Verifica los requerimientos de autenticación de la Story 0b de {@code spec.md}
 * y la sección de Autenticación de {@code plan.md}:
 * <ul>
 *   <li>Credenciales válidas devuelven un token JWT firmado y no nulo.</li>
 *   <li>Email inexistente lanza {@link CredencialesInvalidasException} con mensaje genérico.</li>
 *   <li>Contraseña incorrecta lanza {@link CredencialesInvalidasException} con el mismo mensaje genérico.</li>
 * </ul>
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class LoginServiceTests {

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    private LoginService loginService;

    private Usuario usuarioEjemplo;

    @BeforeEach
    void setUp() {
        loginService = new LoginService(usuarioRepository, passwordEncoder, jwtService);

        usuarioEjemplo = new Usuario(
                "test@easymarket.com",
                "$2a$10$hashedPasswordValueExample",
                Rol.USUARIO,
                0L,
                ZonedDateTime.now(ZoneId.of("America/Lima"))
        );
        usuarioEjemplo.setId(1L);
    }

    @Test
    @DisplayName("login() con credenciales correctas genera y retorna un JWT válido")
    void login_CredencialesCorrectas_RetornaJwtValido() {
        // Arrange
        String email = "test@easymarket.com";
        String rawPassword = "Password123";
        String expectedJwt = "eyJhbGciOiJIUzI1NiJ9.tokenEjemploMock.signature";

        when(usuarioRepository.findByEmail(email)).thenReturn(Optional.of(usuarioEjemplo));
        when(passwordEncoder.matches(rawPassword, usuarioEjemplo.getPasswordHash())).thenReturn(true);
        when(jwtService.generarToken(usuarioEjemplo)).thenReturn(expectedJwt);

        // Act
        String tokenObtenido = loginService.login(email, rawPassword);

        // Assert
        assertThat(tokenObtenido)
                .isNotNull()
                .isNotEmpty()
                .isEqualTo(expectedJwt);
    }

    @Test
    @DisplayName("login() con email inexistente rechaza con CredencialesInvalidasException y mensaje genérico")
    void login_EmailNoExiste_LanzaCredencialesInvalidasException() {
        // Arrange
        String emailInexistente = "noexiste@easymarket.com";
        String rawPassword = "Password123";

        when(usuarioRepository.findByEmail(emailInexistente)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> loginService.login(emailInexistente, rawPassword))
                .isInstanceOf(CredencialesInvalidasException.class)
                .hasMessage("Credenciales inválidas");
    }

    @Test
    @DisplayName("login() con contraseña incorrecta rechaza con la misma CredencialesInvalidasException y mensaje genérico")
    void login_PasswordIncorrecta_LanzaCredencialesInvalidasException() {
        // Arrange
        String email = "test@easymarket.com";
        String passwordErronea = "PasswordErronea123";

        when(usuarioRepository.findByEmail(email)).thenReturn(Optional.of(usuarioEjemplo));
        when(passwordEncoder.matches(passwordErronea, usuarioEjemplo.getPasswordHash())).thenReturn(false);

        // Act & Assert
        assertThatThrownBy(() -> loginService.login(email, passwordErronea))
                .isInstanceOf(CredencialesInvalidasException.class)
                .hasMessage("Credenciales inválidas");
    }
}
