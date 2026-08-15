package com.easymarket.marketplace.service;

import com.easymarket.marketplace.exception.RangoPrecioInvalidoException;
import com.easymarket.marketplace.model.Categoria;
import com.easymarket.marketplace.model.EstadoPublicacion;
import com.easymarket.marketplace.model.Publicacion;
import com.easymarket.marketplace.model.Subcategoria;
import com.easymarket.marketplace.repository.PublicacionRepository;
import com.easymarket.marketplace.repository.TransaccionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias del listado de descubrimiento de Story 11.
 *
 * <p>Verifica que el servicio combine los filtros opcionales, limite la salida a publicaciones
 * aprobadas y aplique exclusivamente los órdenes explícitos autorizados por PHA05TSK02.</p>
 */
@ExtendWith(MockitoExtension.class)
class ListadoPublicacionesServiceTests {

    @Mock
    private PublicacionRepository publicacionRepository;

    @Mock
    private TransaccionRepository transaccionRepository;

    @InjectMocks
    private ListadoPublicacionesService listadoPublicacionesService;

    /**
     * Verifica que el rango inclusivo y el orden descendente de precio se apliquen juntos.
     */
    @Test
    @DisplayName("Debe combinar rango de precio inclusivo con orden descendente de precio")
    void listar_RangoYOrdenPrecioDescendente_CombineAmbosCriterios() {
        Publicacion precioMinimo = publicacion(1L, 10L, 100L, 100L, EstadoPublicacion.APROBADA);
        Publicacion precioIntermedio = publicacion(2L, 10L, 100L, 200L, EstadoPublicacion.APROBADA);
        Publicacion precioMaximo = publicacion(3L, 10L, 100L, 300L, EstadoPublicacion.APROBADA);
        Publicacion fueraDeRango = publicacion(4L, 10L, 100L, 400L, EstadoPublicacion.APROBADA);
        when(publicacionRepository.findByEstado(EstadoPublicacion.APROBADA))
            .thenReturn(List.of(precioIntermedio, fueraDeRango, precioMinimo, precioMaximo));

        List<Publicacion> resultado = listadoPublicacionesService.listar(
            null, null, 100L, 300L, OrdenListadoPublicaciones.PRECIO_DESCENDENTE
        );

        assertThat(resultado).extracting(Publicacion::getId).containsExactly(3L, 2L, 1L);
    }

    /**
     * Verifica que una publicación oculta no se incluya incluso si el repositorio la entrega.
     */
    @Test
    @DisplayName("Debe excluir publicaciones OCULTA y cualquier estado distinto de APROBADA")
    void listar_PublicacionesNoAprobadas_ExcluyeOcultaYCualquierOtroEstado() {
        Publicacion aprobada = publicacion(1L, 10L, 100L, 100L, EstadoPublicacion.APROBADA);
        Publicacion oculta = publicacion(2L, 10L, 100L, 200L, EstadoPublicacion.OCULTA);
        Publicacion pendiente = publicacion(3L, 10L, 100L, 300L, EstadoPublicacion.PENDIENTE_REVISION);
        when(publicacionRepository.findByEstado(EstadoPublicacion.APROBADA))
            .thenReturn(List.of(aprobada, oculta, pendiente));

        List<Publicacion> resultado = listadoPublicacionesService.listar(null, null, null, null, null);

        assertThat(resultado).containsExactly(aprobada);
    }

    /**
     * Verifica el orden explícito ascendente de precio.
     */
    @Test
    @DisplayName("Debe ordenar por precio ascendente cuando se solicita explícitamente")
    void listar_OrdenPrecioAscendente_OrdenaPorPrecioMenorAMayor() {
        Publicacion cara = publicacion(1L, 10L, 100L, 300L, EstadoPublicacion.APROBADA);
        Publicacion barata = publicacion(2L, 10L, 100L, 100L, EstadoPublicacion.APROBADA);
        Publicacion media = publicacion(3L, 10L, 100L, 200L, EstadoPublicacion.APROBADA);
        when(publicacionRepository.findByEstado(EstadoPublicacion.APROBADA)).thenReturn(List.of(cara, barata, media));

        List<Publicacion> resultado = listadoPublicacionesService.listar(
            null, null, null, null, OrdenListadoPublicaciones.PRECIO_ASCENDENTE
        );

        assertThat(resultado).extracting(Publicacion::getId).containsExactly(2L, 3L, 1L);
    }

    /**
     * Verifica el orden explícito de más vendido mediante el conteo reutilizado de PHA05TSK01.
     */
    @Test
    @DisplayName("Debe ordenar por más vendido según conteos descendentes de transacciones completadas")
    void listar_OrdenMasVendido_OrdenaPorConteoDescendente() {
        Publicacion sinVentas = publicacion(1L, 10L, 100L, 100L, EstadoPublicacion.APROBADA);
        Publicacion masVendida = publicacion(2L, 10L, 100L, 200L, EstadoPublicacion.APROBADA);
        Publicacion ventasIntermedias = publicacion(3L, 10L, 100L, 300L, EstadoPublicacion.APROBADA);
        when(publicacionRepository.findByEstado(EstadoPublicacion.APROBADA))
            .thenReturn(List.of(ventasIntermedias, sinVentas, masVendida));
        when(transaccionRepository.contarTransaccionesCompletadasPorPublicacion(1L)).thenReturn(0L);
        when(transaccionRepository.contarTransaccionesCompletadasPorPublicacion(2L)).thenReturn(8L);
        when(transaccionRepository.contarTransaccionesCompletadasPorPublicacion(3L)).thenReturn(3L);

        List<Publicacion> resultado = listadoPublicacionesService.listar(
            null, null, null, null, OrdenListadoPublicaciones.MAS_VENDIDO
        );

        assertThat(resultado).extracting(Publicacion::getId).containsExactly(2L, 3L, 1L);
        verify(transaccionRepository).contarTransaccionesCompletadasPorPublicacion(1L);
        verify(transaccionRepository).contarTransaccionesCompletadasPorPublicacion(2L);
        verify(transaccionRepository).contarTransaccionesCompletadasPorPublicacion(3L);
    }

