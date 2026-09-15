package com.easymarket.marketplace.repository;

import com.easymarket.marketplace.model.MovimientoSaldo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repositorio JPA para insertar movimientos append-only de saldo de vendedores.
 *
 * <p>Los servicios de dominio lo usan dentro de la misma transacción que el cambio del saldo
 * cacheado y la transición que originó el movimiento.</p>
 */
@Repository
public interface MovimientoSaldoRepository extends JpaRepository<MovimientoSaldo, Long> {

    /**
     * Obtiene los movimientos append-only asociados a un vendedor.
     *
     * <p>La consulta no declara orden porque Story 12 y su contrato de dominio no lo definen. El
     * saldo cacheado se obtiene independientemente desde {@code usuarios.saldo_disponible}.</p>
     *
     * @param vendedorId identificador del vendedor cuyos movimientos se consultan
     * @return movimientos asociados exclusivamente al vendedor indicado, sin orden contractual
     */
    List<MovimientoSaldo> findByVendedorId(Long vendedorId);

    /**
     * Sums the amount of every positive movement in the append-only ledger.
     *
     * <p>Read-only aggregate for {@code GET /admin/tablero} (PHA06TSK07; plan.md, "Tablero
     * administrativo", {@code fondosLiberadosCentavos}): exactly the movements whose
     * {@code monto > 0} are added; zero and negative movements do not contribute.
     * {@code COALESCE} turns an empty sum into {@code 0} so the DTO never receives
     * {@code null} money (constitution, principle 3: integer cents, never null). The
     * aggregation is read-only; the ledger is neither recalculated nor modified.</p>
     *
     * @return total released funds in integer cents, zero when no positive movement exists
     */
    @Query("select coalesce(sum(m.monto), 0L) from MovimientoSaldo m where m.monto > 0")
    long sumarMovimientosPositivos();
}
