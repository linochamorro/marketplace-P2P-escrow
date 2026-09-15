-- V18__add_notificacion_tipos_accionables.sql
-- Migración para PHA09TSK05: nuevos tipos de notificación accionables por rol.
--
-- Agrega CHECK constraint en notificaciones.tipo con los valores autorizados.
-- La columna leida ya existe desde V9 (NOT NULL DEFAULT FALSE).

ALTER TABLE notificaciones
    ADD CONSTRAINT chk_notificaciones_tipo_valido
    CHECK (tipo IN (
        -- Tipos existentes (PHA04, PHA06)
        'NUEVA_PUBLICACION_PENDIENTE',
        'ENVIO_PENDIENTE_48H',
        'COMPRA_PENDIENTE_DIARIA',
        'VENTA_POR_ENTREGAR_DIARIA',
        'AVISO_TRANSACCION_ABIERTA',
        -- Nuevos tipos accionables por rol (PHA09TSK05)
        'PUBLICACION_PENDIENTE_APROBAR',
        'DISPUTA_PENDIENTE_RESOLVER',
        'RESPUESTA_USUARIO_PENDIENTE',
        'PUBLICACION_APROBADA_RECHAZADA',
        'COMPRA_CONFIRMADA',
        'ENVIO_MARCADO',
        'ENTREGA_MARCADA',
        'DISPUTA_ABIERTA',
        'DISPUTA_RESUELTA'
    ));

-- Índice para consultas frecuentes por tipo y usuario (filtro por rol en GET /notificaciones)
CREATE INDEX idx_notificaciones_usuario_tipo_leida ON notificaciones (usuario_id, tipo, leida);