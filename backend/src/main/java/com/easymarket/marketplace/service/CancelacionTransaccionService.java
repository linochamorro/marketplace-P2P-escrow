package com.easymarket.marketplace.service;

import com.easymarket.marketplace.exception.ActorNoAutorizadoParaCancelarTransaccionException;
import com.easymarket.marketplace.exception.MotivoCancelacionObligatorioException;
import com.easymarket.marketplace.exception.PaymentIntentTransaccionNoEncontradoException;
import com.easymarket.marketplace.exception.TransaccionNoEncontradaException;
import com.easymarket.marketplace.exception.TransicionEstadoTransaccionInvalidaException;
import com.easymarket.marketplace.model.EstadoTransaccion;
import com.easymarket.marketplace.model.IdempotencyKey;
import com.easymarket.marketplace.model.Transaccion;
import com.easymarket.marketplace.model.TransaccionEvento;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.model.StripeRefundOutbox;
import com.easymarket.marketplace.repository.IdempotencyKeyRepository;
import com.easymarket.marketplace.repository.PublicacionRepository;
import com.easymarket.marketplace.repository.StripeRefundOutboxRepository;
import com.easymarket.marketplace.repository.TransaccionEventoRepository;
import com.easymarket.marketplace.repository.TransaccionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * Cancela transacciones permitidas y persiste atómicamente su devolución local y orden de reembolso Stripe.
 */
@Service
public class CancelacionTransaccionService {

    private final TransaccionRepository transaccionRepository;
    private final PublicacionRepository publicacionRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final TransaccionEventoRepository transaccionEventoRepository;
    private final StripeRefundOutboxRepository stripeRefundOutboxRepository;

    /**
     * Construye el servicio con los repositorios de sus efectos persistentes.
     *
     * @param transaccionRepository repositorio que bloquea y persiste la transacción
     * @param publicacionRepository repositorio que restaura atómicamente el stock
     * @param idempotencyKeyRepository repositorio de correlación con el PaymentIntent
     * @param transaccionEventoRepository repositorio del log append-only
     * @param stripeRefundOutboxRepository repositorio de la orden durable de reembolso
     */
    public CancelacionTransaccionService(TransaccionRepository transaccionRepository,
                                         PublicacionRepository publicacionRepository,
                                         IdempotencyKeyRepository idempotencyKeyRepository,
                                         TransaccionEventoRepository transaccionEventoRepository,
                                         StripeRefundOutboxRepository stripeRefundOutboxRepository) {
        this.transaccionRepository = transaccionRepository;
        this.publicacionRepository = publicacionRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.transaccionEventoRepository = transaccionEventoRepository;
        this.stripeRefundOutboxRepository = stripeRefundOutboxRepository;
    }

    /**
     * Cancela una reserva por comprador o vendedor, o un envío exclusivamente por vendedor.
     *
     * <p>La transacción bloqueada, la correlación Stripe, restauración de exactamente una unidad,
     * cambio de estado, motivo, auditoría append-only y orden PENDIENTE se manejan dentro de este
     * único límite transaccional. No llama a Stripe: su procesador pertenece a PHA04TSK25.</p>
     *
     * @param transaccionId ID de la transacción a cancelar
     * @param actorId ID del comprador o vendedor autorizado según el estado origen
     * @param motivo motivo obligatorio no nulo, no vacío y no compuesto solo por blancos
     * @return transacción persistida en estado {@code cancelada}
     * @throws MotivoCancelacionObligatorioException si el motivo no contiene texto
     * @throws TransaccionNoEncontradaException si no existe la transacción indicada
     * @throws TransicionEstadoTransaccionInvalidaException si no está reservada ni enviada
     * @throws ActorNoAutorizadoParaCancelarTransaccionException si el actor no puede cancelar ese estado
     * @throws PaymentIntentTransaccionNoEncontradoException si falta la correlación Stripe antes de escribir
     */
    @Transactional
    public Transaccion cancelar(Long transaccionId, Long actorId, String motivo) {
        if (motivo == null || motivo.trim().isEmpty()) {
            throw new MotivoCancelacionObligatorioException("El motivo de cancelación es obligatorio");
        }
        Transaccion transaccion = transaccionRepository.findByIdForUpdate(transaccionId)
            .orElseThrow(() -> new TransaccionNoEncontradaException("Transacción con ID " + transaccionId + " no encontrada"));
        EstadoTransaccion origen = transaccion.getEstado();
        if (origen != EstadoTransaccion.RESERVADA && origen != EstadoTransaccion.ENVIADO) {
            throw new TransicionEstadoTransaccionInvalidaException("Transición no permitida desde '" + origen + "' hacia 'CANCELADA'");
        }
        Usuario comprador = transaccion.getComprador();
        Usuario vendedor = transaccion.getPublicacion().getUsuario();
        boolean autorizado = origen == EstadoTransaccion.RESERVADA
            ? Objects.equals(comprador.getId(), actorId) || Objects.equals(vendedor.getId(), actorId)
            : Objects.equals(vendedor.getId(), actorId);
        if (!autorizado) {
            throw new ActorNoAutorizadoParaCancelarTransaccionException("El actor con ID " + actorId + " no puede cancelar la transacción " + transaccionId + " en estado " + origen);
        }
        IdempotencyKey key = idempotencyKeyRepository.findByTransaccionId(transaccionId)
            .filter(candidate -> candidate.getPaymentIntentId() != null && !candidate.getPaymentIntentId().isBlank())
            .orElseThrow(() -> new PaymentIntentTransaccionNoEncontradoException("No existe PaymentIntent para la transacción " + transaccionId));

        ZonedDateTime ahora = ZonedDateTime.now();
        publicacionRepository.incrementarStock(transaccion.getPublicacion().getId());
        transaccion.setEstado(EstadoTransaccion.CANCELADA);
        transaccion.setMotivoCancelacion(motivo);
        Transaccion persistida = transaccionRepository.save(transaccion);
        transaccionEventoRepository.save(new TransaccionEvento(persistida, Objects.equals(comprador.getId(), actorId) ? comprador : vendedor,
            origen, EstadoTransaccion.CANCELADA, motivo, ahora));
        stripeRefundOutboxRepository.save(new StripeRefundOutbox(persistida, key.getPaymentIntentId(), "refund:" + persistida.getId(), ahora));
        return persistida;
    }
}
