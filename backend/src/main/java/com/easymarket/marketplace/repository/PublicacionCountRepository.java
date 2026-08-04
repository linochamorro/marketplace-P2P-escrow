package com.easymarket.marketplace.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Repositorio de consulta mínima sobre la tabla {@code publicaciones} para la verificación de existencia de publicaciones asociadas.
 *
 * <p>Esta clase provee consultas de conteo directo sin requerir el mapeo JPA ni el servicio completo de la entidad {@code Publicacion}
 * (cuya construcción pertenece a PHA02TSK04), previniendo scope creep y manteniendo aislamiento de capas.</p>
 */
@Repository
public class PublicacionCountRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Construye el repositorio inyectando {@link JdbcTemplate}.
     *
     * @param jdbcTemplate plantilla JDBC para consultas de base de datos
     */
    public PublicacionCountRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Comprueba si existe al menos una publicación asociada a la categoría especificada.
     *
     * @param categoriaId ID de la categoría a consultar
     * @return true si existe al menos una publicación vinculada, false en caso contrario
     */
    public boolean existsByCategoriaId(Long categoriaId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM publicaciones WHERE categoria_id = ?",
            Integer.class,
            categoriaId
        );
        return count != null && count > 0;
    }

    /**
     * Comprueba si existe al menos una publicación asociada a la subcategoría especificada.
     *
     * @param subcategoriaId ID de la subcategoría a consultar
     * @return true si existe al menos una publicación vinculada, false en caso contrario
     */
    public boolean existsBySubcategoriaId(Long subcategoriaId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM publicaciones WHERE subcategoria_id = ?",
            Integer.class,
            subcategoriaId
        );
        return count != null && count > 0;
    }
}
