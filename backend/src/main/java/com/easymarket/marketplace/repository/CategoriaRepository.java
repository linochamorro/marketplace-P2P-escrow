package com.easymarket.marketplace.repository;

import com.easymarket.marketplace.model.Categoria;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repositorio JPA para la entidad {@link Categoria}.
 */
@Repository
public interface CategoriaRepository extends JpaRepository<Categoria, Long> {

    /**
     * Comprueba si existe una categoría raíz con el nombre especificado.
     *
     * @param nombre nombre a comprobar
     * @return true si ya existe una categoría con ese nombre, false en caso contrario
     */
    boolean existsByNombre(String nombre);

    /**
     * Busca una categoría raíz por su nombre exacto.
     *
     * @param nombre nombre de la categoría
     * @return un {@link Optional} conteniendo la categoría si existe
     */
    Optional<Categoria> findByNombre(String nombre);

    /**
     * Obtiene todas las categorías raíz ordenadas alfabéticamente por su nombre de forma ascendente.
     *
     * @return lista ordenada de categorías raíz
     */
    java.util.List<Categoria> findAllByOrderByNombreAsc();
}
