-- V20__add_notificacion_publicacion_idempotencia.sql
-- PHA15TSK03: una proyección por destinatario, elemento pendiente y tipo.
-- La asociación es nullable para conservar avisos históricos sin elemento.
ALTER TABLE notificaciones
    ADD COLUMN publicacion_id BIGINT
        CONSTRAINT fk_notificaciones_publicacion REFERENCES publicaciones(id) ON DELETE NO ACTION;

CREATE INDEX idx_notificaciones_usuario_publicacion_tipo
    ON notificaciones (usuario_id, publicacion_id, tipo);

-- Las dos claves parciales separan los dos tipos de elemento y no interfieren
-- con las filas históricas sin asociación ni con los avisos transaccionales.
CREATE UNIQUE INDEX ux_notificaciones_usuario_publicacion_tipo
    ON notificaciones (usuario_id, publicacion_id, tipo)
    WHERE publicacion_id IS NOT NULL;

CREATE UNIQUE INDEX ux_notificaciones_usuario_transaccion_tipo
    ON notificaciones (usuario_id, transaccion_id, tipo)
    WHERE transaccion_id IS NOT NULL;
