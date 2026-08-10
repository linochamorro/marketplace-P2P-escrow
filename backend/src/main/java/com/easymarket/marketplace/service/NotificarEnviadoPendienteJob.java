package com.easymarket.marketplace.service;

import com.easymarket.marketplace.model.AvisoEnvioPendiente;
import com.easymarket.marketplace.model.Notificacion;
import com.easymarket.marketplace.model.Transaccion;
import com.easymarket.marketplace.repository.AvisoEnvioPendienteRepository;
import com.easymarket.marketplace.repository.NotificacionRepository;
import com.easymarket.marketplace.repository.TransaccionRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Creates the one-time in-app warning for a transaction that remains reserved for more than 48
 * hours without shipment.
 *
 * <p>Each execution locks eligible rows with PostgreSQL {@code FOR UPDATE SKIP LOCKED} and, in
 * the same database transaction, stores the notification and its V13 idempotency marker. The job
 * intentionally does not transition the transaction, alter stock, write a transaction event, or
 * perform any refund action.</p>
 */
@Component
public class NotificarEnviadoPendienteJob {

    /** Peru time zone mandated for all 48-hour business deadlines. */
    private static final ZoneId PERU_ZONE = ZoneId.of("America/Lima");

    /** Stable type mandated by plan.md for this warning. */
    private static final String TIPO_ENVIO_PENDIENTE_48H = "ENVIO_PENDIENTE_48H";

    /** Literal buyer-facing message mandated by plan.md for this warning. */
    private static final String MENSAJE_ENVIO_PENDIENTE_48H =
        "Tu compra permanece sin envío tras 48 horas. Puedes esperar o cancelarla.";

    /** Repository selecting eligible reserved transactions under a row lock. */
    private final TransaccionRepository transaccionRepository;

    /** Repository persisting the in-app notification projection. */
    private final NotificacionRepository notificacionRepository;

    /** Repository persisting and checking the one-time warning marker. */
    private final AvisoEnvioPendienteRepository avisoEnvioPendienteRepository;

    /**
     * Creates the scheduled pending-shipment warning job.
     *
     * @param transaccionRepository repository that locks expired reservations
     * @param notificacionRepository repository that persists in-app notifications
     * @param avisoEnvioPendienteRepository repository that persists idempotency markers
     */
    public NotificarEnviadoPendienteJob(TransaccionRepository transaccionRepository,
                                        NotificacionRepository notificacionRepository,
                                        AvisoEnvioPendienteRepository avisoEnvioPendienteRepository) {
        this.transaccionRepository = transaccionRepository;
        this.notificacionRepository = notificacionRepository;
        this.avisoEnvioPendienteRepository = avisoEnvioPendienteRepository;
    }

    /**
     * Polls every 15 minutes for reservations older than 48 complete hours in Peru time.
     *
     * <p>The cadence is within the 15--30 minute interval in plan.md and does not relax the
     * strict eligibility threshold. The transaction boundary retains each row lock through the
     * notification and marker insertion.</p>
     */
    @Scheduled(fixedDelayString = "PT15M")
    @Transactional
    public void ejecutar() {
        ZonedDateTime ahora = ZonedDateTime.now(PERU_ZONE);
        ZonedDateTime limiteExclusivo = ahora.minusHours(48);
        List<Transaccion> transacciones = transaccionRepository
            .findReservadasVencidasForUpdateSkipLocked(limiteExclusivo);

        for (Transaccion transaccion : transacciones) {
            notificarSiNoFueAvisada(transaccion, ahora);
        }
    }

    /**
     * Creates the notification and its marker when the locked transaction has not already been
     * warned in an earlier job execution.
     *
     * @param transaccion eligible transaction locked by {@link #ejecutar()}
     * @param ahora instant recorded on both rows
     */
    private void notificarSiNoFueAvisada(Transaccion transaccion, ZonedDateTime ahora) {
        if (avisoEnvioPendienteRepository.existsById(transaccion.getId())) {
            return;
        }
        Notificacion notificacion = notificacionRepository.save(new Notificacion(
            transaccion.getComprador(), MENSAJE_ENVIO_PENDIENTE_48H, TIPO_ENVIO_PENDIENTE_48H, ahora));
        avisoEnvioPendienteRepository.save(new AvisoEnvioPendiente(transaccion, notificacion, ahora));
    }
}
