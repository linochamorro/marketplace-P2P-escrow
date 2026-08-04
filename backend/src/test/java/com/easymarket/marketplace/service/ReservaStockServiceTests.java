package com.easymarket.marketplace.service;

import com.easymarket.marketplace.exception.StockAgotadoException;
import com.easymarket.marketplace.model.EstadoTransaccion;
import com.easymarket.marketplace.model.Publicacion;
import com.easymarket.marketplace.model.Rol;
import com.easymarket.marketplace.model.Transaccion;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.PublicacionRepository;
import com.easymarket.marketplace.repository.TransaccionRepository;
import com.easymarket.marketplace.repository.UsuarioRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias para {@link ReservaStockService}.
 *
 * <p>Verifica la lógica pura del servicio de reserva atómica de stock de la Story 5 de
 * {@code spec.md} (tarea PHA03TSK04 de {@code tasks.md}), mockeando los repositorios
 * (sin base de datos):
 * <ul>
 *   <li>Camino feliz: con stock disponible el decremento condicional afecta 1 fila, se toma el
 *       snapshot de precio de la publicación y se persiste una transacción en estado
 *       {@code reservada} con ese snapshot (cláusulas "el stock disponible se reduce en 1 de forma
 *       atómica junto con la creación de la transacción" y "el precio se fija como snapshot
 *       inmutable tomado de la publicación en ese instante" de Story 5).</li>
 *   <li>Camino de rechazo: si el decremento condicional no afecta ninguna fila (stock agotado),
 *       se lanza {@link StockAgotadoException} y NO se persiste ninguna transacción (cláusula
 *       "solo la de timestamp más temprano obtiene la reserva; la otra es rechazada por falta de
 *       stock").</li>
 * </ul>
 * La concurrencia real (carrera sobre la última unidad) se cubre en
 * {@link ReservaStockServiceIntegrationTests} contra PostgreSQL real con Testcontainers; aquí
 * solo se verifica la lógica de decisión del servicio en aislamiento.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class ReservaStockServiceTests {

    @Mock
    private PublicacionRepository publicacionRepository;

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private TransaccionRepository transaccionRepository;

    @InjectMocks
    private ReservaStockService reservaStockService;

    /**
     * Verifica que una reserva con stock disponible descuenta el stock de forma atómica
     * (decremento condicional que afecta 1 fila) y crea la transacción en estado
     * {@code reservada} con el {@code precio_snapshot} inmutable tomado de la publicación
     * (Story 5, spec.md).
     */
    @Test
    @DisplayName("Debe reservar stock y crear transacción 'reservada' con snapshot de precio de la publicación")
    void reservarStock_StockDisponible_CreaTransaccionReservadaConSnapshotDePrecio() {
        Usuario vendedor = new Usuario("vendedor@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now());
        vendedor.setId(1L);
        Usuario comprador = new Usuario("comprador@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now());
        comprador.setId(2L);

        Publicacion publicacion = new Publicacion();
        publicacion.setId(100L);
        publicacion.setUsuario(vendedor);
        publicacion.setStock(1);
        publicacion.setPrecio(250000L);

        when(publicacionRepository.decrementarStockSiDisponible(100L)).thenReturn(1);
        when(publicacionRepository.findById(100L)).thenReturn(Optional.of(publicacion));
        when(usuarioRepository.getReferenceById(2L)).thenReturn(comprador);
        // Replica el contrato real de JpaRepository.save: retorna la entidad persistida (la misma
        // instancia con su id asignado), en lugar del default null de Mockito.
        when(transaccionRepository.save(any(Transaccion.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Transaccion transaccion = reservaStockService.reservarStock(2L, 100L);

        // La entidad retornada por el servicio es la misma que se persiste con los valores correctos.
        assertThat(transaccion.getEstado()).isEqualTo(EstadoTransaccion.RESERVADA);
        assertThat(transaccion.getPrecioSnapshot()).isEqualTo(250000L);
        assertThat(transaccion.getComprador().getId()).isEqualTo(2L);
        assertThat(transaccion.getPublicacion().getId()).isEqualTo(100L);
        assertThat(transaccion.getFechaReservada()).isNotNull();

        ArgumentCaptor<Transaccion> captor = ArgumentCaptor.forClass(Transaccion.class);
        verify(transaccionRepository).save(captor.capture());
        Transaccion guardada = captor.getValue();
        assertThat(guardada.getEstado()).isEqualTo(EstadoTransaccion.RESERVADA);
        assertThat(guardada.getPrecioSnapshot()).isEqualTo(250000L);
        assertThat(guardada.getFechaReservada()).isNotNull();

        verify(publicacionRepository).decrementarStockSiDisponible(100L);
    }

    /**
     * Verifica que cuando el decremento condicional no afecta ninguna fila (stock agotado, el
     * perdedor de la carrera sobre la última unidad), el servicio lanza
     * {@link StockAgotadoException} sin crear ninguna transacción (Story 5, spec.md: "la otra es
     * rechazada por falta de stock"; plan.md: "Si al procesar payment_intent.succeeded el
     * decremento atómico WHERE stock>=1 falla, NO se crea la transacción").
     */
    @Test
    @DisplayName("Debe lanzar StockAgotadoException sin crear transacción cuando el decremento condicional no afecta filas")
    void reservarStock_StockAgotado_LanzaStockAgotadoExceptionSinCrearTransaccion() {
        when(publicacionRepository.decrementarStockSiDisponible(100L)).thenReturn(0);

        assertThatThrownBy(() -> reservaStockService.reservarStock(2L, 100L))
            .isInstanceOf(StockAgotadoException.class)
            .hasMessageContaining("no tiene stock disponible");

        verify(transaccionRepository, never()).save(any(Transaccion.class));
        verify(publicacionRepository, never()).findById(100L);
        verify(usuarioRepository, never()).getReferenceById(2L);
    }
}
