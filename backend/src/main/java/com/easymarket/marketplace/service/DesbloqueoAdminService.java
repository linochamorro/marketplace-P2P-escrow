package com.easymarket.marketplace.service;

import com.easymarket.marketplace.exception.CuentaNoBloqueadaPermanentementeException;
import com.easymarket.marketplace.model.AdminAccion;
import com.easymarket.marketplace.repository.AdminAccionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Servicio de dominio responsable de gestionar el desbloqueo administrativo de cuentas
 * en estado de bloqueo permanente (Story 0c, spec.md).
 */
@Service
public class DesbloqueoAdminService {

    private final RateLimitingService rateLimitingService;
    private final AdminAccionRepository adminAccionRepository;

    /**
     * Constructor con inyección de dependencias de dominio.
     *
     * @param rateLimitingService servicio de rate limiting para verificar y reiniciar el estado de bloqueo
     * @param adminAccionRepository repositorio para registrar las acciones de auditoría administrativa
     */
    public DesbloqueoAdminService(RateLimitingService rateLimitingService, AdminAccionRepository adminAccionRepository) {
        this.rateLimitingService = rateLimitingService;
        this.adminAccionRepository = adminAccionRepository;
    }

    /**
     * Desbloquea una combinación email + IP que se encuentra en bloqueo permanente, registrando
     * la correspondiente entrada de auditoría append-only con el admin responsable.
     *
     * <p>Ambas operaciones (reseteo del contador y registro en auditoría) ocurren en la misma
     * transacción atómica de base de datos (constitution.md, principio 1).</p>
     *
     * @param adminId ID del administrador que autoriza y ejecuta el desbloqueo
     * @param email correo electrónico de la cuenta afectada
     * @param ip dirección IP afectada
     * @param usuarioAfectadoId ID del usuario afectado (opcional/nullable)
     * @throws CuentaNoBloqueadaPermanentementeException si la combinación email + IP no está en bloqueo permanente
     */
    @Transactional
    public void desbloquearCuenta(Long adminId, String email, String ip, Long usuarioAfectadoId) {
        if (!rateLimitingService.esBloqueoPermanente(email, ip)) {
            throw new CuentaNoBloqueadaPermanentementeException(
                "La combinación email/IP " + email + " / " + ip + " no se encuentra en estado de bloqueo permanente"
            );
        }

        rateLimitingService.desbloquearPermanente(email, ip);

        String detalle = String.format("Desbloqueo permanente de cuenta para email: %s, IP: %s", email, ip);
        AdminAccion accion = new AdminAccion(
                adminId,
                "DESBLOQUEO_CUENTA",
                usuarioAfectadoId,
                detalle
        );
        adminAccionRepository.save(accion);
    }

    /**
     * Sobrecarga de conveniencia cuando no se especifica el ID del usuario afectado.
     *
     * @param adminId ID del administrador
     * @param email correo electrónico
     * @param ip dirección IP
     */
    public void desbloquearCuenta(Long adminId, String email, String ip) {
        desbloquearCuenta(adminId, email, ip, null);
    }
}
