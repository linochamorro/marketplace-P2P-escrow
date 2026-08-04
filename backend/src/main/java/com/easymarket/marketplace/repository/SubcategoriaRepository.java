package com.easymarket.marketplace.repository;

import com.easymarket.marketplace.model.Subcategoria;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repositorio JPA para la entidad {@link Subcategoria}.
 */
@Repository
public interface SubcategoriaRepository extends JpaRepository<Subcategoria, Long> {

    /**
     * Comprueba si existe una subcategoría con el nombre indicado bajo una misma categoría padre.
     *
     * @param categoriaId ID de la categoría padre
     * @param nombre nombre de la subcategoría
     * @return true si ya existe una subcategoría con dicho nombre bajo esa categoría padre, false en caso contrario
     */
    boolean existsByCategoriaIdAndNombre(Long categoriaId, String nombre);

    /**
     * Comprueba si existe al menos una subcategoría perteneciente a la categoría padre dada.
     *
     * @param categoriaId ID de la categoría padre
     * @return true si existe al menos una subcategoría asociada, false en caso contrario
     */
    boolean existsByCategoriaId(Long categoriaId);

    /**
     * Obtiene todas las subcategorías pertenecientes a una categoría raíz dada.
     *
     * @param categoriaId ID de la categoría padre
     * @return lista de subcategorías pertenecientes a esa categoría
     */
    List<Subcategoria> findByCategoriaId(Long categoriaId);

    /**
     * Obtiene todas las subcategorías del sistema ordenadas alfabéticamente por su nombre de forma ascendente.
     *
     * @return lista de subcategorías ordenadas
     */
    List<Subcategoria> findAllByOrderByNombreAsc();
}

