package com.easymarket.marketplace.service;

import com.easymarket.marketplace.exception.AutoCompraNoPermitidaException;
import com.easymarket.marketplace.exception.PublicacionNoEncontradaException;
import com.easymarket.marketplace.exception.StockAgotadoException;
import com.easymarket.marketplace.model.Publicacion;
import com.easymarket.marketplace.model.Rol;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.PublicacionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias para {@link ValidacionCompraService}.
 *
 * <p>Verifica las reglas de negocio de validación de compra de la Story 5 de {@code spec.md}
 * (tarea PHA03TSK03 de {@code tasks.md}):
 * <ul>
 *   <li>Rechazo de auto-compra: el dueño de la publicación no puede comprarla — cláusula
 *       "Dado que el usuario es dueño de la publicación, cuando intenta comprarla, entonces
 *       el sistema rechaza la operación" y regla de actores
 *       {@code transacción.comprador_id != publicación.usuario_id} (no auto-compra).</li>
 *   <li>Validación de stock disponible &ge; 1: stock 0 rechaza — cláusula "Dado que hay stock
 *       disponible ≥ 1, cuando se confirma la compra, entonces el stock disponible se reduce
 *       en 1" (el decremento atómico es PHA03TSK04; aquí solo se valida la precondición).</li>
 *   <li>Carga de la publicación por ID con {@link PublicacionNoEncontradaException} si no existe
 *       (reutiliza {@link PublicacionRepository#findById}, sin métodos nuevos).</li>
 *   <li>Caso positivo: comprador distinto al dueño con stock &ge; 1 no lanza excepción.</li>
 * </ul>
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class ValidacionCompraServiceTests {

    @Mock
    private PublicacionRepository publicacionRepository;

    @InjectMocks
    private ValidacionCompraService validacionCompraService;

    /**
     * Verifica que el dueño de la publicación no puede comprarla: lanza
     * {@link AutoCompraNoPermitidaException} cuando el comprador es el propietario
     * (regla {@code comprador_id != publicacion.usuario_id}, Story 5, spec.md).
     */
    @Test
    @DisplayName("Debe lanzar AutoCompraNoPermitidaException cuando el comprador es el dueño de la publicación")
    void validarCompra_CompradorEsElDuenio_LanzaAutoCompraNoPermitidaException() {
        Usuario duenio = new Usuario("duenio@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now());
        duenio.setId(1L);

        Publicacion publicacion = new Publicacion();
        publicacion.setId(100L);
        publicacion.setUsuario(duenio);
        publicacion.setStock(3);

        when(publicacionRepository.findById(100L)).thenReturn(Optional.of(publicacion));

        assertThatThrownBy(() -> validacionCompraService.validarCompra(1L, 100L))
            .isInstanceOf(AutoCompraNoPermitidaException.class)
            .hasMessageContaining("no puede comprar su propia publicación");
    }

    /**
     * Verifica que una compra sobre una publicación sin stock disponible (stock = 0) se rechaza
     * con {@link StockAgotadoException}: la validación exige stock &ge; 1 (Story 5, spec.md;
     * plan.md, "Flujo de compra y reserva de stock (PHA03)").
     */
    @Test
    @DisplayName("Debe lanzar StockAgotadoException cuando la publicación tiene stock 0")
    void validarCompra_StockCero_LanzaStockAgotadoException() {
        Usuario vendedor = new Usuario("vendedor@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now());
        vendedor.setId(1L);

        Publicacion publicacion = new Publicacion();
        publicacion.setId(100L);
        publicacion.setUsuario(vendedor);
        publicacion.setStock(0);

        when(publicacionRepository.findById(100L)).thenReturn(Optional.of(publicacion));

        assertThatThrownBy(() -> validacionCompraService.validarCompra(2L, 100L))
            .isInstanceOf(StockAgotadoException.class)
            .hasMessageContaining("no tiene stock disponible");
    }

    /**
     * Verifica que la validación permite la compra cuando el comprador no es el dueño y la
     * publicación tiene stock &ge; 1 (cláusula positiva de Story 5: "el usuario autenticado no
     * es el dueño de la publicación ... se crea una transacción"; aquí solo la precondición).
     */
    @Test
    @DisplayName("Debe permitir la compra cuando el comprador no es el dueño y hay stock >= 1")
    void validarCompra_CompradorDistintoConStockDisponible_NoLanzaExcepcion() {
        Usuario vendedor = new Usuario("vendedor2@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now());
        vendedor.setId(1L);

        Publicacion publicacion = new Publicacion();
        publicacion.setId(200L);
        publicacion.setUsuario(vendedor);
        publicacion.setStock(5);

        when(publicacionRepository.findById(200L)).thenReturn(Optional.of(publicacion));

        assertThatCode(() -> validacionCompraService.validarCompra(2L, 200L))
            .doesNotThrowAnyException();
    }

    /**
     * Verifica que la validación lanza {@link PublicacionNoEncontradaException} cuando la
     * publicación indicada no existe (mismo mensaje que {@code PublicacionService}).
     */
    @Test
    @DisplayName("Debe lanzar PublicacionNoEncontradaException cuando la publicación no existe")
    void validarCompra_PublicacionInexistente_LanzaPublicacionNoEncontradaException() {
        when(publicacionRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> validacionCompraService.validarCompra(2L, 999L))
            .isInstanceOf(PublicacionNoEncontradaException.class)
            .hasMessageContaining("Publicación con ID 999 no encontrada");
    }
}
