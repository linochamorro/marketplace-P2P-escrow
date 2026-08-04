package com.easymarket.marketplace.controller;

import com.easymarket.marketplace.exception.UsuarioNoEncontradoException;
import com.easymarket.marketplace.model.LoginAttempt;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.LoginAttemptRepository;
import com.easymarket.marketplace.repository.UsuarioRepository;
import com.easymarket.marketplace.security.UsuarioPrincipal;
import com.easymarket.marketplace.service.DesbloqueoAdminService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Controller REST para las acciones administrativas sobre usuarios en EasyMarket (Story 0c, spec.md).
 *
 * <p>Protegido por el rol {@code ADMIN} mediante la cadena de seguridad y anotaciones de método.
 * El ID del administrador responsable se extrae exclusivamente del token JWT en el {@code SecurityContext},
 * garantizando la integridad del registro de auditoría append-only en {@code admin_acciones}.</p>
 */
@RestController
@RequestMapping("/admin/usuarios")
public class AdminUsuarioController {

    private final DesbloqueoAdminService desbloqueoAdminService;
    private final UsuarioRepository usuarioRepository;
    private final LoginAttemptRepository loginAttemptRepository;

    /**
     * Constructor con inyección de dependencias.
     *
     * @param desbloqueoAdminService servicio de dominio para el desbloqueo administrativo de cuentas
     * @param usuarioRepository repositorio JPA de usuarios
     * @param loginAttemptRepository repositorio JPA de intentos de login
     */
    public AdminUsuarioController(
            DesbloqueoAdminService desbloqueoAdminService,
            UsuarioRepository usuarioRepository,
            LoginAttemptRepository loginAttemptRepository
    ) {
        this.desbloqueoAdminService = desbloqueoAdminService;
        this.usuarioRepository = usuarioRepository;
        this.loginAttemptRepository = loginAttemptRepository;
    }

    /**
     * Endpoint REST {@code POST /admin/usuarios/{id}/desbloquear} para desbloquear una cuenta en estado permanente.
     *
     * <p>Requerimientos de seguridad y auditoría (Story 0c, spec.md):
     * <ul>
     *   <li>Protegido con {@code @PreAuthorize("hasRole('ADMIN')")}.</li>
     *   <li>Extrae el {@code adminId} del usuario autenticado en {@link UsuarioPrincipal}.</li>
     *   <li>Verifica la existencia del usuario afectado.</li>
     *   <li>Desbloquea de forma completa todas las combinaciones de IP con bloqueo permanente para el correo del usuario.</li>
     * </ul>
     * </p>
     *
     * @param usuarioId ID del usuario afectado a desbloquear
     * @param principal datos del administrador autenticado desde el contexto de seguridad
     * @return {@link ResponseEntity} con código HTTP 200 OK y mensaje de confirmación
     * @throws UsuarioNoEncontradoException si no existe un usuario con el ID especificado (HTTP 404)
     * @throws com.easymarket.marketplace.exception.CuentaNoBloqueadaPermanentementeException si la cuenta no está en bloqueo permanente (HTTP 400)
     */
    @PostMapping("/{id}/desbloquear")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> desbloquearUsuario(
            @PathVariable("id") Long usuarioId,
            @AuthenticationPrincipal UsuarioPrincipal principal
    ) {
        Usuario usuarioAfectado = usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> new UsuarioNoEncontradoException("Usuario no encontrado con ID: " + usuarioId));

        List<LoginAttempt> intentos = loginAttemptRepository.findByEmail(usuarioAfectado.getEmail());

        // Filtrar todas las IPs donde la combinación (email, IP) se encuentre en bloqueo permanente (intentos >= 12)
        List<String> ipsBloqueadasPermanentes = intentos.stream()
                .filter(att -> att.getIntentos() >= 12)
                .map(LoginAttempt::getIp)
                .distinct()
                .toList();

        if (ipsBloqueadasPermanentes.isEmpty()) {
            // Si no hay ninguna IP bloqueada permanentemente, invocar con IP por defecto para que DesbloqueoAdminService
            // valide y lance la excepción de dominio CuentaNoBloqueadaPermanentementeException (HTTP 400).
            String ipReferencia = intentos.isEmpty() ? "127.0.0.1" : intentos.get(0).getIp();
            desbloqueoAdminService.desbloquearCuenta(
                    principal.id(),
                    usuarioAfectado.getEmail(),
                    ipReferencia,
                    usuarioId
            );
        } else {
            // Desbloquear todas las combinaciones email + IP que se encuentran en bloqueo permanente
            for (String ip : ipsBloqueadasPermanentes) {
                desbloqueoAdminService.desbloquearCuenta(
                        principal.id(),
                        usuarioAfectado.getEmail(),
                        ip,
                        usuarioId
                );
            }
        }

        return ResponseEntity.ok(Map.of("mensaje", "Cuenta desbloqueada exitosamente"));
    }
}
