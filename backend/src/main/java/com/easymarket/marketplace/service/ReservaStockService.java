package com.easymarket.marketplace.service;

import com.easymarket.marketplace.exception.PublicacionNoEncontradaException;
import com.easymarket.marketplace.exception.StockAgotadoException;
import com.easymarket.marketplace.model.Publicacion;
import com.easymarket.marketplace.model.Transaccion;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.PublicacionRepository;
import com.easymarket.marketplace.repository.TransaccionRepository;
import com.easymarket.marketplace.repository.UsuarioRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;

/**
 * Servicio de dominio de reserva atómica de stock con snapshot de precio (PHA03TSK04, Story 5,
 * spec.md).
 *
 * <p>Implementa el mecanismo atómico de reserva de la última unidad frente a compras
 * concurrentes, según la coreografía de plan.md ("Flujo de compra y reserva de stock (PHA03)"):
 * este servicio es ORQUESTADO por el procesador de webhooks {@code payment_intent.succeeded}
 * (PHA03TSK08) — aquí NO se implementa el webhook, el controlador {@code POST /compras}
 * (PHA03TSK09) ni la integración con Stripe (PHA03TSK06). El mecanismo, dentro de UNA
 * transacción de base de datos (constitución, principio 1):</p>
 *
 * <ol>
 *   <li>Decremento atómico condicional {@code UPDATE publicaciones SET stock = stock - 1 WHERE id = ? AND stock >= 1}
 *       (vía {@link PublicacionRepository#decrementarStockSiDisponible(Long)}). El lock de fila de
 *       PostgreSQL materializa la prioridad FIFO por timestamp: el primer hilo que adquiere el
 *       lock gana; el segundo re-evalúa la condición con stock 0 y no afecta filas.</li>
 *   <li>Si el decremento no afecta ninguna fila (perdedor de la carrera), se lanza
 *       {@link StockAgotadoException} y la transacción no crea nada — no queda stock apartado
 *       (plan.md: "Si al procesar payment_intent.succeeded el decremento atómico WHERE stock&gt;=1
 *       falla, NO se crea la transacción").</li>
 *   <li>Si afecta una fila, se toma el snapshot de precio de la publicación dentro de la misma
 *       transacción (en el instante de la reserva) y se persiste la transacción en estado
 *       {@code reservada} con ese {@code precio_snapshot} inmutable (entero en centavos,
 *       constitución, principio 3).</li>
 * </ol>
 *
 * <p>No valida auto-compra ni existencias previas de stock: la validación de auto-compra y de
 * stock &ge; 1 ya vive en {@link ValidacionCompraService} (PHA03TSK03, cerrada) y se aplica en
 * {@code POST /compras} antes del pago; aquí la única "validación" de stock es el propio
 * decremento condicional (scope estricto de esta tarea).</p>
 */
@Service
public class ReservaStockService {

    private final PublicacionRepository publicacionRepository;
    private final UsuarioRepository usuarioRepository;
    private final TransaccionRepository transaccionRepository;

    /**
     * Construye el servicio inyectando los repositorios necesarios.
     *
     * @param publicacionRepository repositorio JPA de publicaciones (provee el decremento atómico condicional)
     * @param usuarioRepository repositorio JPA de usuarios (provee la referencia del comprador para la FK)
     * @param transaccionRepository repositorio JPA de transacciones (persiste la reserva)
     */
    public ReservaStockService(PublicacionRepository publicacionRepository,
                               UsuarioRepository usuarioRepository,
                               TransaccionRepository transaccionRepository) {
        this.publicacionRepository = publicacionRepository;
        this.usuarioRepository = usuarioRepository;
        this.transaccionRepository = transaccionRepository;
    }

    /**
     * Reserva atómicamente una unidad de stock y crea la transacción {@code reservada} con
     * snapshot de precio, en una única transacción de base de datos.
     *
     * <p>Si el decremento condicional no afecta filas (la última unidad ya fue tomada por otro
     * comprador con timestamp más temprano), lanza {@link StockAgotadoException} sin crear
     * ninguna transacción — el stock queda intacto y no se registra nada (Story 5, spec.md:
     * "solo la de timestamp más temprano obtiene la reserva; la otra es rechazada por falta de
     * stock").</p>
     *
     * <p><strong>Sin rollback ante {@link StockAgotadoException} (PHA16TSK02):</strong> la
     * transacción declara {@code noRollbackFor = StockAgotadoException.class} porque el
     * orquestador ({@code ProcesadorEventosWebhookService}) invoca este método dentro de su
     * propia transacción del evento y trata al perdedor persistiendo una orden durable de
     * reembolso en la MISMA transacción: si la excepción marcara rollback-only, el commit del
     * orquestador fallaría con {@code UnexpectedRollbackException} y ni la orden ni la marca del
     * evento sobrevivirían. Antes del lanzamiento no se escribió nada (el decremento no afectó
     * filas), por lo que no hacer rollback no deja efectos parciales; cualquier otra excepción
     * conserva la semántica de rollback normal.</p>
     *
     * @param compradorId ID del usuario comprador (referencia FK; la integridad la garantiza la
     *                    constraint de base de datos de la migración V7)
     * @param publicacionId ID de la publicación a comprar
     * @return la entidad {@link Transaccion} creada y persistida en estado {@code reservada}
     * @throws StockAgotadoException si el decremento condicional no afecta ninguna fila (stock agotado)
     * @throws PublicacionNoEncontradaException si la publicación no existe (no debería ocurrir si
     *         el decremento afectó filas, pero se cubre la integridad referencial de forma defensiva)
     */
    @Transactional(noRollbackFor = StockAgotadoException.class)
    public Transaccion reservarStock(Long compradorId, Long publicacionId) {
        int filasAfectadas = publicacionRepository.decrementarStockSiDisponible(publicacionId);
        if (filasAfectadas == 0) {
            throw new StockAgotadoException(
                "La publicación con ID " + publicacionId + " no tiene stock disponible (stock agotado)"
            );
        }

        Publicacion publicacion = publicacionRepository.findById(publicacionId)
            .orElseThrow(() -> new PublicacionNoEncontradaException(
                "Publicación con ID " + publicacionId + " no encontrada"
            ));
        Usuario comprador = usuarioRepository.getReferenceById(compradorId);

        Transaccion transaccion = new Transaccion(
            comprador,
            publicacion,
            publicacion.getPrecio(),
            ZonedDateTime.now()
        );
        return transaccionRepository.save(transaccion);
    }
}
