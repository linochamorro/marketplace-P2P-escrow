package com.easymarket.marketplace.service;

import com.easymarket.marketplace.exception.IdempotencyKeyDuplicadaException;
import com.easymarket.marketplace.model.IdempotencyKey;
import com.easymarket.marketplace.repository.IdempotencyKeyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * Servicio de dominio de idempotencia de compra (PHA03TSK05, Story 5, spec.md).
 *
 * <p>Implementa el mecanismo de guardia contra doble-submit del mismo usuario mediante
 * {@code Idempotency-Key} (plan.md, "Idempotencia de compra (doble-submit)" y "Flujo de compra y
 * reserva de stock (PHA03)"). El frontend genera una clave UUID v4 al montar el botón de
 * "Comprar" y la envía en el header {@code Idempotency-Key} de {@code POST /compras}. Este
 * servicio determina si la operación debe continuar (key nueva), está en progreso (key existente
 * sin transacción), o ya se completó (key existente con transacción).</p>
 *
 * <p>Usa {@code REQUIRES_NEW} para que el INSERT de la key se persista en una transacción propia
 * inmediatamente, incluso si la transacción padre (creación de PaymentIntent) se revierte —
 * patrón de "pago en vuelo" del plan.md, sección "Flujo de compra y reserva de stock (PHA03)",
 * donde "el payment intent en vuelo no tiene representación como transacción, solo como fila en
 * idempotency_keys con payment_intent_id".</p>
 *
 * <p>No crea PaymentIntents (PHA03TSK06), no crea transacciones (PHA03TSK08), no expone endpoints
 * REST (PHA03TSK09). Alcance estricto: solo la verificación y registro de la idempotency key.</p>
 */
@Service
public class IdempotenciaCompraService {

    private final IdempotencyKeyRepository idempotencyKeyRepository;

    /**
     * Construye el servicio inyectando el repositorio de idempotency keys.
     *
     * @param idempotencyKeyRepository repositorio JPA de claves de idempotencia
     */
    public IdempotenciaCompraService(IdempotencyKeyRepository idempotencyKeyRepository) {
        this.idempotencyKeyRepository = idempotencyKeyRepository;
    }

    /**
     * Registra o reintenta una operación de compra basada en la {@code Idempotency-Key}.
     *
     * <p>Lógica de decisión:</p>
     * <ul>
     *   <li>Si la key NO existe: la inserta con el {@code paymentIntentId} y
     *       {@code transaccionId = null}. Retorna {@code null} para indicar "key nueva,
     *       puede continuar".</li>
     *   <li>Si la key SÍ existe y tiene {@code transaccionId} poblado: retorna ese
     *       {@code transaccionId} (la compra ya se completó).</li>
     *   <li>Si la key SÍ existe y NO tiene {@code transaccionId} poblado: la operación
     *       está en progreso; lanza {@link IdempotencyKeyDuplicadaException}.</li>
     * </ul>
     *
     * @param idempotencyKey  la clave UUID de idempotencia generada por el frontend
     * @param paymentIntentId el ID del PaymentIntent de Stripe asociado a esta operación
     * @return el ID de la transacción existente si la key ya está completa, o {@code null}
     *         si la key es nueva y la operación puede continuar
     * @throws IdempotencyKeyDuplicadaException si la key ya existe pero no tiene transacción
     *         asociada (operación en progreso)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long registrarOReintentar(String idempotencyKey, String paymentIntentId) {
        Optional<IdempotencyKey> existente = idempotencyKeyRepository.findById(idempotencyKey);

        if (existente.isPresent()) {
            IdempotencyKey key = existente.get();
            if (key.getTransaccionId() != null) {
                // La compra ya se completó; retorna el ID de la transacción existente
                return key.getTransaccionId();
            }
            // La key existe pero sin transacción asociada: operación en progreso
            throw new IdempotencyKeyDuplicadaException(idempotencyKey);
        }

        // Key nueva: inserta con paymentIntentId y transaccionId = null
        IdempotencyKey nueva = new IdempotencyKey(idempotencyKey, paymentIntentId, ZonedDateTime.now());
        idempotencyKeyRepository.save(nueva);
        return null;
    }
}