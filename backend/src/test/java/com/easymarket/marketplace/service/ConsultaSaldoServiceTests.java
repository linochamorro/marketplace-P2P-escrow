package com.easymarket.marketplace.service;

import com.easymarket.marketplace.exception.UsuarioNoEncontradoException;
import com.easymarket.marketplace.model.ConsultaSaldoResult;
import com.easymarket.marketplace.model.MovimientoSaldo;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.MovimientoSaldoRepository;
import com.easymarket.marketplace.repository.UsuarioRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias de la consulta de saldo cacheado y su detalle append-only de la Story 12.
 *
 * <p>Las pruebas exigen que el saldo provenga del campo cacheado del usuario incluso cuando difiere
 * del ledger. También verifican que el detalle se solicite solo para ese vendedor, sin imponer un
 * orden no definido por el contrato ni producir escrituras.</p>
 */
@ExtendWith(MockitoExtension.class)
class ConsultaSaldoServiceTests {

    private static final Long VENDEDOR_ID = 10L;
    private static final Long OTRO_VENDEDOR_ID = 20L;

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private MovimientoSaldoRepository movimientoSaldoRepository;

    /** Verifica que se devuelve el cache y solo los movimientos del vendedor consultado. */
    @Test
    @DisplayName("Devuelve saldo cacheado y detalle exclusivo del vendedor en escenario consistente")
    void consultarSaldo_UsuarioExistente_DevuelveCacheYMovimientosPropios() {
        Usuario vendedor = usuario(VENDEDOR_ID, 15_000L);
        MovimientoSaldo primerMovimiento = movimiento(vendedor, 5_000L);
        MovimientoSaldo segundoMovimiento = movimiento(vendedor, 10_000L);
        Usuario otroVendedor = usuario(OTRO_VENDEDOR_ID, 900L);
        MovimientoSaldo movimientoAjeno = movimiento(otroVendedor, 900L);
        List<MovimientoSaldo> movimientosPropios = List.of(primerMovimiento, segundoMovimiento);
        when(usuarioRepository.findById(VENDEDOR_ID)).thenReturn(Optional.of(vendedor));
        when(movimientoSaldoRepository.findByVendedorId(VENDEDOR_ID)).thenReturn(movimientosPropios);

        ConsultaSaldoResult resultado = service().consultarSaldo(VENDEDOR_ID);

        long sumaMovimientos = movimientosPropios.stream().mapToLong(MovimientoSaldo::getMonto).sum();
        assertThat(sumaMovimientos).isEqualTo(vendedor.getSaldoDisponible());
        assertThat(resultado.saldoDisponible()).isEqualTo(vendedor.getSaldoDisponible());
        assertThat(resultado.movimientos()).containsExactlyInAnyOrder(primerMovimiento, segundoMovimiento);
        assertThat(resultado.movimientos()).doesNotContain(movimientoAjeno);
        verify(movimientoSaldoRepository).findByVendedorId(VENDEDOR_ID);
    }

    /** Verifica que el cache prevalece sobre un ledger deliberadamente discrepante sin escrituras. */
    @Test
    @DisplayName("Conserva el saldo cacheado discrepante sin recalcularlo ni escribir")
    void consultarSaldo_CacheDifiereDeMovimientos_ConservaCacheYNoEscribe() {
        Usuario vendedor = usuario(VENDEDOR_ID, 15_000L);
        MovimientoSaldo primerMovimiento = movimiento(vendedor, 5_000L);
        MovimientoSaldo segundoMovimiento = movimiento(vendedor, 2_000L);
        List<MovimientoSaldo> movimientos = List.of(primerMovimiento, segundoMovimiento);
        when(usuarioRepository.findById(VENDEDOR_ID)).thenReturn(Optional.of(vendedor));
        when(movimientoSaldoRepository.findByVendedorId(VENDEDOR_ID)).thenReturn(movimientos);

        ConsultaSaldoResult resultado = service().consultarSaldo(VENDEDOR_ID);

        assertThat(movimientos.stream().mapToLong(MovimientoSaldo::getMonto).sum()).isEqualTo(7_000L);
        assertThat(resultado.saldoDisponible()).isEqualTo(15_000L);
        assertThat(resultado.movimientos()).containsExactly(primerMovimiento, segundoMovimiento);
        verify(usuarioRepository).findById(VENDEDOR_ID);
        verify(movimientoSaldoRepository).findByVendedorId(VENDEDOR_ID);
        verifyNoMoreInteractions(usuarioRepository, movimientoSaldoRepository);
    }

    /** Verifica que un vendedor inexistente conserva la excepción específica reutilizable. */
    @Test
    @DisplayName("Rechaza la consulta de un usuario inexistente con excepción específica")
    void consultarSaldo_UsuarioInexistente_LanzaUsuarioNoEncontradoException() {
        when(usuarioRepository.findById(VENDEDOR_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().consultarSaldo(VENDEDOR_ID))
            .isInstanceOf(UsuarioNoEncontradoException.class);

        verify(movimientoSaldoRepository, never()).findByVendedorId(VENDEDOR_ID);
    }

    /**
     * Construye el servicio bajo prueba con repositorios simulados.
     *
     * @return servicio de consulta configurado para esta prueba
     */
    private ConsultaSaldoService service() {
        return new ConsultaSaldoService(usuarioRepository, movimientoSaldoRepository);
    }

    /**
     * Construye un usuario con un saldo cacheado determinado.
     *
     * @param id identificador persistido simulado del usuario
     * @param saldoDisponible saldo cacheado en centavos
     * @return usuario con la identidad y saldo indicados
     */
    private Usuario usuario(Long id, long saldoDisponible) {
        Usuario usuario = new Usuario();
        usuario.setId(id);
        usuario.setSaldoDisponible(saldoDisponible);
        return usuario;
    }

    /**
     * Construye un movimiento de saldo asociado al vendedor indicado.
     *
     * @param vendedor vendedor asociado al movimiento
     * @param monto monto entero en centavos del movimiento
     * @return movimiento de saldo simulado
     */
    private MovimientoSaldo movimiento(Usuario vendedor, long monto) {
        return new MovimientoSaldo(null, vendedor, monto, null);
    }
}
