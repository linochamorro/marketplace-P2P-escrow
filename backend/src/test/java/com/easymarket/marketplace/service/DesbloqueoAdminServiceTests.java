package com.easymarket.marketplace.service;

import com.easymarket.marketplace.exception.CuentaNoBloqueadaPermanentementeException;
import com.easymarket.marketplace.model.AdminAccion;
import com.easymarket.marketplace.repository.AdminAccionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;

/**
 * Pruebas unitarias para {@link DesbloqueoAdminService}.
 *
 * <p>Verifica el cumplimiento de los criterios de la Story 0c (spec.md):
 * <ul>
 *   <li>Desbloqueo exitoso solo si la cuenta está en bloqueo permanente.</li>
 *   <li>Reinicio incondicional de los intentos a tramo inicial tras el desbloqueo.</li>
 *   <li>Registro de auditoría append-only en {@code admin_acciones} con el admin responsable.</li>
 *   <li>Rechazo estricto si la cuenta no está en bloqueo permanente (sin reiniciar ni auditar).</li>
 * </ul>
 * </p>
 */
class DesbloqueoAdminServiceTests {

    private RateLimitingService rateLimitingService;
    private AdminAccionRepository adminAccionRepository;
    private DesbloqueoAdminService desbloqueoAdminService;

    @BeforeEach
    void setUp() {
        rateLimitingService = mock(RateLimitingService.class);
        adminAccionRepository = mock(AdminAccionRepository.class);
        desbloqueoAdminService = new DesbloqueoAdminService(rateLimitingService, adminAccionRepository);
    }

    @Test
    @DisplayName("Desbloqueo administrativo reinicia contador a tramo inicial y registra entrada en admin_acciones cuando la cuenta está en bloqueo permanente")
    void desbloquearCuenta_cuandoEstaEnBloqueoPermanente_reseteaContadorYRegistraAuditoria() {
        Long adminId = 1L;
        String email = "bloqueado@ejemplo.com";
        String ip = "192.168.1.100";
        Long usuarioAfectadoId = 42L;

        when(rateLimitingService.esBloqueoPermanente(email, ip)).thenReturn(true);

        desbloqueoAdminService.desbloquearCuenta(adminId, email, ip, usuarioAfectadoId);

        verify(rateLimitingService).desbloquearPermanente(email, ip);

        ArgumentCaptor<AdminAccion> captor = ArgumentCaptor.forClass(AdminAccion.class);
        verify(adminAccionRepository).save(captor.capture());

        AdminAccion accionGuardada = captor.getValue();
        assertNotNull(accionGuardada);
        assertEquals(adminId, accionGuardada.getAdminId());
        assertEquals("DESBLOQUEO_CUENTA", accionGuardada.getAccion());
        assertEquals(usuarioAfectadoId, accionGuardada.getUsuarioAfectadoId());
        assertNotNull(accionGuardada.getDetalle());
        assertTrue(accionGuardada.getDetalle().contains(email));
        assertTrue(accionGuardada.getDetalle().contains(ip));
    }

    @Test
    @DisplayName("Desbloqueo administrativo rechaza la operación si el usuario no está en bloqueo permanente")
    void desbloquearCuenta_cuandoNoEstaEnBloqueoPermanente_lanzaExcepcionYNoReseteaNiRegistraAuditoria() {
        Long adminId = 1L;
        String email = "normal@ejemplo.com";
        String ip = "192.168.1.101";

        when(rateLimitingService.esBloqueoPermanente(email, ip)).thenReturn(false);

        CuentaNoBloqueadaPermanentementeException ex = assertThrows(
                CuentaNoBloqueadaPermanentementeException.class,
                () -> desbloqueoAdminService.desbloquearCuenta(adminId, email, ip)
        );

        assertTrue(ex.getMessage().contains("bloqueo permanente"));
        verify(rateLimitingService, never()).desbloquearPermanente(any(), any());
        verify(adminAccionRepository, never()).save(any());
    }
}
