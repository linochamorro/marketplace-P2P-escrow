package com.easymarket.marketplace.repository;

import com.easymarket.marketplace.model.AdminAccion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repositorio Spring Data JPA para la entidad {@link AdminAccion}.
 *
 * <p>Proporciona operaciones de persistencia para el registro de auditoría append-only
 * de acciones ejecutadas por administradores.</p>
 */
@Repository
public interface AdminAccionRepository extends JpaRepository<AdminAccion, Long> {
}
