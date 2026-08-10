package com.easymarket.marketplace.service;

import com.easymarket.marketplace.exception.UsuarioNoEncontradoException;
import com.easymarket.marketplace.model.ConsultaSaldoResult;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.MovimientoSaldoRepository;
import com.easymarket.marketplace.repository.UsuarioRepository;
import org.springframework.stereotype.Service;

/**
 * Servicio de dominio que consulta el saldo cacheado y el detalle append-only de la Story 12.
 *
 * <p>Lee {@code saldo_disponible} en tiempo constante desde el usuario y obtiene sus movimientos
 * para auditoría. No agrega movimientos para producir el saldo, no modifica fondos ni corrige
 * inconsistencias entre el cache y el ledger.</p>
 */
@Service
public class ConsultaSaldoService {

    private final UsuarioRepository usuarioRepository;
    private final MovimientoSaldoRepository movimientoSaldoRepository;

    /**
     * Construye el servicio con los repositorios de usuario y movimientos de saldo.
     *
     * @param usuarioRepository repositorio que obtiene el saldo cacheado del vendedor
     * @param movimientoSaldoRepository repositorio que obtiene el detalle append-only del vendedor
     */
    public ConsultaSaldoService(UsuarioRepository usuarioRepository,
                                MovimientoSaldoRepository movimientoSaldoRepository) {
        this.usuarioRepository = usuarioRepository;
        this.movimientoSaldoRepository = movimientoSaldoRepository;
    }

    /**
     * Consulta el saldo cacheado y los movimientos append-only de un vendedor existente.
     *
     * <p>El saldo del resultado se copia directamente del usuario encontrado; los movimientos no
     * se suman, validan ni usan para modificar ese valor.</p>
     *
     * @param vendedorId identificador del vendedor que consulta su saldo
     * @return saldo cacheado en centavos y detalle de movimientos del vendedor
     * @throws UsuarioNoEncontradoException si no existe un usuario con el identificador indicado
     */
    public ConsultaSaldoResult consultarSaldo(Long vendedorId) {
        Usuario vendedor = usuarioRepository.findById(vendedorId)
            .orElseThrow(() -> new UsuarioNoEncontradoException(
                "Usuario vendedor con ID " + vendedorId + " no encontrado"));
        return new ConsultaSaldoResult(vendedor.getSaldoDisponible(),
            movimientoSaldoRepository.findByVendedorId(vendedorId));
    }
}
