package com.easymarket.marketplace.service;

import com.easymarket.marketplace.exception.EmailYaRegistradoException;
import com.easymarket.marketplace.exception.PasswordInvalidaException;
import com.easymarket.marketplace.model.Rol;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Tests unitarios para el servicio de dominio {@link RegistroService}.
 *
 * <p>Verifica la regla de negocio de registro de usuarios (Story 0 de {@code spec.md}):
 * <ul>
 *   <li>Rechazo de contraseñas de menos de 8 caracteres.</li>
 *   <li>Rechazo de correos electrónicos duplicados.</li>
 *   <li>Hashing seguro de contraseña con BCrypt donde el hash nunca equivale al texto plano.</li>
 *   <li>Asignación estricta y exclusiva del rol {@link Rol#USUARIO}.</li>
 * </ul>
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class RegistroServiceTests {

    @Mock
    private UsuarioRepository usuarioRepository;

    private PasswordEncoder passwordEncoder;
    private RegistroService registroService;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        registroService = new RegistroService(usuarioRepository, passwordEncoder);
    }

    /**
     * Test previo Red Phase: Rechaza el registro si la contraseña no cumple la política de al menos 8 caracteres.
     */
    @Test
    @DisplayName("Rechaza contraseñas de menos de 8 caracteres con PasswordInvalidaException")
    void testRechazaContrasenaCorta() {
        assertThatThrownBy(() -> registroService.registrarUsuario("nuevo@example.com", "1234567"))
                .isInstanceOf(PasswordInvalidaException.class)
                .hasMessageContaining("8 caracteres");
    }

    /**
     * Test previo Red Phase: Rechaza el registro si el email ya existe en el repositorio de usuarios.
     */
    @Test
    @DisplayName("Rechaza emails duplicados con EmailYaRegistradoException")
    void testRechazaEmailDuplicado() {
        when(usuarioRepository.existsByEmail("existente@example.com")).thenReturn(true);

        assertThatThrownBy(() -> registroService.registrarUsuario("existente@example.com", "passwordSegura123"))
                .isInstanceOf(EmailYaRegistradoException.class)
                .hasMessageContaining("ya se encuentra registrado");
    }

    /**
     * Test previo Red Phase: Registra exitosamente al usuario, codificando la contraseña con BCrypt y asignando rol USUARIO.
     */
    @Test
    @DisplayName("Registro exitoso asigna rol USUARIO y hash BCrypt que difiere del texto plano")
    void testRegistroExitosoYHashNoIgualAPasswordPlano() {
        String email = "nuevo@example.com";
        String passwordPlano = "seguridad123";

        when(usuarioRepository.existsByEmail(email)).thenReturn(false);
        when(usuarioRepository.save(any(Usuario.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Usuario usuarioCreado = registroService.registrarUsuario(email, passwordPlano);

        assertThat(usuarioCreado).isNotNull();
        assertThat(usuarioCreado.getEmail()).isEqualTo(email);
        assertThat(usuarioCreado.getRol()).isEqualTo(Rol.USUARIO);
        assertThat(usuarioCreado.getSaldoDisponible()).isZero();
        assertThat(usuarioCreado.getPasswordHash()).isNotEqualTo(passwordPlano);
        assertThat(passwordEncoder.matches(passwordPlano, usuarioCreado.getPasswordHash())).isTrue();
    }
}
