package com.easymarket.marketplace.service;

import com.easymarket.marketplace.exception.CategoriaConPublicacionesException;
import com.easymarket.marketplace.exception.CategoriaNoEncontradaException;
import com.easymarket.marketplace.exception.NombreCategoriaDuplicadoException;
import com.easymarket.marketplace.model.Categoria;
import com.easymarket.marketplace.model.Subcategoria;
import com.easymarket.marketplace.repository.CategoriaRepository;
import com.easymarket.marketplace.repository.PublicacionCountRepository;
import com.easymarket.marketplace.repository.SubcategoriaRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Servicio de dominio para la gestión del catálogo de categorías y subcategorías (Story 4, spec.md).
 *
 * <p>Responsable de crear, editar y eliminar categorías y subcategorías haciendo cumplir las siguientes reglas:
 * <ul>
 *   <li>Validación de unicidad de nombre de categorías raíz (único a nivel global).</li>
 *   <li>Validación de unicidad de nombre de subcategorías por categoría padre.</li>
 *   <li>Rechazo de eliminación si existen publicaciones asociadas a la categoría o subcategoría.</li>
 *   <li>Traducción defensiva de violaciones de restricción de base de datos a excepciones de dominio.</li>
 * </ul>
 * </p>
 */
@Service
public class CategoriaService {

    private final CategoriaRepository categoriaRepository;
    private final SubcategoriaRepository subcategoriaRepository;
    private final PublicacionCountRepository publicacionCountRepository;

    /**
     * Construye el servicio inyectando los repositorios necesarios.
     *
     * @param categoriaRepository repositorio JPA de categorías
     * @param subcategoriaRepository repositorio JPA de subcategorías
     * @param publicacionCountRepository repositorio de conteo de publicaciones
     */
    public CategoriaService(CategoriaRepository categoriaRepository,
                            SubcategoriaRepository subcategoriaRepository,
                            PublicacionCountRepository publicacionCountRepository) {
        this.categoriaRepository = categoriaRepository;
        this.subcategoriaRepository = subcategoriaRepository;
        this.publicacionCountRepository = publicacionCountRepository;
    }

    /**
     * Crea una nueva categoría raíz validando previamente la unicidad del nombre.
     *
     * @param nombre nombre único de la categoría raíz
     * @return la entidad {@link Categoria} creada y persistida
     * @throws NombreCategoriaDuplicadoException si el nombre ya existe
     */
    @Transactional
    public Categoria crearCategoria(String nombre) {
        if (categoriaRepository.existsByNombre(nombre)) {
            throw new NombreCategoriaDuplicadoException("Ya existe una categoría raíz con el nombre '" + nombre + "'");
        }
        try {
            return categoriaRepository.save(new Categoria(nombre));
        } catch (DataIntegrityViolationException e) {
            throw new NombreCategoriaDuplicadoException("Ya existe una categoría raíz con el nombre '" + nombre + "'");
        }
    }

    /**
     * Crea una nueva subcategoría perteneciente a una categoría raíz dada.
     *
     * @param categoriaId ID de la categoría raíz padre
     * @param nombre nombre de la subcategoría (único dentro de la categoría padre)
     * @return la entidad {@link Subcategoria} creada y persistida
     * @throws CategoriaNoEncontradaException si la categoría raíz no existe
     * @throws NombreCategoriaDuplicadoException si ya existe una subcategoría con ese nombre en la misma categoría padre
     */
    @Transactional
    public Subcategoria crearSubcategoria(Long categoriaId, String nombre) {
        Categoria categoria = categoriaRepository.findById(categoriaId)
            .orElseThrow(() -> new CategoriaNoEncontradaException("Categoría raíz con ID " + categoriaId + " no encontrada"));

        if (subcategoriaRepository.existsByCategoriaIdAndNombre(categoriaId, nombre)) {
            throw new NombreCategoriaDuplicadoException("Ya existe una subcategoría '" + nombre + "' en esta categoría");
        }
        try {
            return subcategoriaRepository.save(new Subcategoria(categoria, nombre));
        } catch (DataIntegrityViolationException e) {
            throw new NombreCategoriaDuplicadoException("Ya existe una subcategoría '" + nombre + "' en esta categoría");
        }
    }

