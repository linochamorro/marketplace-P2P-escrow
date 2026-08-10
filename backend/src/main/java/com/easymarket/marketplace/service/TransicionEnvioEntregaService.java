package com.easymarket.marketplace.service;

import com.easymarket.marketplace.exception.ActorNoEsVendedorTransaccionException;
import com.easymarket.marketplace.exception.TransaccionNoEncontradaException;
import com.easymarket.marketplace.exception.TransicionEstadoTransaccionInvalidaException;
import com.easymarket.marketplace.model.EstadoTransaccion;
import com.easymarket.marketplace.model.Transaccion;
import com.easymarket.marketplace.model.TransaccionEvento;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.TransaccionEventoRepository;
import com.easymarket.marketplace.repository.TransaccionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * Servicio de dominio para las transiciones de envío y entrega de la Story 6a.
 *
 * <p>Permite únicamente al vendedor dueño de la publicación avanzar una transacción por la ruta
 * {@code reservada -> enviado -> entregado}. Cada actualización de estado, su timestamp y el
 * evento de auditoría append-only se persisten en una única transacción. Al marcar entregado
 * también persiste la prueba de entrega opcional de la Story 6b. No implementa recepción,
 * movimientos de saldo, reclamos, cancelaciones, jobs ni endpoints.</p>
 */
@Service
public class TransicionEnvioEntregaService {

    private final TransaccionRepository transaccionRepository;
    private final TransaccionEventoRepository transaccionEventoRepository;

    /**
     * Construye el servicio con los repositorios de la transacción y su auditoría append-only.
     *
     * @param transaccionRepository repositorio de transacciones a transicionar
     * @param transaccionEventoRepository repositorio que inserta eventos de auditoría
     */
    public TransicionEnvioEntregaService(TransaccionRepository transaccionRepository,
                                         TransaccionEventoRepository transaccionEventoRepository) {
        this.transaccionRepository = transaccionRepository;
        this.transaccionEventoRepository = transaccionEventoRepository;
    }

    /**
     * Marca como enviada una transacción reservada por el vendedor dueño de su publicación.
     *
     * <p>Persiste juntos el estado {@link EstadoTransaccion#ENVIADO}, {@code fecha_enviado} y el
     * evento {@code reservada -> enviado} sin motivo, por lo que ningún estado transicionado puede
     * confirmarse sin su registro append-only.</p>
     *
     * @param transaccionId ID de la transacción reservada
     * @param actorId ID del vendedor que marca el envío
     * @return transacción persistida en estado {@code enviado}
     * @throws TransaccionNoEncontradaException si no existe la transacción indicada
     * @throws ActorNoEsVendedorTransaccionException si el actor no es el dueño de la publicación
     * @throws TransicionEstadoTransaccionInvalidaException si el estado actual no es {@code reservada}
     */
    @Transactional
    public Transaccion marcarEnviado(Long transaccionId, Long actorId) {
        return transicionar(transaccionId, actorId, EstadoTransaccion.RESERVADA,
            EstadoTransaccion.ENVIADO, null);
    }

    /**
     * Marca como entregada una transacción enviada por el vendedor dueño de su publicación.
     *
     * <p>Persiste juntos el estado {@link EstadoTransaccion#ENTREGADO}, {@code fecha_entregado},
     * la descripción opcional en {@code transacciones.descripcion_prueba_entrega} y el evento
     * {@code enviado -> entregado} sin motivo. Un {@code null} significa que no se proporcionó
     * prueba de entrega y no bloquea la transición; el motivo del evento queda reservado para
     * motivos de negocio de tareas futuras.</p>
     *
     * @param transaccionId ID de la transacción enviada
     * @param actorId ID del vendedor que marca la entrega
     * @param descripcionPruebaEntrega descripción opcional de prueba de entrega, o {@code null}
     *                                  si el vendedor no proporciona una
     * @return transacción persistida en estado {@code entregado}
     * @throws TransaccionNoEncontradaException si no existe la transacción indicada
     * @throws ActorNoEsVendedorTransaccionException si el actor no es el dueño de la publicación
     * @throws TransicionEstadoTransaccionInvalidaException si el estado actual no es {@code enviado}
     */
    @Transactional
    public Transaccion marcarEntregado(Long transaccionId, Long actorId, String descripcionPruebaEntrega) {
        return transicionar(transaccionId, actorId, EstadoTransaccion.ENVIADO,
            EstadoTransaccion.ENTREGADO, descripcionPruebaEntrega);
    }

    /**
     * Ejecuta una única transición de envío o entrega, junto con su evento append-only.
     *
     * @param transaccionId ID de la transacción a actualizar
     * @param actorId ID del vendedor que solicita el cambio
     * @param estadoEsperado único estado desde el cual se permite avanzar
     * @param estadoDestino estado de envío o entrega resultante
     * @param descripcionPruebaEntrega descripción opcional que se persiste únicamente al marcar
     *                                  entregado; es {@code null} para marcar enviado o cuando no
     *                                  se proporciona prueba
     * @return transacción actualizada y persistida
     * @throws TransaccionNoEncontradaException si no existe la transacción indicada
     * @throws ActorNoEsVendedorTransaccionException si el actor no es dueño de la publicación
     * @throws TransicionEstadoTransaccionInvalidaException si el estado actual difiere del esperado
     */
    private Transaccion transicionar(Long transaccionId, Long actorId, EstadoTransaccion estadoEsperado,
                                      EstadoTransaccion estadoDestino, String descripcionPruebaEntrega) {
        Transaccion transaccion = transaccionRepository.findById(transaccionId)
            .orElseThrow(() -> new TransaccionNoEncontradaException(
                "Transacción con ID " + transaccionId + " no encontrada"));
        Usuario vendedor = transaccion.getPublicacion().getUsuario();

        if (!Objects.equals(vendedor.getId(), actorId)) {
            throw new ActorNoEsVendedorTransaccionException(
                "El actor con ID " + actorId + " no es el vendedor dueño de la publicación de la transacción "
                    + transaccionId);
        }
        if (transaccion.getEstado() != estadoEsperado) {
            throw new TransicionEstadoTransaccionInvalidaException(
                "Transición no permitida desde '" + transaccion.getEstado() + "' hacia '" + estadoDestino
                    + "'; se requiere estado '" + estadoEsperado + "'");
        }

        ZonedDateTime ahora = ZonedDateTime.now();
        transaccion.setEstado(estadoDestino);
        if (estadoDestino == EstadoTransaccion.ENVIADO) {
            transaccion.setFechaEnviado(ahora);
        } else {
            transaccion.setFechaEntregado(ahora);
            transaccion.setDescripcionPruebaEntrega(descripcionPruebaEntrega);
        }
        Transaccion persistida = transaccionRepository.save(transaccion);
        transaccionEventoRepository.save(new TransaccionEvento(
            persistida, vendedor, estadoEsperado, estadoDestino, null, ahora));
        return persistida;
    }
}
