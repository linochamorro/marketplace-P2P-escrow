package com.easymarket.marketplace.service;

import com.easymarket.marketplace.exception.ActorNoEsCompradorTransaccionException;
import com.easymarket.marketplace.exception.PlazoConfirmacionRecepcionExcedidoException;
import com.easymarket.marketplace.exception.TransaccionNoEncontradaException;
import com.easymarket.marketplace.exception.TransicionEstadoTransaccionInvalidaException;
import com.easymarket.marketplace.model.EstadoTransaccion;
import com.easymarket.marketplace.model.MovimientoSaldo;
import com.easymarket.marketplace.model.Transaccion;
import com.easymarket.marketplace.model.TransaccionEvento;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.MovimientoSaldoRepository;
import com.easymarket.marketplace.repository.TransaccionEventoRepository;
import com.easymarket.marketplace.repository.TransaccionRepository;
import com.easymarket.marketplace.repository.UsuarioRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * Servicio de dominio para la confirmación explícita de recepción de la Story 6c.
 *
 * <p>Solo el comprador puede confirmar una transacción {@code entregado} dentro de las 48 horas
 * desde {@code fecha_entregado}. Bajo una única transacción bloquea la transacción, acredita el
 * saldo cacheado del vendedor con el precio snapshot entero, inserta el movimiento append-only y
 * registra la transición {@code entregado -> recibido}. Desde PHA12TSK03 (recuperación de
 * PHA09TSK05) emite además, en esa misma transacción, la notificación accionable
 * COMPRA_CONFIRMADA al comprador y al vendedor con mensajes dirigidos ("tu compra"/"tu venta")
 * que la UI enruta. No modifica stock: PHA03 ya lo descontó al reservar.</p>
 */
@Service
public class ConfirmacionRecepcionService {

    private final TransaccionRepository transaccionRepository;
    private final UsuarioRepository usuarioRepository;
    private final MovimientoSaldoRepository movimientoSaldoRepository;
    private final TransaccionEventoRepository transaccionEventoRepository;
    private final NotificacionService notificacionService;
    private final Clock clock;

    /**
     * Construye el servicio para ejecución de producción usando el reloj UTC del sistema.
     *
     * @param transaccionRepository repositorio que bloquea y persiste la transacción
     * @param usuarioRepository repositorio que incrementa atómicamente el saldo cacheado
     * @param movimientoSaldoRepository repositorio que inserta el movimiento de saldo
     * @param transaccionEventoRepository repositorio que inserta la auditoría de transición
     * @param notificacionService servicio de dominio para notificaciones accionables (PHA09TSK05)
     */
    @Autowired
    public ConfirmacionRecepcionService(TransaccionRepository transaccionRepository,
                                        UsuarioRepository usuarioRepository,
                                        MovimientoSaldoRepository movimientoSaldoRepository,
                                        TransaccionEventoRepository transaccionEventoRepository,
                                        NotificacionService notificacionService) {
        this(transaccionRepository, usuarioRepository, movimientoSaldoRepository,
            transaccionEventoRepository, notificacionService, Clock.systemUTC());
    }

    /**
     * Construye el servicio con un reloj explícito para evaluar determinísticamente el plazo.
     *
     * @param transaccionRepository repositorio que bloquea y persiste la transacción
     * @param usuarioRepository repositorio que incrementa atómicamente el saldo cacheado
     * @param movimientoSaldoRepository repositorio que inserta el movimiento de saldo
     * @param transaccionEventoRepository repositorio que inserta la auditoría de transición
     * @param notificacionService servicio de dominio para notificaciones accionables (PHA09TSK05)
     * @param clock reloj que provee el instante actual
     */
    ConfirmacionRecepcionService(TransaccionRepository transaccionRepository, UsuarioRepository usuarioRepository,
                                 MovimientoSaldoRepository movimientoSaldoRepository,
                                 TransaccionEventoRepository transaccionEventoRepository,
                                 NotificacionService notificacionService, Clock clock) {
        this.transaccionRepository = transaccionRepository;
        this.usuarioRepository = usuarioRepository;
        this.movimientoSaldoRepository = movimientoSaldoRepository;
        this.transaccionEventoRepository = transaccionEventoRepository;
        this.notificacionService = notificacionService;
        this.clock = clock;
    }