    /**
     * Edita el nombre de una categoría raíz existente.
     *
     * @param id ID de la categoría a editar
     * @param nuevoNombre nuevo nombre a asignar
     * @return la entidad {@link Categoria} modificada
     * @throws CategoriaNoEncontradaException si la categoría no existe
     * @throws NombreCategoriaDuplicadoException si el nuevo nombre ya pertenece a otra categoría raíz
     */
    @Transactional
    public Categoria editarCategoria(Long id, String nuevoNombre) {
        Categoria categoria = categoriaRepository.findById(id)
            .orElseThrow(() -> new CategoriaNoEncontradaException("Categoría con ID " + id + " no encontrada"));

        if (!categoria.getNombre().equalsIgnoreCase(nuevoNombre) && categoriaRepository.existsByNombre(nuevoNombre)) {
            throw new NombreCategoriaDuplicadoException("Ya existe una categoría raíz con el nombre '" + nuevoNombre + "'");
        }

        categoria.setNombre(nuevoNombre);
        try {
            return categoriaRepository.save(categoria);
        } catch (DataIntegrityViolationException e) {
            throw new NombreCategoriaDuplicadoException("Ya existe una categoría raíz con el nombre '" + nuevoNombre + "'");
        }
    }

    /**
     * Edita el nombre de una subcategoría existente.
     *
     * @param subcategoriaId ID de la subcategoría a editar
     * @param nuevoNombre nuevo nombre a asignar
     * @return la entidad {@link Subcategoria} modificada
     * @throws CategoriaNoEncontradaException si la subcategoría no existe
     * @throws NombreCategoriaDuplicadoException si el nuevo nombre ya existe dentro de la misma categoría padre
     */
    @Transactional
    public Subcategoria editarSubcategoria(Long subcategoriaId, String nuevoNombre) {
        Subcategoria subcategoria = subcategoriaRepository.findById(subcategoriaId)
            .orElseThrow(() -> new CategoriaNoEncontradaException("Subcategoría con ID " + subcategoriaId + " no encontrada"));

        Long categoriaId = subcategoria.getCategoria().getId();
        if (!subcategoria.getNombre().equalsIgnoreCase(nuevoNombre)
                && subcategoriaRepository.existsByCategoriaIdAndNombre(categoriaId, nuevoNombre)) {
            throw new NombreCategoriaDuplicadoException("Ya existe una subcategoría '" + nuevoNombre + "' en esta categoría");
        }

        subcategoria.setNombre(nuevoNombre);
        try {
            return subcategoriaRepository.save(subcategoria);
        } catch (DataIntegrityViolationException e) {
            throw new NombreCategoriaDuplicadoException("Ya existe una subcategoría '" + nuevoNombre + "' en esta categoría");
        }
    }

    /**
     * Elimina una categoría raíz si no mantiene publicaciones asociadas.
     *
     * @param id ID de la categoría a eliminar
     * @throws CategoriaNoEncontradaException si la categoría no existe
     * @throws CategoriaConPublicacionesException si existen publicaciones vinculadas a esta categoría
     */
    @Transactional
    public void eliminarCategoria(Long id) {
        Categoria categoria = categoriaRepository.findById(id)
            .orElseThrow(() -> new CategoriaNoEncontradaException("Categoría con ID " + id + " no encontrada"));

        if (publicacionCountRepository.existsByCategoriaId(id)) {
            throw new CategoriaConPublicacionesException(
                "No se puede eliminar la categoría '" + categoria.getNombre() + "' porque tiene publicaciones asociadas"
            );
        }

        if (subcategoriaRepository.existsByCategoriaId(id)) {
            throw new CategoriaConPublicacionesException(
                "No se puede eliminar la categoría '" + categoria.getNombre() + "' porque tiene subcategorías asociadas"
            );
        }

        try {
            categoriaRepository.delete(categoria);
            categoriaRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new CategoriaConPublicacionesException(
                "No se puede eliminar la categoría '" + categoria.getNombre() + "' porque tiene elementos asociados"
            );
        }
    }



