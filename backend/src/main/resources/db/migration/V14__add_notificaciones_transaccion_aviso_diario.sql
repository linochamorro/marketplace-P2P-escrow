-- V14__add_notificaciones_transaccion_aviso_diario.sql
-- Migración para PHA04TSK27 (Story 7b).
--
-- Las notificaciones son una proyección in-app de conveniencia y no el log canónico
-- append-only de transiciones. La relación nullable conserva los avisos existentes
-- que no pertenecen a una transacción.
ALTER TABLE notificaciones
    ADD COLUMN transaccion_id BIGINT
        CONSTRAINT fk_notificaciones_transaccion REFERENCES transacciones(id) ON DELETE NO ACTION;

-- El futuro job diario consulta el último aviso por transacción, destinatario y tipo,
-- ordenado por creación. No se define ninguna semántica de tipo o mensaje en esta migración.
CREATE INDEX idx_notificaciones_transaccion_usuario_tipo_created_at
    ON notificaciones (transaccion_id, usuario_id, tipo, created_at);