    /**
     * Confirma la recepción de una transacción entregada por su comprador dentro del plazo.
     *
     * <p>La frontera de 48 horas es inclusiva: se permite confirmar cuando {@code ahora} coincide
     * exactamente con {@code fecha_entregado + 48h}; se rechaza solo después. Cualquier excepción
     * de persistencia, incluido un fallo al insertar el movimiento o al emitir las notificaciones
     * accionables COMPRA_CONFIRMADA, revierte el crédito, la transición, el evento y los avisos
     * por la transacción de Spring.</p>
     *
     * @param transaccionId ID de la transacción entregada
     * @param actorId ID del comprador que confirma la recepción
     * @return transacción persistida en estado {@code recibido}
     * @throws TransaccionNoEncontradaException si no existe la transacción indicada
     * @throws ActorNoEsCompradorTransaccionException si el actor no es el comprador de la transacción
     * @throws TransicionEstadoTransaccionInvalidaException si el estado actual no es {@code entregado}
     * @throws PlazoConfirmacionRecepcionExcedidoException si faltó la fecha de entrega o pasó el plazo
     */
    @Transactional
    public Transaccion confirmarRecepcion(Long transaccionId, Long actorId) {
        Transaccion transaccion = transaccionRepository.findByIdForUpdate(transaccionId)
            .orElseThrow(() -> new TransaccionNoEncontradaException(
                "Transacción con ID " + transaccionId + " no encontrada"));

        if (!Objects.equals(transaccion.getComprador().getId(), actorId)) {
            throw new ActorNoEsCompradorTransaccionException(
                "El actor con ID " + actorId + " no es el comprador de la transacción " + transaccionId);
        }
        if (transaccion.getEstado() != EstadoTransaccion.ENTREGADO) {
            throw new TransicionEstadoTransaccionInvalidaException(
                "Transición no permitida desde '" + transaccion.getEstado()
                    + "' hacia '" + EstadoTransaccion.RECIBIDO + "'; se requiere estado '"
                    + EstadoTransaccion.ENTREGADO + "'");
        }

        ZonedDateTime ahora = ZonedDateTime.now(clock);
        ZonedDateTime fechaEntregado = transaccion.getFechaEntregado();
        if (fechaEntregado == null || fechaEntregado.plusHours(48).isBefore(ahora)) {
            throw new PlazoConfirmacionRecepcionExcedidoException(
                "La confirmación de la transacción " + transaccionId
                    + " requiere fecha de entrega y debe ocurrir dentro de las 48 horas posteriores");
        }

        Usuario vendedor = transaccion.getPublicacion().getUsuario();
        long monto = transaccion.getPrecioSnapshot();
        usuarioRepository.incrementarSaldoDisponible(vendedor.getId(), monto);
        transaccion.setEstado(EstadoTransaccion.RECIBIDO);
        Transaccion persistida = transaccionRepository.save(transaccion);
        movimientoSaldoRepository.save(new MovimientoSaldo(persistida, vendedor, monto, ahora));
        transaccionEventoRepository.save(new TransaccionEvento(persistida, transaccion.getComprador(),
            EstadoTransaccion.ENTREGADO, EstadoTransaccion.RECIBIDO, null, ahora));

        // Notificaciones accionables a comprador y vendedor (PHA09TSK05, recuperado en PHA12TSK03),
        // emitidas dentro de la misma transacción que la transición (constitution, principio 1).
        notificacionService.crearNotificacionUsuario(
            transaccion.getComprador(),
            "COMPRA_CONFIRMADA",
            "Confirmaste la recepción de tu compra #" + persistida.getId(),
            persistida,
            ahora);
        notificacionService.crearNotificacionUsuario(
            vendedor,
            "COMPRA_CONFIRMADA",
            "El comprador confirmó la recepción de tu venta #" + persistida.getId() + "; el saldo fue acreditado",
            persistida,
            ahora);
        return persistida;
    }
}
