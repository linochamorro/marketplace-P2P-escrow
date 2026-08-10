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
}
