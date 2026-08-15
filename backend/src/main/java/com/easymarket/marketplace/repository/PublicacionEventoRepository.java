package com.easymarket.marketplace.repository;

import com.easymarket.marketplace.model.PublicacionEvento;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repositorio JPA de inserción para el log canónico de eventos de publicación.
 *
 * <p>V15 aplica en PostgreSQL la inmutabilidad de las filas; el servicio de dominio solo usa
 * {@link #save(Object)} para insertar el evento CREADA junto con la publicación.</p>
 */
@Repository
public interface PublicacionEventoRepository extends JpaRepository<PublicacionEvento, Long> {
}