    /**
     * Verifica que categoría y subcategoría reduzcan conjuntamente el conjunto de resultados.
     */
    @Test
    @DisplayName("Debe combinar los filtros de categoría y subcategoría")
    void listar_CategoriaYSubcategoria_CombineAmbosFiltros() {
        Publicacion coincidencia = publicacion(1L, 10L, 100L, 100L, EstadoPublicacion.APROBADA);
        Publicacion otraSubcategoria = publicacion(2L, 10L, 101L, 100L, EstadoPublicacion.APROBADA);
        Publicacion otraCategoria = publicacion(3L, 11L, 100L, 100L, EstadoPublicacion.APROBADA);
        when(publicacionRepository.findByEstado(EstadoPublicacion.APROBADA))
            .thenReturn(List.of(coincidencia, otraSubcategoria, otraCategoria));

        List<Publicacion> resultado = listadoPublicacionesService.listar(10L, 100L, null, null, null);

        assertThat(resultado).containsExactly(coincidencia);
    }

    /**
     * Verifica la inclusión de límites iguales y el comportamiento de cada límite aislado.
     */
    @Test
    @DisplayName("Debe incluir límites de precio y admitir mínimo o máximo aislados")
    void listar_LimitesInclusivosYAislados_FiltraCorrectamente() {
        Publicacion bajo = publicacion(1L, 10L, 100L, 99L, EstadoPublicacion.APROBADA);
        Publicacion minimo = publicacion(2L, 10L, 100L, 100L, EstadoPublicacion.APROBADA);
        Publicacion medio = publicacion(3L, 10L, 100L, 150L, EstadoPublicacion.APROBADA);
        Publicacion maximo = publicacion(4L, 10L, 100L, 200L, EstadoPublicacion.APROBADA);
        Publicacion alto = publicacion(5L, 10L, 100L, 201L, EstadoPublicacion.APROBADA);
        when(publicacionRepository.findByEstado(EstadoPublicacion.APROBADA))
            .thenReturn(List.of(bajo, minimo, medio, maximo, alto));

        assertThat(listadoPublicacionesService.listar(null, null, 100L, 200L, null))
            .containsExactly(minimo, medio, maximo);
        assertThat(listadoPublicacionesService.listar(null, null, 150L, null, null))
            .containsExactly(medio, maximo, alto);
        assertThat(listadoPublicacionesService.listar(null, null, null, 150L, null))
            .containsExactly(bajo, minimo, medio);
    }

    /**
     * Verifica que un rango invertido se rechace antes de consultar cualquier repositorio.
     */
    @Test
    @DisplayName("Debe rechazar rango de precio invertido sin consultar repositorios")
    void listar_RangoInvertido_LanzaRangoPrecioInvalidoExceptionSinConsultarRepositorios() {
        assertThatThrownBy(() -> listadoPublicacionesService.listar(null, null, 201L, 200L, null))
            .isInstanceOf(RangoPrecioInvalidoException.class)
            .hasMessageContaining("El precio mínimo no puede ser mayor que el precio máximo");

        verifyNoInteractions(publicacionRepository, transaccionRepository);
    }

    /**
     * Verifica que la ausencia de orden conserve el orden recibido y no consulte conteos de ventas.
     */
    @Test
    @DisplayName("No debe imponer orden ni desempate cuando orden es null")
    void listar_OrdenNulo_ConservaOrdenRecibidoSinConsultarConteos() {
        Publicacion primera = publicacion(1L, 10L, 100L, 300L, EstadoPublicacion.APROBADA);
        Publicacion segunda = publicacion(2L, 10L, 100L, 100L, EstadoPublicacion.APROBADA);
        when(publicacionRepository.findByEstado(EstadoPublicacion.APROBADA)).thenReturn(List.of(primera, segunda));

        List<Publicacion> resultado = listadoPublicacionesService.listar(null, null, null, null, null);

        assertThat(resultado).containsExactly(primera, segunda);
        verify(transaccionRepository, never()).contarTransaccionesCompletadasPorPublicacion(1L);
        verify(transaccionRepository, never()).contarTransaccionesCompletadasPorPublicacion(2L);
    }

    /**
     * Construye una publicación con relaciones identificables para las pruebas de filtrado.
     *
     * @param publicacionId identificador de la publicación
     * @param categoriaId identificador de su categoría
     * @param subcategoriaId identificador de su subcategoría
     * @param precio precio entero en centavos
     * @param estado estado de visibilidad de la publicación
     * @return publicación configurada para la prueba
     */
    private Publicacion publicacion(Long publicacionId, Long categoriaId, Long subcategoriaId, long precio,
                                   EstadoPublicacion estado) {
        Categoria categoria = new Categoria("Categoría " + categoriaId);
        categoria.setId(categoriaId);
        Subcategoria subcategoria = new Subcategoria(categoria, "Subcategoría " + subcategoriaId);
        subcategoria.setId(subcategoriaId);
        Publicacion publicacion = new Publicacion();
        publicacion.setId(publicacionId);
        publicacion.setCategoria(categoria);
        publicacion.setSubcategoria(subcategoria);
        publicacion.setPrecio(precio);
        publicacion.setEstado(estado);
        return publicacion;
    }
}
