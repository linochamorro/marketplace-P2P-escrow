package com.easymarket.marketplace.repository;

import com.easymarket.marketplace.model.EstadoTransaccion;
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
 * Incluye el conteo de ventas completadas para PHA05TSK01; las consultas de historial pertenecen
 * a tareas futuras de {@code tasks.md}.</p>
 */
@Repository
public interface TransaccionRepository extends JpaRepository<Transaccion, Long> {

    /**
     * Indica si existe al menos una transacción asociada a la publicación indicada.
     *
     * <p>Consulta derivada de Spring Data para PHA12TSK01 (decisión de Lino 2026-08-23,
     * plan.md "PHA12 — Eliminación de publicaciones con transacciones asociadas"):
     * materializa {@code select exists(...)} sobre {@code transacciones.publicacion_id}.
     * Es de solo lectura; la consume {@code PublicacionService.eliminarPublicacion} para
     * rechazar con {@code PublicacionConTransaccionesException} antes del {@code delete},
     * evitando la violación de la FK {@code fk_transacciones_publicacion} (V7) en el commit.</p>
     *
     * @param publicacionId identificador de la publicación cuya existencia de transacciones se consulta
     * @return {@code true} si la publicación tiene al menos una transacción asociada, {@code false} en caso contrario
     */
    boolean existsByPublicacionId(Long publicacionId);

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

    /**
     * Counts the transactions of one publication that Story 11 defines as completed sales.
     *
     * <p>The query deliberately includes exactly {@link EstadoTransaccion#COMPLETADA},
     * {@link EstadoTransaccion#RECIBIDO}, and {@link EstadoTransaccion#RECIBIDO_SIN_RESPUESTA}.
     * It is read-only and performs neither state transitions nor modifications to funds or stock.
     * Ordering publications by this value belongs to PHA05TSK02.</p>
     *
     * @param publicacionId identifier of the publication whose completed-sale transactions are counted
     * @return number of matching transactions, including zero when the publication has none
     */
    @Query("select count(t) from Transaccion t where t.publicacion.id = :publicacionId "
        + "and t.estado in (com.easymarket.marketplace.model.EstadoTransaccion.COMPLETADA, "
        + "com.easymarket.marketplace.model.EstadoTransaccion.RECIBIDO, "
        + "com.easymarket.marketplace.model.EstadoTransaccion.RECIBIDO_SIN_RESPUESTA)")
    long contarTransaccionesCompletadasPorPublicacion(@Param("publicacionId") Long publicacionId);

    /**
     * Lists every transaction whose buyer is the supplied user, most recent reservation first.
     *
     * <p>Read-only query for {@code GET /transacciones/compras} (PHA06TSK06; plan.md, "Lectura de
     * transacciones"): the buyer of a transaction is {@code comprador.id}. It is a pure projection
     * that performs no state transition, no fund modification and no ledger write. The descending
     * {@code fecha_reservada} order is declared as the list contract for the future purchases UI
     * (PHA06TSK12) because plan.md does not define one. The caller must keep a read-only
     * transaction open to map the lazy {@code publicacion} association of each result.</p>
     *
     * @param compradorId identifier of the buyer whose purchases are listed
     * @return the buyer's transactions, or an empty list when the user has none
     */
    @Query("select t from Transaccion t where t.comprador.id = :compradorId "
        + "order by t.fechaReservada desc")
    List<Transaccion> findByCompradorIdOrderByFechaReservadaDesc(@Param("compradorId") Long compradorId);

    /**
     * Lists every transaction whose publication belongs to the supplied seller, most recent
     * reservation first.
     *
     * <p>Read-only query for {@code GET /transacciones/ventas} (PHA06TSK06; plan.md, "Lectura de
     * transacciones"): the seller of a transaction is the owner of its publication
     * ({@code publicacion.usuario.id}). It is a pure projection that performs no state transition,
     * no fund modification and no ledger write. The descending {@code fecha_reservada} order is
     * declared as the list contract for the future sales UI (PHA06TSK12) because plan.md does not
     * define one. The caller must keep a read-only transaction open to map the lazy
     * {@code publicacion} association of each result.</p>
     *
     * @param vendedorId identifier of the publication owner whose sales are listed
     * @return the seller's transactions, or an empty list when the user has none
     */
    @Query("select t from Transaccion t where t.publicacion.usuario.id = :vendedorId "
        + "order by t.fechaReservada desc")
    List<Transaccion> findByPublicacionUsuarioIdOrderByFechaReservadaDesc(@Param("vendedorId") Long vendedorId);

