package com.easymarket.marketplace.repository;

import com.easymarket.marketplace.model.PublicacionMotivoHistorico;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repositorio JPA de inserción para el histórico append-only de motivos de moderación.
 *
 * <p>V17 aplica en PostgreSQL la inmutabilidad de las filas; el servicio de dominio solo usa
 * {@link #save(Object)} para insertar el motivo junto con el cambio de estado de la moderación,
 * dentro de la misma transacción atómica (constitución, principios 1 y 2). No expone consultas
 * de lectura: la consulta del histórico queda para tareas futuras sin asignar en PHA06.</p>
 */
@Repository
public interface PublicacionMotivoHistoricoRepository extends JpaRepository<PublicacionMotivoHistorico, Long> {
}
