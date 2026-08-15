package com.easymarket.marketplace.service;

import com.easymarket.marketplace.exception.RangoPrecioInvalidoException;
import com.easymarket.marketplace.model.EstadoPublicacion;
import com.easymarket.marketplace.model.Publicacion;
import com.easymarket.marketplace.repository.PublicacionRepository;
import com.easymarket.marketplace.repository.TransaccionRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Servicio de dominio que descubre publicaciones aprobadas mediante filtros y órdenes de Story 11.
 *
 * <p>Esta operación es exclusivamente de lectura: no modifica publicaciones, transacciones, stock,
 * dinero ni registros de auditoría.</p>
 */
@Service
public class ListadoPublicacionesService {

    private final PublicacionRepository publicacionRepository;
    private final TransaccionRepository transaccionRepository;

    /**
     * Crea el servicio con los repositorios de lectura necesarios para listar y contar ventas.
     *
     * @param publicacionRepository repositorio de publicaciones
     * @param transaccionRepository repositorio que expone el conteo de ventas completadas de PHA05TSK01
     */
    public ListadoPublicacionesService(PublicacionRepository publicacionRepository,
                                       TransaccionRepository transaccionRepository) {
        this.publicacionRepository = publicacionRepository;
        this.transaccionRepository = transaccionRepository;
    }

    /**
     * Lista publicaciones aprobadas aplicando opcionalmente filtros combinables y un orden explícito.
     *
     * <p>Los límites de precio son inclusivos. Si {@code orden} es {@code null}, conserva el orden
     * recibido del repositorio y no introduce orden ni desempate. Para {@link
     * OrdenListadoPublicaciones#MAS_VENDIDO}, reutiliza el conteo de PHA05TSK01 sin crear consultas
     * adicionales.</p>
     *
     * @param categoriaId identificador opcional de categoría
     * @param subcategoriaId identificador opcional de subcategoría
     * @param precioMinimo límite inferior inclusivo opcional, expresado en centavos enteros
     * @param precioMaximo límite superior inclusivo opcional, expresado en centavos enteros
     * @param orden orden explícito opcional para los resultados
     * @return publicaciones exclusivamente aprobadas que satisfacen todos los filtros presentes
     * @throws RangoPrecioInvalidoException si ambos límites existen y el mínimo es mayor que el máximo
     */
    public List<Publicacion> listar(Long categoriaId, Long subcategoriaId, Long precioMinimo, Long precioMaximo,
                                    OrdenListadoPublicaciones orden) {
        validarRango(precioMinimo, precioMaximo);

        List<Publicacion> publicaciones = publicacionRepository.findByEstado(EstadoPublicacion.APROBADA).stream()
            .filter(publicacion -> publicacion.getEstado() == EstadoPublicacion.APROBADA)
            .filter(coincideCategoria(categoriaId))
            .filter(coincideSubcategoria(subcategoriaId))
            .filter(coincidePrecioMinimo(precioMinimo))
            .filter(coincidePrecioMaximo(precioMaximo))
            .collect(Collectors.toCollection(ArrayList::new));

        if (orden != null) {
            ordenar(publicaciones, orden);
        }

        return publicaciones;
    }

    /**
     * Rechaza un rango invertido antes de consultar los repositorios.
     *
     * @param precioMinimo límite inferior opcional en centavos
     * @param precioMaximo límite superior opcional en centavos
     * @throws RangoPrecioInvalidoException si ambos límites existen y el inferior excede al superior
     */
    private void validarRango(Long precioMinimo, Long precioMaximo) {
        if (precioMinimo != null && precioMaximo != null && precioMinimo > precioMaximo) {
            throw new RangoPrecioInvalidoException("El precio mínimo no puede ser mayor que el precio máximo");
        }
    }

    /**
     * Crea el predicado del filtro opcional de categoría.
     *
     * @param categoriaId identificador opcional de categoría
     * @return predicado que admite todas las publicaciones sin filtro o solo las de la categoría solicitada
     */
    private Predicate<Publicacion> coincideCategoria(Long categoriaId) {
        return publicacion -> categoriaId == null || categoriaId.equals(publicacion.getCategoria().getId());
    }

    /**
     * Crea el predicado del filtro opcional de subcategoría.
     *
     * @param subcategoriaId identificador opcional de subcategoría
     * @return predicado que admite todas las publicaciones sin filtro o solo las de la subcategoría solicitada
     */
    private Predicate<Publicacion> coincideSubcategoria(Long subcategoriaId) {
        return publicacion -> subcategoriaId == null || subcategoriaId.equals(publicacion.getSubcategoria().getId());
    }

    /**
     * Crea el predicado del límite mínimo inclusivo opcional.
     *
     * @param precioMinimo límite inferior opcional en centavos
     * @return predicado que admite precios iguales o mayores al límite, o todos si no existe límite
     */
    private Predicate<Publicacion> coincidePrecioMinimo(Long precioMinimo) {
        return publicacion -> precioMinimo == null || publicacion.getPrecio() >= precioMinimo;
    }

    /**
     * Crea el predicado del límite máximo inclusivo opcional.
     *
     * @param precioMaximo límite superior opcional en centavos
     * @return predicado que admite precios iguales o menores al límite, o todos si no existe límite
     */
    private Predicate<Publicacion> coincidePrecioMaximo(Long precioMaximo) {
        return publicacion -> precioMaximo == null || publicacion.getPrecio() <= precioMaximo;
    }

    /**
     * Aplica uno de los órdenes explícitos autorizados sin agregar un criterio de desempate.
     *
     * @param publicaciones publicaciones ya filtradas que se ordenarán en sitio
     * @param orden orden explícito solicitado
     */
    private void ordenar(List<Publicacion> publicaciones, OrdenListadoPublicaciones orden) {
        switch (orden) {
            case PRECIO_ASCENDENTE -> publicaciones.sort(Comparator.comparingLong(Publicacion::getPrecio));
            case PRECIO_DESCENDENTE -> publicaciones.sort(Comparator.comparingLong(Publicacion::getPrecio).reversed());
            case MAS_VENDIDO -> ordenarPorMasVendido(publicaciones);
        }
    }

    /**
     * Ordena por los conteos descendentes de ventas completadas definidos y ya consultados por PHA05TSK01.
     *
     * @param publicaciones publicaciones filtradas que se ordenarán por sus ventas completadas
     */
    private void ordenarPorMasVendido(List<Publicacion> publicaciones) {
        Map<Publicacion, Long> conteos = publicaciones.stream().collect(Collectors.toMap(
            publicacion -> publicacion,
            publicacion -> transaccionRepository.contarTransaccionesCompletadasPorPublicacion(publicacion.getId())
        ));
        publicaciones.sort(Comparator.comparingLong(conteos::get).reversed());
    }
}
