package com.easymarket.marketplace.repository;

import com.easymarket.marketplace.model.EstadoPublicacion;
import com.easymarket.marketplace.model.Publicacion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repositorio JPA para la entidad {@link Publicacion}.
 */
@Repository
public interface PublicacionRepository extends JpaRepository<Publicacion, Long> {

    /**
     * Comprueba si existe al menos una publicación asociada a la categoría especificada.
     *
     * @param categoriaId ID de la categoría
     * @return true si existen publicaciones vinculadas, false en caso contrario
     */
    boolean existsByCategoriaId(Long categoriaId);

    /**
     * Comprueba si existe al menos una publicación asociada a la subcategoría especificada.
     *
     * @param subcategoriaId ID de la subcategoría
     * @return true si existen publicaciones vinculadas, false en caso contrario
     */
    boolean existsBySubcategoriaId(Long subcategoriaId);

    /**
     * Obtiene todas las publicaciones pertenecientes a un usuario vendedor específico,
     * ordenadas de la más reciente a la más antigua.
     *
     * @param usuarioId ID del usuario vendedor
     * @return lista de publicaciones de ese usuario ordenadas por fecha de creación descendente
     */
    List<Publicacion> findByUsuarioIdOrderByCreatedAtDescIdDesc(Long usuarioId);

    /**
     * Obtiene todas las publicaciones filtradas por su estado actual.
     *
     * @param estado estado de publicación a filtrar
     * @return lista de publicaciones en ese estado
     */
    List<Publicacion> findByEstado(EstadoPublicacion estado);

    /**
     * Cuenta las publicaciones que se encuentran en el estado indicado.
     *
     * <p>Consulta derivada de lectura usada por {@code GET /admin/tablero} (PHA06TSK07; plan.md,
     * "Tablero administrativo", "conteo de publicaciones pendientes"): el controller la invoca
     * con {@link EstadoPublicacion#PENDIENTE_REVISION}. La agregación es de lectura pura; no
     * modifica el estado ni el catálogo.</p>
     *
     * @param estado estado de publicación a contar
     * @return número de publicaciones en ese estado, incluyendo cero cuando no hay ninguna
     */
    long countByEstado(EstadoPublicacion estado);

    /**
     * Decrementa el stock de una publicación en 1 de forma atómica y condicional, solo si queda
     * al menos una unidad disponible (stock &gt;= 1).
     *
     * <p>Es el mecanismo que materializa la prioridad FIFO por timestamp ante compras
     * concurrentes sobre la última unidad (plan.md, "Flujo de compra y reserva de stock
     * (PHA03)"; spec.md, Story 5): el primer hilo que adquiere el lock de fila de PostgreSQL
     * decrementa y gana; el segundo, al re-evaluar la condición con stock ya en 0, no afecta
     * ninguna fila y es rechazado por falta de stock. Debe ejecutarse dentro de la misma
     * transacción de base de datos que crea la transacción {@code reservada} con su snapshot de
     * precio (constitución, principio 1: atomicidad). El UPDATE nativo usa
     * {@code clearAutomatically} y {@code flushAutomatically} para que el contexto de
     * persistencia se sincronice antes de cualquier lectura posterior dentro de la misma
     * transacción.</p>
     *
     * @param publicacionId ID de la publicación a decrementar
     * @return número de filas afectadas: 1 si el stock era &gt;= 1 (reserva exitosa), 0 si estaba
     *         agotado (perdedor de la carrera)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE publicaciones SET stock = stock - 1 WHERE id = :publicacionId AND stock >= 1", nativeQuery = true)
    int decrementarStockSiDisponible(@Param("publicacionId") Long publicacionId);

    /**
     * Restaura exactamente una unidad de una publicación cuya reserva fue cancelada.
     *
     * @param publicacionId ID de la publicación a la que se devuelve la unidad reservada
     * @return número de filas afectadas
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE publicaciones SET stock = stock + 1 WHERE id = :publicacionId", nativeQuery = true)
    int incrementarStock(@Param("publicacionId") Long publicacionId);
}
