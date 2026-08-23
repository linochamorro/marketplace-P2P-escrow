package com.easymarket.marketplace.service;

import com.easymarket.marketplace.exception.DecisionResolucionDisputaInvalidaException;
import com.easymarket.marketplace.exception.MotivoResolucionDisputaObligatorioException;
import com.easymarket.marketplace.exception.PaymentIntentTransaccionNoEncontradoException;
import com.easymarket.marketplace.exception.TransaccionNoEncontradaException;
import com.easymarket.marketplace.exception.TransicionEstadoTransaccionInvalidaException;
import com.easymarket.marketplace.exception.UsuarioNoEncontradoException;
import com.easymarket.marketplace.model.EstadoTransaccion;
import com.easymarket.marketplace.model.IdempotencyKey;
import com.easymarket.marketplace.model.MovimientoSaldo;
import com.easymarket.marketplace.model.ResolucionDisputa;
import com.easymarket.marketplace.model.StripeRefundOutbox;
import com.easymarket.marketplace.model.Transaccion;
import com.easymarket.marketplace.model.TransaccionEvento;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.IdempotencyKeyRepository;
import com.easymarket.marketplace.repository.MovimientoSaldoRepository;
import com.easymarket.marketplace.repository.PublicacionRepository;
import com.easymarket.marketplace.repository.StripeRefundOutboxRepository;
import com.easymarket.marketplace.repository.TransaccionEventoRepository;
import com.easymarket.marketplace.repository.TransaccionRepository;
import com.easymarket.marketplace.repository.UsuarioRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;

/**
 * Resuelve de forma binaria una disputa administrativa según Story 9.
 *
 * <p>La operación bloquea la transacción antes de validar que esté en {@code disputa}. Una decisión
 * a favor del vendedor transiciona a {@code completada} y acredita su precio snapshot entero en el
 * ledger interno; una decisión a favor del comprador transiciona a {@code cancelada}, restaura una
 * unidad y persiste una orden durable de refund. Los efectos locales y el evento append-only con el
 * admin responsable ocurren en la misma transacción; desde PHA12TSK03 (recuperación de PHA09TSK05)
 * ambas ramas emiten también, en esa misma transacción, la notificación accionable
 * DISPUTA_RESUELTA al comprador y al vendedor con mensajes dirigidos ("tu compra"/"tu venta") que
 * la UI enruta. Esta clase no autoriza el rol ADMIN ni llama a Stripe, responsabilidades de
 * PHA04TSK15 y PHA04TSK25 respectivamente.</p>
 */
@Service
public class ResolucionDisputaService {

    private final TransaccionRepository transaccionRepository;
    private final UsuarioRepository usuarioRepository;
    private final MovimientoSaldoRepository movimientoSaldoRepository;
    private final PublicacionRepository publicacionRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final TransaccionEventoRepository transaccionEventoRepository;
    private final StripeRefundOutboxRepository stripeRefundOutboxRepository;
    private final NotificacionService notificacionService;

    /**
     * Construye el servicio con los repositorios necesarios para ambos resultados de la resolución
     * y el servicio de notificaciones accionables.
     *
     * @param transaccionRepository repositorio que bloquea y persiste la transacción disputada
     * @param usuarioRepository repositorio que identifica al admin y acredita el saldo del vendedor
     * @param movimientoSaldoRepository repositorio que inserta créditos append-only
     * @param publicacionRepository repositorio que restaura el stock en la rama compradora
     * @param idempotencyKeyRepository repositorio de correlación con el PaymentIntent
     * @param transaccionEventoRepository repositorio que inserta el evento append-only
     * @param stripeRefundOutboxRepository repositorio que persiste la orden durable de refund
     * @param notificacionService servicio de dominio para notificaciones accionables (PHA09TSK05)
     */
    public ResolucionDisputaService(TransaccionRepository transaccionRepository, UsuarioRepository usuarioRepository,
                                    MovimientoSaldoRepository movimientoSaldoRepository,
                                    PublicacionRepository publicacionRepository,
                                    IdempotencyKeyRepository idempotencyKeyRepository,
                                    TransaccionEventoRepository transaccionEventoRepository,
                                    StripeRefundOutboxRepository stripeRefundOutboxRepository,
                                    NotificacionService notificacionService) {
        this.transaccionRepository = transaccionRepository;
        this.usuarioRepository = usuarioRepository;
        this.movimientoSaldoRepository = movimientoSaldoRepository;
        this.publicacionRepository = publicacionRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.transaccionEventoRepository = transaccionEventoRepository;
        this.stripeRefundOutboxRepository = stripeRefundOutboxRepository;
        this.notificacionService = notificacionService;
    }

