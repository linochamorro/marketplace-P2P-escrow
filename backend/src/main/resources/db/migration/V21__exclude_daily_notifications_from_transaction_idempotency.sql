-- V21__exclude_daily_notifications_from_transaction_idempotency.sql
-- PHA15TSK03: V20 ya puede estar aplicada; modifica el índice sin cambiar su checksum.
DROP INDEX ux_notificaciones_usuario_transaccion_tipo;

CREATE UNIQUE INDEX ux_notificaciones_usuario_transaccion_tipo
    ON notificaciones (usuario_id, transaccion_id, tipo)
    WHERE transaccion_id IS NOT NULL
      AND tipo NOT IN ('COMPRA_PENDIENTE_DIARIA', 'VENTA_POR_ENTREGAR_DIARIA');
