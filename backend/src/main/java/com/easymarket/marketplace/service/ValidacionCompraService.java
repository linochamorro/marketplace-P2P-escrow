package com.easymarket.marketplace.service;

import com.easymarket.marketplace.exception.AutoCompraNoPermitidaException;
import com.easymarket.marketplace.exception.PublicacionNoEncontradaException;
import com.easymarket.marketplace.exception.StockAgotadoException;
import com.easymarket.marketplace.model.Publicacion;
import com.easymarket.marketplace.repository.PublicacionRepository;
import org.springframework.stereotype.Service;

/**
 * Servicio de dominio de validación de compra (PHA03TSK03, Story 5, spec.md).
 *
 * <p>Valida las precondiciones de negocio de una compra antes de que el flujo avance a la
 * creación del PaymentIntent (plan.md, "Flujo de compra y reserva de stock (PHA03)"):
 * <ul>
 *   <li>Rechaza la auto-compra: el comprador no puede ser el dueño de la publicación
 *       (regla {@code comprador_id != publicacion.usuario_id}).</li>
 *   <li>Rechaza la compra cuando no hay stock disponible (stock &ge; 1 requerido).</li>
 * </ul>
 * No realiza el decremento atómico de stock (PHA03TSK04) ni la creación de transacciones
 * (PHA03TSK08) — alcance estricto de esta tarea.
 * </p>
 */
@Service
public class ValidacionCompraService {

    private final PublicacionRepository publicacionRepository;

    /**
     * Construye el servicio inyectando el repositorio de publicaciones.
     *
     * @param publicacionRepository repositorio JPA de publicaciones
     */
    public ValidacionCompraService(PublicacionRepository publicacionRepository) {
        this.publicacionRepository = publicacionRepository;
    }

    /**
     * Valida que una compra cumpla las precondiciones de negocio de la Story 5.
     *
     * <p>Lanza la excepción de dominio específica en cuanto se detecta la primera violación,
     * en este orden: publicación inexistente, auto-compra y stock agotado. No modifica ningún
     * dato: es una validación pura, previa a la creación del PaymentIntent (plan.md, "Flujo de
     * compra y reserva de stock (PHA03)").</p>
     *
     * @param compradorId ID del usuario que intenta comprar
     * @param publicacionId ID de la publicación a comprar
     * @throws PublicacionNoEncontradaException si la publicación no existe
     * @throws AutoCompraNoPermitidaException si el comprador es el dueño de la publicación
     * @throws StockAgotadoException si la publicación no tiene stock disponible (stock &lt; 1)
     */
    public void validarCompra(Long compradorId, Long publicacionId) {
        Publicacion publicacion = publicacionRepository.findById(publicacionId)
            .orElseThrow(() -> new PublicacionNoEncontradaException("Publicación con ID " + publicacionId + " no encontrada"));

        if (publicacion.getUsuario().getId().equals(compradorId)) {
            throw new AutoCompraNoPermitidaException(
                "El usuario con ID " + compradorId + " es el propietario de la publicación " + publicacionId + "; no puede comprar su propia publicación"
            );
        }

        if (publicacion.getStock() < 1) {
            throw new StockAgotadoException(
                "La publicación con ID " + publicacionId + " no tiene stock disponible (stock=" + publicacion.getStock() + ")"
            );
        }
    }
}
