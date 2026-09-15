package com.easymarket.marketplace.repository;

import com.easymarket.marketplace.model.EstadoPublicacion;
import com.easymarket.marketplace.model.Publicacion;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

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
     * Carga una publicación adquiriendo un lock pesimista de escritura ({@code PESSIMISTIC_WRITE},
     * materializado por Hibernate como {@code SELECT ... FOR UPDATE} en PostgreSQL) sobre su fila.
     *
     * <p>Método creado para PHA12TSK07 (decisión de Lino 2026-08-23, plan.md "PHA12", fila
     * "Endurecimiento TOCTOU compra-vs-delete"; riesgo declarado en
     * {@code docs/avance/PHA12TSK01-L01-programmer.md}, sección Riesgos, punto 1): cierra la ventana
     * TOCTOU entre {@code TransaccionRepository.existsByPublicacionId} y {@code JpaRepository.delete}
     * dentro de {@code PublicacionService.eliminarPublicacion}. Al ser la PRIMERA operación del flujo,
     * cualquier INSERT concurrente en {@code transacciones} (webhook {@code payment_intent.succeeded})
     * que referencie esta fila debe adquirir su propio lock sobre la fila padre (vía la FK
     * {@code fk_transacciones_publicacion}, V7) y bloquea hasta que la transacción de eliminación
     * termine: compra-vs-delete queda serializado. El consumidor debe invocarlo dentro de una
     * transacción activa (constitución, principio 1); fuera de ella el lock se libera de inmediato
     * y no ofrece garantías. El comportamiento observable del endpoint no cambia (204 sin
     * transacciones, 409 con transacciones, 403 no dueño, 404 inexistente).</p>
     *
     * @param id ID de la publicación a cargar bajo lock
     * @return la publicación encontrada con lock de escritura adquirido, o {@link Optional#empty()}
     *         si no existe (en cuyo caso ningún lock queda adquirido)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Publicacion p where p.id = :id")
    Optional<Publicacion> findByIdWithLock(@Param("id") Long id);

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
