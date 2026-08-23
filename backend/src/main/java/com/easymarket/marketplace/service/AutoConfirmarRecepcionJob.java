package com.easymarket.marketplace.service;

import com.easymarket.marketplace.model.EstadoTransaccion;
import com.easymarket.marketplace.model.MovimientoSaldo;
import com.easymarket.marketplace.model.Transaccion;
import com.easymarket.marketplace.model.TransaccionEvento;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.MovimientoSaldoRepository;
import com.easymarket.marketplace.repository.TransaccionEventoRepository;
import com.easymarket.marketplace.repository.TransaccionRepository;
import com.easymarket.marketplace.repository.UsuarioRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Automatically confirms delivered transactions after the buyer has taken no action for 48 hours.
 *
 * <p>The job polls every 15 minutes, the shortest frequency permitted by plan.md for this timer.
 * One transaction spans row selection with PostgreSQL {@code FOR UPDATE SKIP LOCKED}, the cached
 * seller balance credit, its append-only ledger row, the state transition, and its append-only
 * system event. Since PHA12TSK03 (recovery of PHA09TSK05) it also emits, within that same
 * transaction, the actionable COMPRA_CONFIRMADA notification to both buyer and seller with
 * role-directed messages ("tu compra"/"tu venta") that the UI routes. It does not alter stock
 * because PHA03 discounted it at reservation time.</p>
 */
@Component
public class AutoConfirmarRecepcionJob {

    private final TransaccionRepository transaccionRepository;
    private final UsuarioRepository usuarioRepository;
    private final MovimientoSaldoRepository movimientoSaldoRepository;
    private final TransaccionEventoRepository transaccionEventoRepository;
    private final NotificacionService notificacionService;

    /**
     * Creates the scheduled automatic reception-confirmation job.
     *
     * @param transaccionRepository repository that locks expired delivered transactions
     * @param usuarioRepository repository that atomically increments the cached seller balance
     * @param movimientoSaldoRepository repository that appends seller ledger credits
     * @param transaccionEventoRepository repository that appends system transition events
     * @param notificacionService actionable-notification domain service (PHA09TSK05)
     */
    public AutoConfirmarRecepcionJob(TransaccionRepository transaccionRepository,
                                     UsuarioRepository usuarioRepository,
                                     MovimientoSaldoRepository movimientoSaldoRepository,
                                     TransaccionEventoRepository transaccionEventoRepository,
                                     NotificacionService notificacionService) {
        this.transaccionRepository = transaccionRepository;
        this.usuarioRepository = usuarioRepository;
        this.movimientoSaldoRepository = movimientoSaldoRepository;
        this.transaccionEventoRepository = transaccionEventoRepository;
        this.notificacionService = notificacionService;
    }

    /**
     * Runs the automatic confirmation poll every 15 minutes.
     *
     * <p>Fifteen minutes is within the 15--30 minute range selected in plan.md. The interval is a
     * polling cadence, not a relaxation of the strict 48-hour eligibility limit.</p>
     */
    @Scheduled(fixedDelayString = "PT15M")
    @Transactional
    public void ejecutar() {
        ZonedDateTime limiteExclusivo = ZonedDateTime.now().minusHours(48);
        List<Transaccion> transacciones = transaccionRepository
            .findEntregadasVencidasForUpdateSkipLocked(limiteExclusivo);

        for (Transaccion transaccion : transacciones) {
            acreditarYAutoConfirmar(transaccion, ZonedDateTime.now());
        }
    }

    /**
     * Persists every financial and audit effect of one transaction already locked as eligible and
     * emits the actionable COMPRA_CONFIRMADA notification to both parties.
     *
     * @param transaccion delivered transaction locked by {@link #ejecutar()}
     * @param ahora instant recorded on the append-only records
     */
    private void acreditarYAutoConfirmar(Transaccion transaccion, ZonedDateTime ahora) {
        Usuario vendedor = transaccion.getPublicacion().getUsuario();
        long monto = transaccion.getPrecioSnapshot();
        usuarioRepository.incrementarSaldoDisponible(vendedor.getId(), monto);
        transaccion.setEstado(EstadoTransaccion.RECIBIDO_SIN_RESPUESTA);
        Transaccion persistida = transaccionRepository.save(transaccion);
        movimientoSaldoRepository.save(new MovimientoSaldo(persistida, vendedor, monto, ahora));
        transaccionEventoRepository.save(new TransaccionEvento(persistida, null,
            EstadoTransaccion.ENTREGADO, EstadoTransaccion.RECIBIDO_SIN_RESPUESTA, null, ahora));

        // Notificaciones accionables a comprador y vendedor (PHA09TSK05, recuperado en PHA12TSK03),
        // emitidas dentro de la misma transacción del job (constitution, principio 1).
        notificacionService.crearNotificacionUsuario(
            persistida.getComprador(),
            "COMPRA_CONFIRMADA",
            "Se confirmó automáticamente tu compra #" + persistida.getId()
                + " al cumplirse 48 horas sin acción del comprador",
            persistida,
            ahora);
        notificacionService.crearNotificacionUsuario(
            vendedor,
            "COMPRA_CONFIRMADA",
            "El comprador no respondió en 48 horas; tu venta #" + persistida.getId()
                + " fue confirmada automáticamente y el saldo fue acreditado",
            persistida,
            ahora);
    }
}