    /**
     * Resuelve una transacción bloqueada en disputa a favor del vendedor o del comprador.
     *
     * <p>La decisión y el motivo se validan antes de cualquier acceso persistente. Tras bloquear la
     * transacción se rechaza todo estado distinto de {@code disputa}. Para el comprador, la
     * correlación Stripe se obtiene y valida antes de modificar estado, stock, auditoría u outbox.
     * El texto persistido en el evento incluye la decisión explícita y el motivo literal, porque la
     * tabla de eventos solo dispone de la columna {@code motivo} para ese detalle auditable. Ambas
     * ramas emiten al final, dentro de la misma transacción, la notificación accionable
     * DISPUTA_RESUELTA al comprador y al vendedor.</p>
     *
     * @param transaccionId ID de la transacción en disputa
     * @param adminId ID del usuario administrador responsable, registrado como actor del evento
     * @param decision resultado binario explícito a favor del vendedor o del comprador
     * @param motivo texto obligatorio no nulo, no vacío y no compuesto solo por blancos
     * @return transacción persistida como {@code completada} o {@code cancelada}
     * @throws DecisionResolucionDisputaInvalidaException si no se indicó decisión
     * @throws MotivoResolucionDisputaObligatorioException si el motivo no contiene texto
     * @throws TransaccionNoEncontradaException si no existe la transacción indicada
     * @throws TransicionEstadoTransaccionInvalidaException si la transacción no está en {@code disputa}
     * @throws UsuarioNoEncontradoException si el actor administrativo no existe
     * @throws PaymentIntentTransaccionNoEncontradoException si la rama compradora carece de correlación Stripe
     */
    @Transactional
    public Transaccion resolver(Long transaccionId, Long adminId, ResolucionDisputa decision, String motivo) {
        if (decision == null) {
            throw new DecisionResolucionDisputaInvalidaException("La decisión de resolución de disputa es obligatoria");
        }
        if (motivo == null || motivo.trim().isEmpty()) {
            throw new MotivoResolucionDisputaObligatorioException("El motivo de resolución de disputa es obligatorio");
        }

        Transaccion transaccion = transaccionRepository.findByIdForUpdate(transaccionId)
            .orElseThrow(() -> new TransaccionNoEncontradaException("Transacción con ID " + transaccionId + " no encontrada"));
        if (transaccion.getEstado() != EstadoTransaccion.DISPUTA) {
            throw new TransicionEstadoTransaccionInvalidaException("Transición no permitida desde '"
                + transaccion.getEstado() + "'; se requiere estado '" + EstadoTransaccion.DISPUTA + "'");
        }

        Usuario admin = usuarioRepository.findById(adminId)
            .orElseThrow(() -> new UsuarioNoEncontradoException("Usuario administrador con ID " + adminId + " no encontrado"));
        ZonedDateTime ahora = ZonedDateTime.now();
        String detalleEvento = decision.name() + ": " + motivo;

        if (decision == ResolucionDisputa.A_FAVOR_VENDEDOR) {
            Transaccion resuelta = resolverAFavorVendedor(transaccion, admin, detalleEvento, ahora);
            notificarResolucion(resuelta, detalleEvento, ahora);
            return resuelta;
        }
        Transaccion resuelta = resolverAFavorComprador(transaccion, admin, detalleEvento, ahora);
        notificarResolucion(resuelta, detalleEvento, ahora);
        return resuelta;
    }

