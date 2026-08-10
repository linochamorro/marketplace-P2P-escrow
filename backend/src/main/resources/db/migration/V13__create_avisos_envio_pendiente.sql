-- V13__create_avisos_envio_pendiente.sql
-- Migración para PHA04TSK26 (Story 7).
--
-- Este marcador vincula el único aviso in-app de envío pendiente de 48 horas con
-- su transacción. La PK de transaccion_id impide un segundo aviso puntual para la
-- misma transacción y la unicidad de notificacion_id impide reutilizar una misma
-- notificación para dos transacciones distintas.
CREATE TABLE avisos_envio_pendiente (
    transaccion_id BIGINT PRIMARY KEY
        CONSTRAINT fk_avisos_envio_pendiente_transaccion REFERENCES transacciones(id),
    notificacion_id BIGINT NOT NULL CONSTRAINT uq_avisos_envio_pendiente_notificacion UNIQUE
        CONSTRAINT fk_avisos_envio_pendiente_notificacion REFERENCES notificaciones(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
