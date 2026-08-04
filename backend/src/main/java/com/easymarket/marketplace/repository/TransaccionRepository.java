package com.easymarket.marketplace.repository;

import com.easymarket.marketplace.model.Transaccion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repositorio JPA para la entidad {@link Transaccion}.
 *
 * <p>Interfaz base sin métodos adicionales por ahora: el servicio de reserva atómica
 * (PHA03TSK04) solo necesita el {@code save} de {@link JpaRepository}. Los métodos de consulta
 * específicos (historial por comprador/vendedor, conteos de PHA05, etc.) pertenecen a tareas
 * futuras de {@code tasks.md} — no se adelantan aquí.</p>
 */
@Repository
public interface TransaccionRepository extends JpaRepository<Transaccion, Long> {
}
