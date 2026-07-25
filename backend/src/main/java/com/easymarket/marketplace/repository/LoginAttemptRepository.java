package com.easymarket.marketplace.repository;

import com.easymarket.marketplace.model.LoginAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * Repositorio Spring Data JPA para la entidad {@link LoginAttempt}.
 */
@Repository
public interface LoginAttemptRepository extends JpaRepository<LoginAttempt, Long> {

    /**
     * Busca un registro de intentos de inicio de sesión por la combinación email y dirección IP.
     *
     * @param email correo electrónico del usuario
     * @param ip dirección IP de origen
     * @return un {@link Optional} con el registro de intentos si fue encontrado, o vacío en caso contrario
     */
    Optional<LoginAttempt> findByEmailAndIp(String email, String ip);

    /**
     * Realiza un upsert atómico para registrar un fallo de inicio de sesión.
     * Inserta un nuevo registro con intentos = 1 si no existe, o incrementa el contador
     * de intentos y actualiza la marca de bloqueo según los umbrales configurados.
     *
     * @param email correo electrónico
     * @param ip dirección IP de origen
     * @param bloqueo5m fecha y hora para el bloqueo de 5 minutos (3er fallo)
     * @param bloqueo30m fecha y hora para el bloqueo de 30 minutos (6to fallo)
     * @param bloqueo24h fecha y hora para el bloqueo de 24 horas (9no fallo)
     * @param bloqueoPermanente fecha y hora para el bloqueo permanente (12º fallo o superior)
     * @return número de filas afectadas
     */
    @Modifying
    @Query(value = """
        INSERT INTO login_attempts (email, ip, intentos, bloqueado_hasta)
        VALUES (:email, :ip, 1, NULL)
        ON CONFLICT (email, ip)
        DO UPDATE SET
            intentos = login_attempts.intentos + 1,
            bloqueado_hasta = CASE
                WHEN login_attempts.intentos + 1 = 3 THEN :bloqueo5m
                WHEN login_attempts.intentos + 1 = 6 THEN :bloqueo30m
                WHEN login_attempts.intentos + 1 = 9 THEN :bloqueo24h
                WHEN login_attempts.intentos + 1 >= 12 THEN :bloqueoPermanente
                ELSE login_attempts.bloqueado_hasta
            END
        """, nativeQuery = true)
    int registrarFalloAtomic(
        @Param("email") String email,
        @Param("ip") String ip,
        @Param("bloqueo5m") ZonedDateTime bloqueo5m,
        @Param("bloqueo30m") ZonedDateTime bloqueo30m,
        @Param("bloqueo24h") ZonedDateTime bloqueo24h,
        @Param("bloqueoPermanente") ZonedDateTime bloqueoPermanente
    );

    /**
     * Reinicia el contador de intentos y remueve el bloqueo para una combinación email + IP
     * de forma atómica, siempre que la cuenta no esté en estado de bloqueo permanente (intentos < 12).
     *
     * @param email correo electrónico
     * @param ip dirección IP de origen
     * @return número de filas modificadas
     */
    @Modifying
    @Query("UPDATE LoginAttempt la SET la.intentos = 0, la.bloqueadoHasta = null WHERE la.email = :email AND la.ip = :ip AND la.intentos < 12")
    int registrarExitoAtomic(@Param("email") String email, @Param("ip") String ip);
}
