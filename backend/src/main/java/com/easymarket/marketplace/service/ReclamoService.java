package com.easymarket.marketplace.service;

import com.easymarket.marketplace.exception.ActorNoEsCompradorTransaccionException;
import com.easymarket.marketplace.exception.PlazoReclamoExcedidoException;
import com.easymarket.marketplace.exception.TransaccionNoEncontradaException;
import com.easymarket.marketplace.exception.TransicionEstadoTransaccionInvalidaException;
import com.easymarket.marketplace.model.EstadoTransaccion;
import com.easymarket.marketplace.model.Transaccion;
import com.easymarket.marketplace.model.TransaccionEvento;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.TransaccionEventoRepository;
import com.easymarket.marketplace.repository.TransaccionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * Servicio de dominio para el reclamo del comprador de la Story 6d.
 *
 * <p>Solo el comprador puede reclamar una transacción {@code entregado} dentro de las 48 horas
 * desde {@code fecha_entregado}. Bajo una única transacción bloquea la transacción, la cambia a
 * {@code disputa} y crea el evento append-only con el motivo literal. Desde PHA12TSK03
 * (recuperación de PHA09TSK05) emite además, en esa misma transacción, DISPUTA_ABIERTA al
 * comprador y al vendedor con mensajes dirigidos ("tu compra"/"tu venta") que la UI enruta, y
 * DISPUTA_PENDIENTE_RESOLVER al admin único. Los fondos siguen en escrow: este servicio no crea
 * movimientos de saldo ni modifica {@code saldo_disponible} o stock.</p>
 */
@Service
public class ReclamoService {

    private final TransaccionRepository transaccionRepository;
    private final TransaccionEventoRepository transaccionEventoRepository;
    private final NotificacionService notificacionService;
    private final Clock clock;

    /**
     * Construye el servicio para producción con el reloj UTC del sistema.
     *
     * @param transaccionRepository repositorio que bloquea y persiste la transacción
     * @param transaccionEventoRepository repositorio que inserta el evento append-only
     * @param notificacionService servicio de dominio para notificaciones accionables (PHA09TSK05)
     */
    @Autowired
    public ReclamoService(TransaccionRepository transaccionRepository,
                          TransaccionEventoRepository transaccionEventoRepository,
                          NotificacionService notificacionService) {
        this(transaccionRepository, transaccionEventoRepository, notificacionService, Clock.systemUTC());
    }

    /**
     * Construye el servicio con un reloj explícito para evaluar determinísticamente el plazo.
     *
     * @param transaccionRepository repositorio que bloquea y persiste la transacción
     * @param transaccionEventoRepository repositorio que inserta el evento append-only
     * @param notificacionService servicio de dominio para notificaciones accionables (PHA09TSK05)
     * @param clock reloj que provee el instante actual
     */
    ReclamoService(TransaccionRepository transaccionRepository,
                   TransaccionEventoRepository transaccionEventoRepository,
                   NotificacionService notificacionService, Clock clock) {
        this.transaccionRepository = transaccionRepository;
        this.transaccionEventoRepository = transaccionEventoRepository;
        this.notificacionService = notificacionService;
        this.clock = clock;
    }

    /**
     * Reclama una transacción entregada por su comprador dentro de las 48 horas posteriores.
     *
     * <p>La frontera de 48 horas es inclusiva: se permite reclamar cuando {@code ahora} coincide
     * exactamente con {@code fecha_entregado + 48h}; se rechaza solo después. El motivo es texto
     * libre: Story 6d no lo declara obligatorio (a diferencia de Story 7), por lo que los valores
     * {@code null} y vacío se persisten literalmente. Si falla la persistencia de la transición,
     * del evento o de alguna notificación accionable (DISPUTA_ABIERTA a ambas partes,
     * DISPUTA_PENDIENTE_RESOLVER al admin), la transacción de Spring revierte todos los efectos.</p>
     *
     * @param transaccionId ID de la transacción entregada
     * @param actorId ID del comprador que presenta el reclamo
     * @param motivo texto libre del reclamo, incluido {@code null} o cadena vacía
     * @return transacción persistida en estado {@code disputa}
     * @throws TransaccionNoEncontradaException si no existe la transacción indicada
     * @throws ActorNoEsCompradorTransaccionException si el actor no es el comprador de la transacción
     * @throws TransicionEstadoTransaccionInvalidaException si el estado actual no es {@code entregado}
     * @throws PlazoReclamoExcedidoException si falta la fecha de entrega o pasó el plazo de 48 horas
     */
    @Transactional
    public Transaccion reclamar(Long transaccionId, Long actorId, String motivo) {
        Transaccion transaccion = transaccionRepository.findByIdForUpdate(transaccionId)
            .orElseThrow(() -> new TransaccionNoEncontradaException(
                "Transacción con ID " + transaccionId + " no encontrada"));

        if (!Objects.equals(transaccion.getComprador().getId(), actorId)) {
            throw new ActorNoEsCompradorTransaccionException(
                "El actor con ID " + actorId + " no es el comprador de la transacción " + transaccionId);
        }
        if (transaccion.getEstado() != EstadoTransaccion.ENTREGADO) {
            throw new TransicionEstadoTransaccionInvalidaException(
                "Transición no permitida desde '" + transaccion.getEstado() + "' hacia '"
                    + EstadoTransaccion.DISPUTA + "'; se requiere estado '" + EstadoTransaccion.ENTREGADO + "'");
        }

        ZonedDateTime ahora = ZonedDateTime.now(clock);
        ZonedDateTime fechaEntregado = transaccion.getFechaEntregado();
        if (fechaEntregado == null || fechaEntregado.plusHours(48).isBefore(ahora)) {
            throw new PlazoReclamoExcedidoException(
                "El reclamo de la transacción " + transaccionId
                    + " requiere fecha de entrega y debe ocurrir dentro de las 48 horas posteriores");
        }

        transaccion.setEstado(EstadoTransaccion.DISPUTA);
        Transaccion persistida = transaccionRepository.save(transaccion);
        transaccionEventoRepository.save(new TransaccionEvento(persistida, transaccion.getComprador(),
            EstadoTransaccion.ENTREGADO, EstadoTransaccion.DISPUTA, motivo, ahora));

        // Notificaciones accionables (PHA09TSK05, recuperado en PHA12TSK03), emitidas dentro de la
        // misma transacción que la transición (constitution, principio 1): DISPUTA_ABIERTA a ambas
        // partes y DISPUTA_PENDIENTE_RESOLVER al admin único.
        Usuario vendedor = persistida.getPublicacion().getUsuario();
        notificacionService.crearNotificacionUsuario(
            persistida.getComprador(),
            "DISPUTA_ABIERTA",
            "Abriste una disputa sobre tu compra #" + persistida.getId()
                + "; los fondos quedan congelados hasta la resolución",
            persistida,
            ahora);
        notificacionService.crearNotificacionUsuario(
            vendedor,
            "DISPUTA_ABIERTA",
            "El comprador abrió una disputa sobre tu venta #" + persistida.getId()
                + "; los fondos quedan congelados hasta la resolución",
            persistida,
            ahora);
        notificacionService.crearNotificacionAdmin(
            "DISPUTA_PENDIENTE_RESOLVER",
            "Disputa abierta en la transacción #" + persistida.getId() + "; pendiente de resolución administrativa",
            persistida,
            ahora);
        return persistida;
    }
}
