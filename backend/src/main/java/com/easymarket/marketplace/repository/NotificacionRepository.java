package com.easymarket.marketplace.repository;

import com.easymarket.marketplace.model.Notificacion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for persisting in-app notification projections.
 */
@Repository
public interface NotificacionRepository extends JpaRepository<Notificacion, Long> {

    /**
     * Atomically inserts or reactivates the unique publication notification slot. The conflict
     * target mirrors the V20 partial unique index and is deliberately separate from the transaction
     * slot so daily transaction history can never enter this route.
     *
     * @param usuarioId recipient identifier
     * @param publicacionId pending publication identifier
     * @param tipo stable normal notification type
     * @param mensaje literal notification message
     * @param createdAt creation/reactivation timestamp
     * @return number of affected rows, always one for a successful upsert
     */
    @Modifying
    @Query(value = "INSERT INTO notificaciones (usuario_id, publicacion_id, mensaje, tipo, leida, created_at) "
            + "VALUES (:usuarioId, :publicacionId, :mensaje, :tipo, false, :createdAt) "
            + "ON CONFLICT (usuario_id, publicacion_id, tipo) WHERE publicacion_id IS NOT NULL "
            + "DO UPDATE SET mensaje = EXCLUDED.mensaje, leida = false, created_at = EXCLUDED.created_at",
            nativeQuery = true)
    int upsertPublicacion(@Param("usuarioId") Long usuarioId, @Param("publicacionId") Long publicacionId,
                          @Param("tipo") String tipo, @Param("mensaje") String mensaje,
                          @Param("createdAt") java.time.ZonedDateTime createdAt);

    /**
     * Atomically inserts or reactivates the unique normal transaction notification slot. The
     * predicate excludes the two daily historical types, preserving their separate service route.
     *
     * @param usuarioId recipient identifier
     * @param transaccionId transaction identifier
     * @param tipo stable normal notification type
     * @param mensaje literal notification message
     * @param createdAt creation/reactivation timestamp
     * @return number of affected rows, always one for a successful upsert
     */
    @Modifying
    @Query(value = "INSERT INTO notificaciones (usuario_id, transaccion_id, mensaje, tipo, leida, created_at) "
            + "VALUES (:usuarioId, :transaccionId, :mensaje, :tipo, false, :createdAt) "
            + "ON CONFLICT (usuario_id, transaccion_id, tipo) "
            + "WHERE transaccion_id IS NOT NULL "
            + "AND tipo NOT IN ('COMPRA_PENDIENTE_DIARIA', 'VENTA_POR_ENTREGAR_DIARIA') "
            + "DO UPDATE SET mensaje = EXCLUDED.mensaje, leida = false, created_at = EXCLUDED.created_at",
            nativeQuery = true)
    int upsertTransaccion(@Param("usuarioId") Long usuarioId, @Param("transaccionId") Long transaccionId,
                          @Param("tipo") String tipo, @Param("mensaje") String mensaje,
                          @Param("createdAt") java.time.ZonedDateTime createdAt);

    /**
     * Finds the latest notification of one type for one recipient and transaction.
     *
     * <p>The daily notification job invokes this only while retaining the corresponding
     * transaction row lock, so the read and a possible insertion remain serialized per
     * transaction.</p>
     *
     * @param transaccionId transaction association to match
     * @param usuarioId recipient to match
     * @param tipo stable notification type to match
     * @return latest matching notification, or empty when this recipient has not been notified
     */
    Optional<Notificacion> findFirstByTransaccion_IdAndUsuario_IdAndTipoOrderByCreatedAtDesc(
        Long transaccionId, Long usuarioId, String tipo);

    /**
     * Finds the unique notification slot for a recipient, publication and type.
     *
     * @param usuarioId recipient identifier
     * @param publicacionId pending publication identifier
     * @param tipo stable notification type
     * @return existing slot, or empty when it has not been emitted
     */
    Optional<Notificacion> findByUsuario_IdAndPublicacion_IdAndTipo(Long usuarioId, Long publicacionId,
                                                                       String tipo);

    /**
     * Finds the unique notification slot for a recipient, transaction and type.
     *
     * @param usuarioId recipient identifier
     * @param transaccionId transaction identifier
     * @param tipo stable notification type
     * @return existing slot, or empty when it has not been emitted
     */
    Optional<Notificacion> findByUsuario_IdAndTransaccion_IdAndTipo(Long usuarioId, Long transaccionId,
                                                                       String tipo);

    /**
     * Finds all in-app notifications addressed to one recipient, newest first.
     *
     * <p>Filters by {@code usuario.id} (the recipient column {@code usuario_id}); the endpoint
     * {@code GET /notificaciones} (PHA04TSK16, Story 7b) invokes this so that each user only
     * ever receives their own notifications. Entries are ordered by creation instant descending
     * with the persistent identifier as tie-breaker, the same pattern as
     * {@code PublicacionRepository.findByUsuarioIdOrderByCreatedAtDescIdDesc}.</p>
     *
     * @param usuarioId recipient to match
     * @return notifications of that recipient ordered by {@code createdAt} descending and, for
     *         equal instants, by {@code id} descending
     */
    List<Notificacion> findByUsuarioIdOrderByCreatedAtDescIdDesc(Long usuarioId);

    /**
     * Finds the notifications of one recipient whose type is one of the supplied actionable
     * types, newest first (PHA09TSK05).
     *
     * <p>The redesigned notification center groups entries by role: the caller passes exactly
     * the {@code tipo} values that are actionable for the authenticated role and renders the
     * result. Ordering matches {@link #findByUsuarioIdOrderByCreatedAtDescIdDesc(Long)}:
     * {@code createdAt} descending with the persistent identifier as tie-breaker. Read-only
     * projection; it performs no state transition and no ledger write.</p>
     *
     * @param usuarioId recipient to match
     * @param tipos stable notification types considered actionable for the caller's role
     * @return matching notifications of that recipient, newest first
     */
    List<Notificacion> findByUsuarioIdAndTipoInOrderByCreatedAtDescIdDesc(Long usuarioId, List<String> tipos);
}
