package com.easymarket.marketplace.controller;

import com.easymarket.marketplace.dto.CategoriaRequestDto;
import com.easymarket.marketplace.dto.CategoriaResponseDto;
import com.easymarket.marketplace.dto.SubcategoriaRequestDto;
import com.easymarket.marketplace.dto.SubcategoriaResponseDto;
import com.easymarket.marketplace.model.Categoria;
import com.easymarket.marketplace.model.Subcategoria;
import com.easymarket.marketplace.service.CategoriaService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller REST para la gestión de categorías y subcategorías del catálogo (Story 4, spec.md).
 *
 * <p>Protegido por el rol {@code ADMIN} mediante las reglas de seguridad HTTP y anotaciones {@code @PreAuthorize}.
 * Expone endpoints para la creación, edición y eliminación de categorías raíz y subcategorías anidadas.</p>
 */
@RestController
@RequestMapping("/categorias")
public class CategoriaController {

    private final CategoriaService categoriaService;

    /**
     * Construye el controlador inyectando el servicio de catálogo.
     *
     * @param categoriaService servicio de dominio para la gestión de categorías
     */
    public CategoriaController(CategoriaService categoriaService) {
        this.categoriaService = categoriaService;
    }

    /**
     * Endpoint REST {@code GET /categorias} público para consultar el árbol completo de categorías y subcategorías anidadas.
     *
     * @return {@link ResponseEntity} con código HTTP 200 OK y la lista completa del árbol ordenado por nombre
     */
    @org.springframework.web.bind.annotation.GetMapping
    public ResponseEntity<java.util.List<com.easymarket.marketplace.dto.CategoriaArbolResponseDto>> obtenerArbolCategorias() {
        return ResponseEntity.ok(categoriaService.obtenerArbolCategorias());
    }

    /**
     * Endpoint REST {@code POST /categorias} para la creación de una nueva categoría raíz.
     *
     * @param requestDto DTO con el nombre de la nueva categoría
     * @return {@link ResponseEntity} con código HTTP 201 Created y la categoría creada
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CategoriaResponseDto> crearCategoria(@Valid @RequestBody CategoriaRequestDto requestDto) {
        Categoria creada = categoriaService.crearCategoria(requestDto.nombre());
        return ResponseEntity.status(HttpStatus.CREATED).body(CategoriaResponseDto.fromEntity(creada));
    }

    /**
     * Endpoint REST {@code PUT /categorias/{id}} para la modificación del nombre de una categoría raíz existente.
     *
     * @param id ID de la categoría a modificar
     * @param requestDto DTO con el nuevo nombre
     * @return {@link ResponseEntity} con código HTTP 200 OK y la categoría modificada
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CategoriaResponseDto> editarCategoria(
            @PathVariable("id") Long id,
            @Valid @RequestBody CategoriaRequestDto requestDto
    ) {
        Categoria editada = categoriaService.editarCategoria(id, requestDto.nombre());
        return ResponseEntity.ok(CategoriaResponseDto.fromEntity(editada));
    }

    /**
     * Endpoint REST {@code DELETE /categorias/{id}} para eliminar una categoría raíz sin publicaciones vinculadas.
     *
     * @param id ID de la categoría a eliminar
     * @return {@link ResponseEntity} con código HTTP 204 No Content
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> eliminarCategoria(@PathVariable("id") Long id) {
        categoriaService.eliminarCategoria(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Endpoint REST {@code POST /categorias/{id}/subcategorias} para crear una subcategoría en una categoría padre.
     *
     * @param id ID de la categoría raíz padre
     * @param requestDto DTO con el nombre de la subcategoría
     * @return {@link ResponseEntity} con código HTTP 201 Created y la subcategoría creada
     */
    @PostMapping("/{id}/subcategorias")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SubcategoriaResponseDto> crearSubcategoria(
            @PathVariable("id") Long id,
            @Valid @RequestBody SubcategoriaRequestDto requestDto
    ) {
        Subcategoria creada = categoriaService.crearSubcategoria(id, requestDto.nombre());
        return ResponseEntity.status(HttpStatus.CREATED).body(SubcategoriaResponseDto.fromEntity(creada));
    }

    /**
     * Endpoint REST {@code PUT /categorias/{categoriaId}/subcategorias/{id}} para editar el nombre de una subcategoría.
     *
     * @param categoriaId ID de la categoría padre
     * @param id ID de la subcategoría a editar
     * @param requestDto DTO con el nuevo nombre
     * @return {@link ResponseEntity} con código HTTP 200 OK y la subcategoría editada
     */
    @PutMapping("/{categoriaId}/subcategorias/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SubcategoriaResponseDto> editarSubcategoria(
            @PathVariable("categoriaId") Long categoriaId,
            @PathVariable("id") Long id,
            @Valid @RequestBody SubcategoriaRequestDto requestDto
    ) {
        Subcategoria editada = categoriaService.editarSubcategoria(id, requestDto.nombre());
        return ResponseEntity.ok(SubcategoriaResponseDto.fromEntity(editada));
    }

    /**
     * Endpoint REST {@code DELETE /categorias/{categoriaId}/subcategorias/{id}} para eliminar una subcategoría sin publicaciones.
     *
     * @param categoriaId ID de la categoría padre
     * @param id ID de la subcategoría a eliminar
     * @return {@link ResponseEntity} con código HTTP 204 No Content
     */
    @DeleteMapping("/{categoriaId}/subcategorias/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> eliminarSubcategoria(
            @PathVariable("categoriaId") Long categoriaId,
            @PathVariable("id") Long id
    ) {
        categoriaService.eliminarSubcategoria(id);
        return ResponseEntity.noContent().build();
    }
}
