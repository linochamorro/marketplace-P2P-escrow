-- V16__allow_publicacion_evento_without_deleted_publicacion.sql
-- Conserva el evento append-only de una publicación eliminada sin afectar su actor.
ALTER TABLE publicacion_eventos
    DROP CONSTRAINT fk_publicacion_eventos_publicacion;

ALTER TABLE publicacion_eventos
    ALTER COLUMN publicacion_id DROP NOT NULL;

ALTER TABLE publicacion_eventos
    ADD CONSTRAINT fk_publicacion_eventos_publicacion
        FOREIGN KEY (publicacion_id) REFERENCES publicaciones(id) ON DELETE SET NULL;

-- PostgreSQL ejecuta SET NULL como un UPDATE interno. Se admite solo esa
-- actualización anidada y limitada a publicacion_id; UPDATE/DELETE directos
-- continúan rechazados por el trigger creado en V15.
CREATE OR REPLACE FUNCTION rechazar_modificacion_publicacion_evento()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE'
        AND pg_trigger_depth() > 1
        AND OLD.publicacion_id IS NOT NULL
        AND NEW.publicacion_id IS NULL
        AND OLD.id IS NOT DISTINCT FROM NEW.id
        AND OLD.actor_id IS NOT DISTINCT FROM NEW.actor_id
        AND OLD.tipo IS NOT DISTINCT FROM NEW.tipo
        AND OLD.detalle IS NOT DISTINCT FROM NEW.detalle
        AND OLD.created_at IS NOT DISTINCT FROM NEW.created_at THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'Los eventos de publicación son append-only y no pueden modificarse ni eliminarse'
        USING ERRCODE = '23514';
END;
$$;
