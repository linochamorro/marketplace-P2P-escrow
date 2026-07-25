package com.easymarket.marketplace.service;

import com.easymarket.marketplace.exception.EmailYaRegistradoException;
import com.easymarket.marketplace.exception.PasswordInvalidaException;
import com.easymarket.marketplace.model.Rol;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.UsuarioRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Servicio de dominio responsable del registro de usuarios en EasyMarket.
 *
 * <p>Aplica la política de seguridad y reglas de negocio definidas en la Story 0 de {@code spec.md}
 * y la sección de Autenticación de {@code plan.md}:
 * <ul>
 *   <li>Validación de contraseña (mínimo 8 caracteres, sin reglas de complejidad adicionales).</li>
 *   <li>Verificación de unicidad del correo electrónico antes de la persistencia.</li>
 *   <li>Hashing de contraseña utilizando {@link PasswordEncoder} (BCrypt con factor por defecto).</li>
 *   <li>Asignación estricta del rol {@link Rol#USUARIO} (el registro público nunca crea cuentas {@link Rol#ADMIN}).</li>
 * </ul>
 * </p>
 */
@Service
public class RegistroService {

    private final UsuarioRepository usuarioRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * Constructor con inyección de dependencias para el servicio de registro.
     *
     * @param usuarioRepository repositorio para la persistencia de usuarios
     * @param passwordEncoder codificador de contraseñas (BCryptPasswordEncoder)
     */
    public RegistroService(UsuarioRepository usuarioRepository, PasswordEncoder passwordEncoder) {
        this.usuarioRepository = usuarioRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Registra un nuevo usuario en la plataforma con el rol {@link Rol#USUARIO}.
     *
     * @param email correo electrónico único del usuario
     * @param password contraseña en texto plano (debe cumplir la política de ≥8 caracteres)
     * @return el {@link Usuario} creado y guardado en la base de datos
     * @throws PasswordInvalidaException si la contraseña tiene menos de 8 caracteres o es {@code null}
     * @throws EmailYaRegistradoException si el email ya existe en la base de datos
     */
    @Transactional
    public Usuario registrarUsuario(String email, String password) {
        if (password == null || password.length() < 8) {
            throw new PasswordInvalidaException("La contraseña debe tener al menos 8 caracteres");
        }

        if (usuarioRepository.existsByEmail(email)) {
            throw new EmailYaRegistradoException("El email '" + email + "' ya se encuentra registrado");
        }

        String passwordHash = passwordEncoder.encode(password);
        ZonedDateTime ahora = ZonedDateTime.now(ZoneId.of("America/Lima"));

        Usuario nuevoUsuario = new Usuario(
                email,
                passwordHash,
                Rol.USUARIO,
                0L,
                ahora
        );

        return usuarioRepository.save(nuevoUsuario);
    }
}