    /**
     * Lists every transaction in {@link EstadoTransaccion#DISPUTA}, most recent reservation
     * first.
     *
     * <p>Read-only query for {@code GET /admin/disputas} (PHA06TSK07; plan.md, "Lecturas
     * administrativas"): an open dispute IS a transaction in state {@code disputa}. The
     * descending {@code fecha_reservada} order is declared as the list contract for the future
     * administrative disputes UI (PHA06TSK13) because plan.md does not define one — consistent
     * with the same contract declared for {@code GET /transacciones/compras} and
     * {@code /ventas} in PHA06TSK06. The caller must keep a read-only transaction open to map
     * the lazy {@code publicacion} association of each result.</p>
     *
     * @return every transaction in dispute, most recent reservation first
     */
    @Query("select t from Transaccion t where t.estado = com.easymarket.marketplace.model.EstadoTransaccion.DISPUTA "
        + "order by t.fechaReservada desc")
    List<Transaccion> findByEstadoDisputaOrderByFechaReservadaDesc();

    /**
     * Counts every transaction currently in {@link EstadoTransaccion#DISPUTA}.
     *
     * <p>Read-only aggregate for {@code GET /admin/tablero} (PHA06TSK07; plan.md, "Tablero
     * administrativo", "disputas abiertas"). It performs no state transition and no ledger
     * write.</p>
     *
     * @return number of transactions in dispute, including zero when there are none
     */
    @Query("select count(t) from Transaccion t where t.estado = com.easymarket.marketplace.model.EstadoTransaccion.DISPUTA")
    long contarDisputasAbiertas();

    /**
     * Sums the {@code precio_snapshot} of every transaction in the four escrow states that
     * plan.md defines as retained volume.
     *
     * <p>Read-only aggregate for {@code GET /admin/tablero} (PHA06TSK07; plan.md, "Tablero
     * administrativo", {@code volumenEscrowCentavos}): exactly {@link EstadoTransaccion#RESERVADA},
     * {@link EstadoTransaccion#ENVIADO}, {@link EstadoTransaccion#ENTREGADO} and
     * {@link EstadoTransaccion#DISPUTA}. A transaction in {@code cancelada} or in a final state
     * does NOT contribute. {@code COALESCE} turns an empty sum into {@code 0} so the DTO never
     * receives {@code null} money (constitution, principle 3: integer cents, never null). The
     * aggregation is read-only; the ledger is neither recalculated nor modified.</p>
     *
     * @return total retained escrow volume in integer cents, zero when no transaction matches
     */
    @Query("select coalesce(sum(t.precioSnapshot), 0L) from Transaccion t where t.estado in "
        + "(com.easymarket.marketplace.model.EstadoTransaccion.RESERVADA, "
        + "com.easymarket.marketplace.model.EstadoTransaccion.ENVIADO, "
        + "com.easymarket.marketplace.model.EstadoTransaccion.ENTREGADO, "
        + "com.easymarket.marketplace.model.EstadoTransaccion.DISPUTA)")
    long sumarVolumenEscrowCentavos();

    /**
     * Counts every transaction in one of the three final states that plan.md defines as finished.
     *
     * <p>Read-only aggregate for {@code GET /admin/tablero} (PHA06TSK07; plan.md, "Tablero
     * administrativo", {@code transaccionesFinalizadas}): exactly
     * {@link EstadoTransaccion#RECIBIDO}, {@link EstadoTransaccion#RECIBIDO_SIN_RESPUESTA} and
     * {@link EstadoTransaccion#COMPLETADA}. A transaction in {@code cancelada} does NOT count.
     * The aggregation is read-only; it performs no state transition and no ledger write.</p>
     *
     * @return number of finished transactions, including zero when there are none
     */
    @Query("select count(t) from Transaccion t where t.estado in "
        + "(com.easymarket.marketplace.model.EstadoTransaccion.RECIBIDO, "
        + "com.easymarket.marketplace.model.EstadoTransaccion.RECIBIDO_SIN_RESPUESTA, "
        + "com.easymarket.marketplace.model.EstadoTransaccion.COMPLETADA)")
    long contarTransaccionesFinalizadas();
}
