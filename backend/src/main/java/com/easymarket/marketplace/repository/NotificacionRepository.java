package com.easymarket.marketplace.repository;

import com.easymarket.marketplace.model.Notificacion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for persisting in-app notification projections.
 */
@Repository
public interface NotificacionRepository extends JpaRepository<Notificacion, Long> {

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
