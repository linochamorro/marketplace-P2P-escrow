package com.easymarket.marketplace.service;

import com.easymarket.marketplace.exception.CredencialesInvalidasException;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.UsuarioRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Servicio de dominio responsable de la autenticación de usuarios (login) en EasyMarket.
 *
 * <p>Aplica las reglas de negocio y seguridad definidas en la Story 0b de {@code spec.md}
 * y la sección de Autenticación de {@code plan.md}:
 * <ul>
 *   <li>Verificación de credenciales (email y contraseña) utilizando {@link PasswordEncoder} (BCrypt).</li>
 *   <li>Generación de un token JWT válido (expiración 24h) en caso de éxito.</li>
 *   <li>Rechazo con una excepción genérica única {@link CredencialesInvalidasException} ante fallos
 *       (email no existente o contraseña incorrecta) para prevenir la enumeración de usuarios.</li>
 * </ul>
 * </p>
 */
@Service
public class LoginService {

    private final UsuarioRepository usuarioRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    /**
     * Constructor con inyección de dependencias para el servicio de inicio de sesión.
     *
     * @param usuarioRepository repositorio para la consulta de usuarios
     * @param passwordEncoder codificador de contraseñas (BCryptPasswordEncoder)
     * @param jwtService servicio para la generación de tokens JWT
     */
    public LoginService(
            UsuarioRepository usuarioRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService
    ) {
        this.usuarioRepository = usuarioRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    /**
     * Inicia sesión verificando las credenciales provistas y retorna un token JWT válido.
     *
     * @param email correo electrónico del usuario
     * @param password contraseña en texto plano
     * @return token JWT generado tras una autenticación exitosa
     * @throws CredencialesInvalidasException si el email no existe o la contraseña no coincide
     */
    @Transactional(readOnly = true)
    public String login(String email, String password) {
        Optional<Usuario> usuarioOpt = usuarioRepository.findByEmail(email);

        if (usuarioOpt.isEmpty()) {
            throw new CredencialesInvalidasException("Credenciales inválidas");
        }

        Usuario usuario = usuarioOpt.get();

        if (!passwordEncoder.matches(password, usuario.getPasswordHash())) {
            throw new CredencialesInvalidasException("Credenciales inválidas");
        }

        return jwtService.generarToken(usuario);
    }
}
