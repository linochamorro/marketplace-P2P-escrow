package com.easymarket.marketplace.service;

import com.easymarket.marketplace.exception.CategoriaConPublicacionesException;
import com.easymarket.marketplace.exception.CategoriaNoEncontradaException;
import com.easymarket.marketplace.exception.NombreCategoriaDuplicadoException;
import com.easymarket.marketplace.model.Categoria;
import com.easymarket.marketplace.model.Subcategoria;
import com.easymarket.marketplace.repository.CategoriaRepository;
import com.easymarket.marketplace.repository.PublicacionCountRepository;
import com.easymarket.marketplace.repository.SubcategoriaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias para {@link CategoriaService}.
 *
 * <p>Verifica las reglas de negocio de la gestión de categorías y subcategorías (Story 4, spec.md):
 * <ul>
 *   <li>Validación de unicidad de nombre a nivel de categoría raíz (no pueden duplicarse globalmente).</li>
 *   <li>Validación de unicidad de nombre de subcategoría por categoría padre (mismo nombre permitido en distintas categorías padre).</li>
 *   <li>Rechazo de eliminación/edición de categorías y subcategorías cuando existen publicaciones asociadas.</li>
 * </ul>
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class CategoriaServiceTests {

    @Mock
    private CategoriaRepository categoriaRepository;

    @Mock
    private SubcategoriaRepository subcategoriaRepository;

    @Mock
    private PublicacionCountRepository publicacionCountRepository;

    @InjectMocks
    private CategoriaService categoriaService;

    /**
     * Verifica la creación exitosa de una categoría raíz con nombre único.
     */
    @Test
    @DisplayName("Debe crear una categoría raíz cuando el nombre es único")
    void crearCategoria_NombreNuevo_GuardaYRetornaCategoria() {
        when(categoriaRepository.existsByNombre("Electrónica")).thenReturn(false);
        Categoria categoriaGuardada = new Categoria("Electrónica");
        categoriaGuardada.setId(1L);
        when(categoriaRepository.save(any(Categoria.class))).thenReturn(categoriaGuardada);

        Categoria resultado = categoriaService.crearCategoria("Electrónica");

        assertThat(resultado.getId()).isEqualTo(1L);
        assertThat(resultado.getNombre()).isEqualTo("Electrónica");
        verify(categoriaRepository).save(any(Categoria.class));
    }

    /**
     * Verifica que se rechace la creación de una categoría raíz con nombre duplicado.
     */
    @Test
    @DisplayName("Debe lanzar NombreCategoriaDuplicadoException si el nombre de categoría ya existe")
    void crearCategoria_NombreDuplicado_LanzaExcepcion() {
        when(categoriaRepository.existsByNombre("Electrónica")).thenReturn(true);

        assertThatThrownBy(() -> categoriaService.crearCategoria("Electrónica"))
            .isInstanceOf(NombreCategoriaDuplicadoException.class)
            .hasMessageContaining("Ya existe una categoría raíz con el nombre 'Electrónica'");

        verify(categoriaRepository, never()).save(any(Categoria.class));
    }

    /**
     * Verifica la creación exitosa de una subcategoría con nombre único dentro de su categoría padre.
     */
    @Test
    @DisplayName("Debe crear subcategoría cuando el nombre es único dentro de la misma categoría padre")
    void crearSubcategoria_NombreNuevoEnMismaCategoria_GuardaYRetornaSubcategoria() {
        Categoria categoriaPadre = new Categoria("Electrónica");
        categoriaPadre.setId(1L);

        when(categoriaRepository.findById(1L)).thenReturn(Optional.of(categoriaPadre));
        when(subcategoriaRepository.existsByCategoriaIdAndNombre(1L, "Laptops")).thenReturn(false);

        Subcategoria subcategoriaGuardada = new Subcategoria(categoriaPadre, "Laptops");
        subcategoriaGuardada.setId(10L);
        when(subcategoriaRepository.save(any(Subcategoria.class))).thenReturn(subcategoriaGuardada);

        Subcategoria resultado = categoriaService.crearSubcategoria(1L, "Laptops");

        assertThat(resultado.getId()).isEqualTo(10L);
        assertThat(resultado.getNombre()).isEqualTo("Laptops");
        assertThat(resultado.getCategoria().getId()).isEqualTo(1L);
    }

    /**
     * Verifica que se rechace una subcategoría con nombre duplicado dentro de la misma categoría padre.
     */
    @Test
    @DisplayName("Debe lanzar NombreCategoriaDuplicadoException si la subcategoría ya existe en la misma categoría")
    void crearSubcategoria_NombreDuplicadoEnMismaCategoria_LanzaExcepcion() {
        Categoria categoriaPadre = new Categoria("Electrónica");
        categoriaPadre.setId(1L);

        when(categoriaRepository.findById(1L)).thenReturn(Optional.of(categoriaPadre));
        when(subcategoriaRepository.existsByCategoriaIdAndNombre(1L, "Laptops")).thenReturn(true);

        assertThatThrownBy(() -> categoriaService.crearSubcategoria(1L, "Laptops"))
            .isInstanceOf(NombreCategoriaDuplicadoException.class)
            .hasMessageContaining("Ya existe una subcategoría 'Laptops' en esta categoría");

        verify(subcategoriaRepository, never()).save(any(Subcategoria.class));
    }

    /**
     * Verifica que se permita crear una subcategoría con igual nombre si pertenece a distinta categoría padre.
     */
    @Test
    @DisplayName("Debe permitir subcategoría con el mismo nombre en una categoría padre distinta")
    void crearSubcategoria_NombreIgualEnDistintaCategoria_PermiteCreacion() {
        Categoria categoriaHogar = new Categoria("Hogar");
        categoriaHogar.setId(2L);

        when(categoriaRepository.findById(2L)).thenReturn(Optional.of(categoriaHogar));
        when(subcategoriaRepository.existsByCategoriaIdAndNombre(2L, "Accesorios")).thenReturn(false);

        Subcategoria subcategoria = new Subcategoria(categoriaHogar, "Accesorios");
        subcategoria.setId(20L);
        when(subcategoriaRepository.save(any(Subcategoria.class))).thenReturn(subcategoria);

        Subcategoria resultado = categoriaService.crearSubcategoria(2L, "Accesorios");

        assertThat(resultado.getId()).isEqualTo(20L);
        assertThat(resultado.getNombre()).isEqualTo("Accesorios");
    }

    /**
     * Verifica que se rechace crear una subcategoría si la categoría padre no existe.
     */
    @Test
    @DisplayName("Debe lanzar CategoriaNoEncontradaException si la categoría padre no existe")
    void crearSubcategoria_CategoriaPadreInexistente_LanzaExcepcion() {
        when(categoriaRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> categoriaService.crearSubcategoria(99L, "Monitores"))
            .isInstanceOf(CategoriaNoEncontradaException.class)
            .hasMessageContaining("Categoría raíz con ID 99 no encontrada");

        verify(subcategoriaRepository, never()).save(any(Subcategoria.class));
    }

    /**
     * Verifica la edición exitosa del nombre de una categoría.
     */
    @Test
    @DisplayName("Debe editar el nombre de una categoría existente")
    void editarCategoria_NombreNuevo_ActualizaCorrectamente() {
        Categoria categoria = new Categoria("Electronica");
        categoria.setId(1L);

        when(categoriaRepository.findById(1L)).thenReturn(Optional.of(categoria));
        when(categoriaRepository.existsByNombre("Tecnología")).thenReturn(false);
        when(categoriaRepository.save(any(Categoria.class))).thenReturn(categoria);

        Categoria resultado = categoriaService.editarCategoria(1L, "Tecnología");

        assertThat(resultado.getNombre()).isEqualTo("Tecnología");
        verify(categoriaRepository).save(categoria);
    }

    /**
     * Verifica que se rechace editar una categoría a un nombre ya existente.
     */
    @Test
    @DisplayName("Debe lanzar NombreCategoriaDuplicadoException al editar a un nombre existente")
    void editarCategoria_NombreDuplicado_LanzaExcepcion() {
        Categoria categoria = new Categoria("Electronica");
        categoria.setId(1L);

        when(categoriaRepository.findById(1L)).thenReturn(Optional.of(categoria));
        when(categoriaRepository.existsByNombre("Hogar")).thenReturn(true);

        assertThatThrownBy(() -> categoriaService.editarCategoria(1L, "Hogar"))
            .isInstanceOf(NombreCategoriaDuplicadoException.class)
            .hasMessageContaining("Ya existe una categoría raíz con el nombre 'Hogar'");
    }

    /**
     * Verifica la eliminación exitosa de una categoría sin publicaciones asociadas.
     */
    @Test
    @DisplayName("Debe eliminar la categoría cuando no existen publicaciones asociadas")
    void eliminarCategoria_SinPublicacionesAsociadas_EliminaCorrectamente() {
        Categoria categoria = new Categoria("Deportes");
        categoria.setId(5L);

        when(categoriaRepository.findById(5L)).thenReturn(Optional.of(categoria));
        when(publicacionCountRepository.existsByCategoriaId(5L)).thenReturn(false);

        categoriaService.eliminarCategoria(5L);

        verify(categoriaRepository).delete(categoria);
    }

    /**
     * Verifica que se rechace eliminar una categoría con publicaciones asociadas.
     */
    @Test
    @DisplayName("Debe lanzar CategoriaConPublicacionesException al intentar eliminar categoría con publicaciones asociadas")
    void eliminarCategoria_ConPublicacionesAsociadas_LanzaExcepcion() {
        Categoria categoria = new Categoria("Deportes");
        categoria.setId(5L);

        when(categoriaRepository.findById(5L)).thenReturn(Optional.of(categoria));
        when(publicacionCountRepository.existsByCategoriaId(5L)).thenReturn(true);

        assertThatThrownBy(() -> categoriaService.eliminarCategoria(5L))
            .isInstanceOf(CategoriaConPublicacionesException.class)
            .hasMessageContaining("No se puede eliminar la categoría 'Deportes' porque tiene publicaciones asociadas");

        verify(categoriaRepository, never()).delete(any(Categoria.class));
    }

    /**
     * Verifica la eliminación exitosa de una subcategoría sin publicaciones asociadas.
     */
    @Test
    @DisplayName("Debe eliminar subcategoría cuando no existen publicaciones asociadas")
    void eliminarSubcategoria_SinPublicacionesAsociadas_EliminaCorrectamente() {
        Categoria categoria = new Categoria("Deportes");
        categoria.setId(5L);
        Subcategoria subcategoria = new Subcategoria(categoria, "Balones");
        subcategoria.setId(50L);

        when(subcategoriaRepository.findById(50L)).thenReturn(Optional.of(subcategoria));
        when(publicacionCountRepository.existsBySubcategoriaId(50L)).thenReturn(false);

        categoriaService.eliminarSubcategoria(50L);

        verify(subcategoriaRepository).delete(subcategoria);
    }

    /**
     * Verifica que se rechace eliminar una subcategoría con publicaciones asociadas.
     */
    @Test
    @DisplayName("Debe lanzar CategoriaConPublicacionesException al intentar eliminar subcategoría con publicaciones asociadas")
    void eliminarSubcategoria_ConPublicacionesAsociadas_LanzaExcepcion() {
        Categoria categoria = new Categoria("Deportes");
        categoria.setId(5L);
        Subcategoria subcategoria = new Subcategoria(categoria, "Balones");
        subcategoria.setId(50L);

        when(subcategoriaRepository.findById(50L)).thenReturn(Optional.of(subcategoria));
        when(publicacionCountRepository.existsBySubcategoriaId(50L)).thenReturn(true);

        assertThatThrownBy(() -> categoriaService.eliminarSubcategoria(50L))
            .isInstanceOf(CategoriaConPublicacionesException.class)
            .hasMessageContaining("No se puede eliminar la subcategoría 'Balones' porque tiene publicaciones asociadas");

        verify(subcategoriaRepository, never()).delete(any(Subcategoria.class));
    }
}