    /**
     * Elimina una subcategoría si no mantiene publicaciones asociadas.
     *
     * @param subcategoriaId ID de la subcategoría a eliminar
     * @throws CategoriaNoEncontradaException si la subcategoría no existe
     * @throws CategoriaConPublicacionesException si existen publicaciones vinculadas a esta subcategoría
     */
    @Transactional
    public void eliminarSubcategoria(Long subcategoriaId) {
        Subcategoria subcategoria = subcategoriaRepository.findById(subcategoriaId)
            .orElseThrow(() -> new CategoriaNoEncontradaException("Subcategoría con ID " + subcategoriaId + " no encontrada"));

        if (publicacionCountRepository.existsBySubcategoriaId(subcategoriaId)) {
            throw new CategoriaConPublicacionesException(
                "No se puede eliminar la subcategoría '" + subcategoria.getNombre() + "' porque tiene publicaciones asociadas"
            );
        }

        subcategoriaRepository.delete(subcategoria);
    }

    /**
     * Obtiene una categoría por su ID.
     *
     * @param id ID de la categoría
     * @return la categoría encontrada
     * @throws CategoriaNoEncontradaException si no existe
     */
    @Transactional(readOnly = true)
    public Categoria obtenerCategoriaPorId(Long id) {
        return categoriaRepository.findById(id)
            .orElseThrow(() -> new CategoriaNoEncontradaException("Categoría con ID " + id + " no encontrada"));
    }

    /**
     * Obtiene las subcategorías pertenecientes a una categoría padre.
     *
     * @param categoriaId ID de la categoría padre
     * @return lista de subcategorías
     */
    @Transactional(readOnly = true)
    public List<Subcategoria> obtenerSubcategoriasPorCategoriaId(Long categoriaId) {
        return subcategoriaRepository.findByCategoriaId(categoriaId);
    }

    /**
     * Obtiene el árbol completo de categorías raíz con sus subcategorías anidadas.
     *
     * <p>Optimiza la consulta recuperando todas las categorías y subcategorías ordenadas alfabéticamente
     * en dos consultas y agrupándolas en memoria para evitar el problema de N+1 consultas.</p>
     *
     * @return lista de DTOs {@link com.easymarket.marketplace.dto.CategoriaArbolResponseDto} ordenados por nombre
     */
    @Transactional(readOnly = true)
    public List<com.easymarket.marketplace.dto.CategoriaArbolResponseDto> obtenerArbolCategorias() {
        List<Categoria> categorias = categoriaRepository.findAllByOrderByNombreAsc();
        List<Subcategoria> subcategorias = subcategoriaRepository.findAllByOrderByNombreAsc();

        java.util.Map<Long, List<com.easymarket.marketplace.dto.CategoriaArbolResponseDto.SubcategoriaItemResponseDto>> subcatsPorCategoriaId =
                subcategorias.stream()
                        .collect(java.util.stream.Collectors.groupingBy(
                                sub -> sub.getCategoria().getId(),
                                java.util.stream.Collectors.mapping(
                                        sub -> new com.easymarket.marketplace.dto.CategoriaArbolResponseDto.SubcategoriaItemResponseDto(
                                                sub.getId(),
                                                sub.getNombre()
                                        ),
                                        java.util.stream.Collectors.toList()
                                )
                        ));

        return categorias.stream()
                .map(cat -> new com.easymarket.marketplace.dto.CategoriaArbolResponseDto(
                        cat.getId(),
                        cat.getNombre(),
                        subcatsPorCategoriaId.getOrDefault(cat.getId(), List.of())
                ))
                .toList();
    }
}
