package com.easymarket.marketplace.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * Entidad JPA que representa un evento de webhook de Stripe ya procesado (PHA03TSK08, Story 5,
 * spec.md; plan.md, sección "Idempotencia de webhooks Stripe").
 *
 * <p>Mapea la tabla {@code processed_stripe_events} creada por la migración V8 (PHA03TSK02): la
 * clave primaria es el {@code event_id} provisto por Stripe en el objeto {@code Event}. Antes de
 * procesar un evento entrante, el servicio {@link
 * com.easymarket.marketplace.service.ProcesadorEventosWebhookService} intenta un {@code INSERT}
 * con este ID; si la operación viola la constraint de unicidad, el evento ya fue procesado y se
 * descarta sin reprocesar (plan.md: "el INSERT y la transición de estado correspondiente ocurren
 * en la misma transacción atómica de base de datos"). Esto implementa la guardia de idempotencia
 * requerida por el constitución, principio 4 enmendado: "reintentos del proveedor no duplican
 * efectos en el sistema".</p>
 */
@Entity
@Table(name = "processed_stripe_events")
public class ProcessedStripeEvent {

    @Id
    @Column(name = "event_id", length = 255, nullable = false)
    private String eventId;

    @Column(name = "processed_at", nullable = false)
    private ZonedDateTime processedAt;

    /**
     * Constructor por defecto requerido por JPA.
     */
    public ProcessedStripeEvent() {
    }

    /**
     * Construye una nueva entidad con el ID del evento de Stripe y la marca temporal actual.
     *
     * @param eventId     identificador único del evento de Stripe ({@code event.getId()})
     * @param processedAt marca temporal en que se procesó el evento
     */
    public ProcessedStripeEvent(String eventId, ZonedDateTime processedAt) {
        this.eventId = eventId;
        this.processedAt = processedAt;
    }

    /**
     * Obtiene el identificador único del evento de Stripe (PK natural de la tabla).
     *
     * @return identificador del evento de Stripe
     */
    public String getEventId() {
        return eventId;
    }

    /**
     * Establece el identificador único del evento de Stripe.
     *
     * @param eventId identificador del evento de Stripe
     */
    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    /**
     * Obtiene la marca temporal en que se procesó el evento.
     *
     * @return marca temporal del procesamiento
     */
    public ZonedDateTime getProcessedAt() {
        return processedAt;
    }

    /**
     * Establece la marca temporal del procesamiento.
     *
     * @param processedAt marca temporal del procesamiento
     */
    public void setProcessedAt(ZonedDateTime processedAt) {
        this.processedAt = processedAt;
    }

    /**
     * Compara esta entidad con otro objeto usando la clave primaria natural (event_id).
     *
     * @param o objeto a comparar contra esta entidad
     * @return {@code true} si ambos objetos son la misma instancia o si tienen el mismo
     *         {@code eventId}; {@code false} en cualquier otro caso
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProcessedStripeEvent that = (ProcessedStripeEvent) o;
        return Objects.equals(eventId, that.eventId);
    }

    /**
     * Calcula el hash basado en la clave primaria natural (event_id).
     *
     * @return hash del {@code eventId} de esta entidad
     */
    @Override
    public int hashCode() {
        return Objects.hash(eventId);
    }
}