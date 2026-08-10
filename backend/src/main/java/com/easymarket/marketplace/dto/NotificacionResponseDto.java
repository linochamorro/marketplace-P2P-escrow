package com.easymarket.marketplace.dto;

import com.easymarket.marketplace.model.Notificacion;
import com.easymarket.marketplace.model.Transaccion;

import java.time.ZonedDateTime;

/**
 * DTO de respuesta HTTP para la exposición de notificaciones in-app en el API (Story 7b, spec.md).
 *
 * <p>Mapea una proyección {@link Notificacion} tal como la consume el centro de notificaciones
 * del frontend. El destinatario no se expone: el endpoint {@code GET /notificaciones}
 * (PHA04TSK16) resuelve la identidad exclusivamente desde el principal autenticado, por lo que
 * cada cliente solo puede leer sus propias notificaciones.</p>
 *
 * @param id identificador persistente de la notificación
 * @param mensaje contenido literal de la notificación mostrado al destinatario
 * @param tipo categoría estable de la notificación (ej. {@code AVISO_TRANSACCION_ABIERTA})
 * @param leida indica si el destinatario ya leyó la notificación
 * @param createdAt instante en el que se creó la proyección de la notificación
 * @param transaccionId ID de la transacción asociada, o {@code null} cuando la notificación no
 *                      proviene de una transacción (V14) — incluido como decisión de contrato
 *                      para el futuro centro de notificaciones (PHA04TSK20)
 */
public record NotificacionResponseDto(
        Long id,
        String mensaje,
        String tipo,
        boolean leida,
        ZonedDateTime createdAt,
        Long transaccionId
) {
    /**
     * Mapea una entidad JPA {@link Notificacion} a su representación DTO de respuesta.
     *
     * @param notificacion entidad JPA de origen
     * @return DTO {@link NotificacionResponseDto} resultante
     */
    public static NotificacionResponseDto fromEntity(Notificacion notificacion) {
        Transaccion transaccion = notificacion.getTransaccion();
        return new NotificacionResponseDto(
                notificacion.getId(),
                notificacion.getMensaje(),
                notificacion.getTipo(),
                notificacion.isLeida(),
                notificacion.getCreatedAt(),
                transaccion == null ? null : transaccion.getId()
        );
    }
}