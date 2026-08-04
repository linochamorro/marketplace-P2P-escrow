package com.easymarket.marketplace.service;

import com.easymarket.marketplace.exception.CuentaBloqueadaException;
import com.easymarket.marketplace.model.LoginAttempt;
import com.easymarket.marketplace.repository.LoginAttemptRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * Servicio de dominio responsable de la lógica de rate limiting por combinación de email + IP.
 *
 * <p><strong>Contrato de orden de invocación para componentes integradores (p. ej. controller en PHA01TSK07):</strong>
 * <ul>
 *   <li>El componente que maneja la autenticación debe invocar obligatoriamente {@link #evaluarAcceso(String, String)}
 *       <strong>ANTES</strong> de intentar verificar las credenciales del usuario.</li>
 *   <li>Si la combinación email + IP está actualmente bloqueada, {@code evaluarAcceso()} lanzará {@link CuentaBloqueadaException},
 *       impidiendo que se procesen las credenciales y garantizando que no se llame a {@link #registrarFallo(String, String)}.</li>
 *   <li>Únicamente si la verificación de credenciales falla (tras haber superado {@code evaluarAcceso()}), el integrador
 *       debe invocar {@link #registrarFallo(String, String)}.</li>
 *   <li>Si la autenticación es exitosa, el integrador debe invocar {@link #registrarExito(String, String)}.</li>
 * </ul>
 * </p>
 *
 * <p>Aplica las reglas de escalado de bloqueo definidas en la Story 0b de {@code spec.md}:
 * <ul>
 *   <li>3 fallos consecutivos: bloqueo de 5 minutos.</li>
 *   <li>6 fallos consecutivos: bloqueo de 30 minutos.</li>
 *   <li>9 fallos consecutivos: bloqueo de 24 horas.</li>
 *   <li>12 fallos consecutivos: bloqueo permanente (requiere intervención admin, ver PHA01TSK05).</li>
 *   <li>Un login exitoso antes del bloqueo permanente reinicia el contador de intentos a cero.</li>
 * </ul>
 * </p>
 */
@Service
public class RateLimitingService {

    public static final ZoneId ZONA_PERU = ZoneId.of("America/Lima");
    public static final ZonedDateTime FECHA_BLOQUEO_PERMANENTE = ZonedDateTime.of(9999, 12, 31, 23, 59, 59, 0, ZONA_PERU);

    private final LoginAttemptRepository loginAttemptRepository;

    /**
     * Constructor con inyección del repositorio de intentos de login.
     *
     * @param loginAttemptRepository repositorio JPA de {@code login_attempts}
     */
    public RateLimitingService(LoginAttemptRepository loginAttemptRepository) {
        this.loginAttemptRepository = loginAttemptRepository;
    }

    /**
     * Evalúa si una combinación de email e IP se encuentra actualmente bloqueada en el instante actual.
     *
     * @param email correo electrónico
     * @param ip dirección IP de origen
     * @return {@code true} si el acceso está bloqueado, {@code false} en caso contrario
     */
    @Transactional(readOnly = true)
    public boolean estaBloqueado(String email, String ip) {
        return estaBloqueado(email, ip, ZonedDateTime.now(ZONA_PERU));
    }

    /**
     * Evalúa si una combinación de email e IP se encuentra bloqueada respecto a una fecha/hora de referencia.
     *
     * @param email correo electrónico
     * @param ip dirección IP de origen
     * @param ahora fecha y hora de evaluación
     * @return {@code true} si la combinación está bloqueada, {@code false} en caso contrario
     */
    @Transactional(readOnly = true)
    public boolean estaBloqueado(String email, String ip, ZonedDateTime ahora) {
        Optional<LoginAttempt> attemptOpt = loginAttemptRepository.findByEmailAndIp(email, ip);

        if (attemptOpt.isEmpty()) {
            return false;
        }

        LoginAttempt attempt = attemptOpt.get();
        return attempt.getBloqueadoHasta() != null && ahora.isBefore(attempt.getBloqueadoHasta());
    }

    /**
     * Evalúa si la combinación email + IP se encuentra en estado de bloqueo permanente (12 o más fallos acumulados).
     *
     * <p>Permite a servicios externos (como el servicio de desbloqueo admin en PHA01TSK05) determinar
     * si una combinación de credenciales se encuentra en bloqueo permanente sin depender de valores mágicos.</p>
     *
     * @param email correo electrónico
     * @param ip dirección IP de origen
     * @return {@code true} si los intentos acumulados son mayores o iguales a 12 (bloqueo permanente)
     */
    @Transactional(readOnly = true)
    public boolean esBloqueoPermanente(String email, String ip) {
        return esBloqueoPermanente(email, ip, ZonedDateTime.now(ZONA_PERU));
    }

    /**
     * Evalúa si la combinación email + IP se encuentra en bloqueo permanente respecto a una fecha/hora de referencia.
     *
     * @param email correo electrónico
     * @param ip dirección IP de origen
     * @param ahora fecha y hora de evaluación
     * @return {@code true} si la combinación está en bloqueo permanente
     */
    @Transactional(readOnly = true)
    public boolean esBloqueoPermanente(String email, String ip, ZonedDateTime ahora) {
        Optional<LoginAttempt> attemptOpt = loginAttemptRepository.findByEmailAndIp(email, ip);
        return attemptOpt.isPresent() && attemptOpt.get().getIntentos() >= 12;
    }

    /**
     * Evalúa el acceso de una combinación email + IP y lanza {@link CuentaBloqueadaException} si está bloqueada.
     *
     * @param email correo electrónico
     * @param ip dirección IP de origen
     * @throws CuentaBloqueadaException si la combinación está actualmente bloqueada
     */
    @Transactional(readOnly = true)
    public void evaluarAcceso(String email, String ip) {
        evaluarAcceso(email, ip, ZonedDateTime.now(ZONA_PERU));
    }

    /**
     * Evalúa el acceso de una combinación email + IP para un instante de tiempo específico.
     *
     * @param email correo electrónico
     * @param ip dirección IP de origen
     * @param ahora fecha y hora de evaluación
     * @throws CuentaBloqueadaException si la combinación está actualmente bloqueada
     */
    @Transactional(readOnly = true)
    public void evaluarAcceso(String email, String ip, ZonedDateTime ahora) {
        if (estaBloqueado(email, ip, ahora)) {
            throw new CuentaBloqueadaException("Cuenta o IP temporalmente bloqueada por demasiados intentos fallidos");
        }
    }

    /**
     * Registra un fallo de inicio de sesión para la combinación email + IP utilizando la hora actual.
     *
     * @param email correo electrónico
     * @param ip dirección IP de origen
     */
    @Transactional
    public void registrarFallo(String email, String ip) {
        registrarFallo(email, ip, ZonedDateTime.now(ZONA_PERU));
    }

    /**
     * Registra un fallo de inicio de sesión para la combinación email + IP de forma atómica (upsert),
     * incrementando el contador y aplicando el tiempo de bloqueo correspondiente (5m, 30m, 24h, permanente).
     *
     * @param email correo electrónico
     * @param ip dirección IP de origen
     * @param ahora fecha y hora actual de evaluación
     */
    @Transactional
    public void registrarFallo(String email, String ip, ZonedDateTime ahora) {
        loginAttemptRepository.registrarFalloAtomic(
                email,
                ip,
                ahora.plusMinutes(5),
                ahora.plusMinutes(30),
                ahora.plusHours(24),
                FECHA_BLOQUEO_PERMANENTE
        );
    }

    /**
     * Registra un inicio de sesión exitoso para la combinación email + IP de forma atómica.
     * Si no está en bloqueo permanente, reinicia el contador de intentos a cero y remueve el bloqueo.
     *
     * @param email correo electrónico
     * @param ip dirección IP de origen
     */
    @Transactional
    public void registrarExito(String email, String ip) {
        loginAttemptRepository.registrarExitoAtomic(email, ip);
    }

    /**
     * Resetea incondicionalmente el contador de intentos a cero y remueve la marca de bloqueo
     * para una combinación email + IP de forma atómica, inclusive si la cuenta está en bloqueo permanente.
     *
     * <p>Este método es utilizado por los servicios administrativos de desbloqueo (PHA01TSK05).</p>
     *
     * @param email correo electrónico
     * @param ip dirección IP de origen
     */
    @Transactional
    public void desbloquearPermanente(String email, String ip) {
        loginAttemptRepository.desbloquearPermanenteAtomic(email, ip);
    }
}
