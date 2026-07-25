package com.easymarket.marketplace.service;

import com.easymarket.marketplace.exception.CuentaBloqueadaException;
import com.easymarket.marketplace.model.LoginAttempt;
import com.easymarket.marketplace.repository.LoginAttemptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias para {@link RateLimitingService}.
 *
 * <p>Verifica los requerimientos de rate limiting por combinación de email + IP
 * definidos en la Story 0b de {@code spec.md}:
 * <ul>
 *   <li>3 fallos consecutivos provocan bloqueo de 5 minutos.</li>
 *   <li>6 fallos escalan a 30 minutos, 9 fallos a 24 horas y 12 fallos a bloqueo permanente.</li>
 *   <li>esBloqueoPermanente() devuelve true únicamente cuando intentos >= 12.</li>
 *   <li>Un login exitoso reinicia el contador de intentos a cero mediante update atómico.</li>
 *   <li>evaluarAcceso() lanza {@link CuentaBloqueadaException} si la combinación está bloqueada.</li>
 * </ul>
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class RateLimitingServiceTests {

    @Mock
    private LoginAttemptRepository loginAttemptRepository;

    private RateLimitingService rateLimitingService;

    private final String email = "usuario@easymarket.com";
    private final String ip = "192.168.1.100";
    private final ZonedDateTime ahora = ZonedDateTime.of(2026, 7, 25, 10, 0, 0, 0, ZoneId.of("America/Lima"));

    @BeforeEach
    void setUp() {
        rateLimitingService = new RateLimitingService(loginAttemptRepository);
    }

    @Test
    @DisplayName("registrarFallo() invoca la consulta upsert atómica con los umbrales de tiempo correctos")
    void registrarFallo_InvocaConsultaAtomicConUmbrales() {
        // Act
        rateLimitingService.registrarFallo(email, ip, ahora);

        // Assert
        verify(loginAttemptRepository).registrarFalloAtomic(
                email,
                ip,
                ahora.plusMinutes(5),
                ahora.plusMinutes(30),
                ahora.plusHours(24),
                RateLimitingService.FECHA_BLOQUEO_PERMANENTE
        );
    }

    @Test
    @DisplayName("registrarExito() invoca la consulta de actualización atómica para reiniciar intentos")
    void registrarExito_InvocaUpdateAtomic() {
        // Act
        rateLimitingService.registrarExito(email, ip);

        // Assert
        verify(loginAttemptRepository).registrarExitoAtomic(email, ip);
    }

    @Test
    @DisplayName("esBloqueoPermanente() retorna true cuando intentos >= 12 y false si intentos < 12")
    void esBloqueoPermanente_RetornaTrueSoloParaBloqueoPermanente() {
        // Arrange: intentos = 12 (bloqueo permanente)
        LoginAttempt attemptPermanente = new LoginAttempt(email, ip, 12, RateLimitingService.FECHA_BLOQUEO_PERMANENTE);
        when(loginAttemptRepository.findByEmailAndIp(email, ip)).thenReturn(Optional.of(attemptPermanente));

        // Act & Assert
        assertThat(rateLimitingService.esBloqueoPermanente(email, ip, ahora)).isTrue();

        // Arrange: intentos = 3 (bloqueo temporal de 5m)
        LoginAttempt attemptTemporal = new LoginAttempt(email, ip, 3, ahora.plusMinutes(5));
        when(loginAttemptRepository.findByEmailAndIp(email, ip)).thenReturn(Optional.of(attemptTemporal));

        // Act & Assert
        assertThat(rateLimitingService.esBloqueoPermanente(email, ip, ahora)).isFalse();
    }

    @Test
    @DisplayName("evaluarAcceso() lanza CuentaBloqueadaException cuando la combinación está bloqueada")
    void evaluarAcceso_LanzaCuentaBloqueadaException_SiEstaBloqueado() {
        // Arrange: bloqueado por 5 minutos hasta las 10:05
        LoginAttempt attemptBloqueado = new LoginAttempt(email, ip, 3, ahora.plusMinutes(5));
        when(loginAttemptRepository.findByEmailAndIp(email, ip)).thenReturn(Optional.of(attemptBloqueado));

        // Act & Assert
        assertThatThrownBy(() -> rateLimitingService.evaluarAcceso(email, ip, ahora))
                .isInstanceOf(CuentaBloqueadaException.class)
                .hasMessageContaining("bloqueada");
    }
}
