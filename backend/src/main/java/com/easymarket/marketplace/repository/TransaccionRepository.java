package com.easymarket.marketplace.repository;

import com.easymarket.marketplace.model.Transaccion;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repositorio JPA para la entidad {@link Transaccion}.
 *
 * <p>Incluye el bloqueo pesimista requerido para confirmar recepción: una segunda confirmación
 * concurrente espera la primera y luego valida el estado ya actualizado, evitando doble crédito.
 * Las consultas de historial y conteos pertenecen a tareas futuras de {@code tasks.md}.</p>
 */
@Repository
public interface TransaccionRepository extends JpaRepository<Transaccion, Long> {

    /**
     * Busca una transacción y obtiene un bloqueo de escritura hasta finalizar la transacción actual.
     *
     * <p>Debe invocarse desde un límite {@code @Transactional}. El bloqueo materializa
     * {@code SELECT ... FOR UPDATE}, requerido por plan.md para validar {@code entregado} antes de
     * liberar fondos sin permitir dos confirmaciones exitosas sobre la misma fila.</p>
     *
     * @param id identificador de la transacción a bloquear
     * @return transacción bloqueada, o vacío si no existe
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Transaccion t where t.id = :id")
    Optional<Transaccion> findByIdForUpdate(Long id);

    /**
     * Selects delivered transactions whose confirmation deadline has strictly elapsed and locks
     * them without waiting for rows locked by another execution.
     *
     * <p>The native PostgreSQL query deliberately materializes {@code FOR UPDATE SKIP LOCKED}, as
     * mandated by plan.md for overlapping timer executions. The strict {@code <} comparison makes
     * a transaction eligible only after 48 complete hours; a timestamp exactly 48 hours old is not
     * selected. The caller must retain an active transaction until it has persisted every effect.</p>
     *
     * @param limiteExclusivo instant strictly later than each eligible {@code fecha_entregado}
     * @return delivered transactions locked by the current database transaction
     */
    @Query(value = "select * from transacciones "
        + "where estado = 'entregado' and fecha_entregado < :limiteExclusivo "
        + "for update skip locked", nativeQuery = true)
    List<Transaccion> findEntregadasVencidasForUpdateSkipLocked(@Param("limiteExclusivo") java.time.ZonedDateTime limiteExclusivo);

    /**
     * Selects reserved transactions whose shipment deadline has strictly elapsed and locks them
     * without waiting for rows locked by another job execution.
     *
     * <p>The native PostgreSQL query materializes {@code FOR UPDATE SKIP LOCKED}, required by
     * plan.md for overlapping timer executions. The caller must retain its transaction until both
     * the in-app notification and the persistent idempotency marker have been stored.</p>
     *
     * @param limiteExclusivo instant strictly later than each eligible {@code fecha_reservada}
     * @return reserved transactions locked by the current database transaction
     */
    @Query(value = "select * from transacciones "
        + "where estado = 'reservada' and fecha_reservada < :limiteExclusivo "
        + "for update skip locked", nativeQuery = true)
    List<Transaccion> findReservadasVencidasForUpdateSkipLocked(@Param("limiteExclusivo") java.time.ZonedDateTime limiteExclusivo);

    /**
     * Selects every transaction in one of Story 7b's exclusively defined open states and locks
     * each row without waiting for rows retained by another job execution.
     *
     * <p>The native PostgreSQL query deliberately materializes {@code FOR UPDATE SKIP LOCKED}.
     * The caller retains each transaction lock while checking and inserting the latest daily
     * notice of both recipients, preventing concurrent job runs from duplicating an eligible
     * notice.</p>
     *
     * @return open transactions locked by the current database transaction
     */
    @Query(value = "select * from transacciones "
        + "where estado in ('reservada', 'enviado', 'entregado', 'disputa') "
        + "for update skip locked", nativeQuery = true)
    List<Transaccion> findAbiertasForUpdateSkipLocked();
}
