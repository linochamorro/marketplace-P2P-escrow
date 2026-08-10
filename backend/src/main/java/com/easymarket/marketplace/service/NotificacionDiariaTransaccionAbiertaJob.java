package com.easymarket.marketplace.service;

import com.easymarket.marketplace.model.Notificacion;
import com.easymarket.marketplace.model.EstadoTransaccion;
import com.easymarket.marketplace.model.Transaccion;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.NotificacionRepository;
import com.easymarket.marketplace.repository.TransaccionEventoRepository;
import com.easymarket.marketplace.repository.TransaccionRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Creates recurrent daily in-app notices for both participants of transactions in Story 7b's open
 * states.
 *
 * <p>Each execution locks open transactions with PostgreSQL {@code FOR UPDATE SKIP LOCKED}. While
 * retaining that lock it checks the latest daily notice independently for the buyer and seller,
 * then inserts only notices older than 24 complete hours. The job does not change transaction
 * state, stock, funds, balances, refunds, outbox rows, or canonical transaction events.</p>
 */
@Component
public class NotificacionDiariaTransaccionAbiertaJob {

    /** Peru time zone mandated for all business time calculations. */
    private static final ZoneId PERU_ZONE = ZoneId.of("America/Lima");

    /** Strict recurring interval mandated by Story 7b. */
    private static final long INTERVALO_HORAS = 24L;

    /** Buyer notification type mandated by the closed Story 7b contract. */
    private static final String TIPO_COMPRA_PENDIENTE_DIARIA = "COMPRA_PENDIENTE_DIARIA";

    /** Seller notification type mandated by the closed Story 7b contract. */
    private static final String TIPO_VENTA_POR_ENTREGAR_DIARIA = "VENTA_POR_ENTREGAR_DIARIA";

    /** Literal buyer message mandated by the closed Story 7b contract. */
    private static final String MENSAJE_COMPRA_PENDIENTE_DIARIA = "COMPRAS PENDIENTES";

    /** Literal seller message mandated by the closed Story 7b contract. */
    private static final String MENSAJE_VENTA_POR_ENTREGAR_DIARIA = "VENTAS POR ENTREGAR";

    /** Repository selecting open transactions under PostgreSQL row locks. */
    private final TransaccionRepository transaccionRepository;

    /** Repository reading and persisting transaction-associated notification projections. */
    private final NotificacionRepository notificacionRepository;

    /** Repository reading the append-only entry timestamp of disputed transactions. */
    private final TransaccionEventoRepository transaccionEventoRepository;

    /**
     * Creates the scheduled daily open-transaction notification job.
     *
     * @param transaccionRepository repository that locks open transactions
     * @param notificacionRepository repository that finds and persists daily notices
     * @param transaccionEventoRepository repository that resolves the entry event for disputes
     */
    public NotificacionDiariaTransaccionAbiertaJob(TransaccionRepository transaccionRepository,
                                                    NotificacionRepository notificacionRepository,
                                                    TransaccionEventoRepository transaccionEventoRepository) {
        this.transaccionRepository = transaccionRepository;
        this.notificacionRepository = notificacionRepository;
        this.transaccionEventoRepository = transaccionEventoRepository;
    }

    /**
     * Polls daily for open transactions and creates an eligible recurrent notice for each party.
     *
     * <p>The transaction boundary retains every selected transaction lock while the job reads the
     * latest buyer and seller notices and writes any eligible replacement. A timestamp exactly 24
     * hours old is deliberately not eligible; a full interval must have strictly elapsed.</p>
     */
    @Scheduled(fixedDelayString = "P1D")
    @Transactional
    public void ejecutar() {
        ZonedDateTime ahora = ZonedDateTime.now(PERU_ZONE);
        List<Transaccion> transacciones = transaccionRepository.findAbiertasForUpdateSkipLocked();
        for (Transaccion transaccion : transacciones) {
            notificarParticipantesSiCorresponde(transaccion, ahora);
        }
    }

    /**
     * Creates independently eligible daily notices for the locked transaction's buyer and seller.
     *
     * @param transaccion open transaction locked by {@link #ejecutar()}
     * @param ahora instant shared by every notice created in this job execution
     */
    private void notificarParticipantesSiCorresponde(Transaccion transaccion, ZonedDateTime ahora) {
        notificarSiCorresponde(transaccion, transaccion.getComprador(), TIPO_COMPRA_PENDIENTE_DIARIA,
            MENSAJE_COMPRA_PENDIENTE_DIARIA, ahora);
        notificarSiCorresponde(transaccion, transaccion.getPublicacion().getUsuario(),
            TIPO_VENTA_POR_ENTREGAR_DIARIA, MENSAJE_VENTA_POR_ENTREGAR_DIARIA, ahora);
    }

    /**
     * Creates a daily notice after the first 24 complete hours in the current open state when no
     * prior matching notice exists, or after 24 complete hours from the recipient's latest notice.
     *
     * @param transaccion locked transaction associated with the notice
     * @param destinatario recipient whose notice interval is evaluated independently
     * @param tipo stable daily notification category for the recipient role
     * @param mensaje literal daily message for the recipient role
     * @param ahora instant used as the eligibility reference and creation timestamp
     */
    private void notificarSiCorresponde(Transaccion transaccion, Usuario destinatario, String tipo,
                                        String mensaje, ZonedDateTime ahora) {
        ZonedDateTime limiteExclusivo = ahora.minusHours(INTERVALO_HORAS);
        boolean avisoVencido = notificacionRepository
            .findFirstByTransaccion_IdAndUsuario_IdAndTipoOrderByCreatedAtDesc(
                transaccion.getId(), destinatario.getId(), tipo)
            .map(ultimoAviso -> ultimoAviso.getCreatedAt().isBefore(limiteExclusivo))
            .orElseGet(() -> fechaEntradaEstadoAbierto(transaccion)
                .map(fechaEntrada -> fechaEntrada.isBefore(limiteExclusivo))
                .orElse(false));
        if (avisoVencido) {
            notificacionRepository.save(new Notificacion(destinatario, transaccion, mensaje, tipo, ahora));
        }
    }

    /**
     * Resolves the mandated entry timestamp for the transaction's current open state.
     *
     * <p>{@code disputa} deliberately reads the existing append-only event rather than introducing
     * a {@code fecha_disputa} column. An absent dispute-entry event is ineligible: without the
     * canonical timestamp, the job cannot prove that 24 complete hours elapsed.</p>
     *
     * @param transaccion locked transaction whose current state is open
     * @return entry timestamp for the current state, or empty if its mandated source is absent
     */
    private java.util.Optional<ZonedDateTime> fechaEntradaEstadoAbierto(Transaccion transaccion) {
        return switch (transaccion.getEstado()) {
            case RESERVADA -> java.util.Optional.of(transaccion.getFechaReservada());
            case ENVIADO -> java.util.Optional.ofNullable(transaccion.getFechaEnviado());
            case ENTREGADO -> java.util.Optional.ofNullable(transaccion.getFechaEntregado());
            case DISPUTA -> transaccionEventoRepository
                .findFirstByTransaccion_IdAndEstadoDestinoOrderByCreatedAtDesc(
                    transaccion.getId(), EstadoTransaccion.DISPUTA)
                .map(evento -> evento.getCreatedAt());
            default -> java.util.Optional.empty();
        };
    }
}