    /**
     * Emite las notificaciones accionables DISPUTA_RESUELTA al comprador y al vendedor de una
     * disputa ya resuelta (PHA09TSK05, recuperado en PHA12TSK03), dentro de la misma transacción
     * que los efectos de la resolución (constitution, principio 1). Los mensajes incluyen la
     * decisión y el motivo y usan las expresiones dirigidas ("tu compra"/"tu venta") que la UI
     * enruta a la gestión correspondiente.
     *
     * @param transaccion transacción ya persistida como {@code completada} o {@code cancelada}
     * @param detalleEvento decisión y motivo validados incluidos en el mensaje
     * @param ahora instante común de los registros persistidos
     */
    private void notificarResolucion(Transaccion transaccion, String detalleEvento, ZonedDateTime ahora) {
        notificacionService.crearNotificacionUsuario(
            transaccion.getComprador(),
            "DISPUTA_RESUELTA",
            "Se resolvió la disputa de tu compra #" + transaccion.getId() + ": " + detalleEvento,
            transaccion,
            ahora);
        notificacionService.crearNotificacionUsuario(
            transaccion.getPublicacion().getUsuario(),
            "DISPUTA_RESUELTA",
            "Se resolvió la disputa de tu venta #" + transaccion.getId() + ": " + detalleEvento,
            transaccion,
            ahora);
    }

    /**
     * Completa la disputa acreditando exactamente el snapshot entero al vendedor.
     *
     * @param transaccion transacción bloqueada y validada en disputa
     * @param admin usuario responsable del evento append-only
     * @param detalleEvento decisión y motivo ya validados para auditoría
     * @param ahora instante común de los registros persistidos
     * @return transacción persistida como completada
     */
    private Transaccion resolverAFavorVendedor(Transaccion transaccion, Usuario admin, String detalleEvento,
                                                ZonedDateTime ahora) {
        Usuario vendedor = transaccion.getPublicacion().getUsuario();
        long monto = transaccion.getPrecioSnapshot();
        usuarioRepository.incrementarSaldoDisponible(vendedor.getId(), monto);
        transaccion.setEstado(EstadoTransaccion.COMPLETADA);
        Transaccion persistida = transaccionRepository.save(transaccion);
        movimientoSaldoRepository.save(new MovimientoSaldo(persistida, vendedor, monto, ahora));
        transaccionEventoRepository.save(new TransaccionEvento(persistida, admin, EstadoTransaccion.DISPUTA,
            EstadoTransaccion.COMPLETADA, detalleEvento, ahora));
        return persistida;
    }

    /**
     * Cancela la disputa, restaura exactamente una unidad y persiste la orden durable de refund.
     *
     * @param transaccion transacción bloqueada y validada en disputa
     * @param admin usuario responsable del evento append-only
     * @param detalleEvento decisión y motivo ya validados para auditoría
     * @param ahora instante común de los registros persistidos
     * @return transacción persistida como cancelada
     * @throws PaymentIntentTransaccionNoEncontradoException si no hay correlación Stripe utilizable
     */
    private Transaccion resolverAFavorComprador(Transaccion transaccion, Usuario admin, String detalleEvento,
                                                 ZonedDateTime ahora) {
        IdempotencyKey key = idempotencyKeyRepository.findByTransaccionId(transaccion.getId())
            .filter(candidate -> candidate.getPaymentIntentId() != null && !candidate.getPaymentIntentId().isBlank())
            .orElseThrow(() -> new PaymentIntentTransaccionNoEncontradoException(
                "No existe PaymentIntent para la transacción " + transaccion.getId()));

        publicacionRepository.incrementarStock(transaccion.getPublicacion().getId());
        transaccion.setEstado(EstadoTransaccion.CANCELADA);
        Transaccion persistida = transaccionRepository.save(transaccion);
        transaccionEventoRepository.save(new TransaccionEvento(persistida, admin, EstadoTransaccion.DISPUTA,
            EstadoTransaccion.CANCELADA, detalleEvento, ahora));
        stripeRefundOutboxRepository.save(new StripeRefundOutbox(persistida, key.getPaymentIntentId(),
            "refund:" + persistida.getId(), ahora));
        return persistida;
    }
}
