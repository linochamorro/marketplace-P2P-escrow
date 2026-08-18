-- V18__add_imagen_filename_to_publicaciones.sql
-- Migración para incorporar el nombre de archivo de imagen de una publicación
-- (decisión de Lino, 2026-08-18): la BD conserva únicamente el nombre de archivo
-- (imagen_filename) y el frontend construye la URL estática
-- /imagenes/publicaciones/{imagen_filename}.
--
-- La columna es NULLABLE: las publicaciones existentes no tienen imagen asignada
-- y el frontend ya resuelve esa ausencia con el marcador "Sin imagen". No se
-- introduce ninguna restricción CHECK sobre la extensión porque el dominio decide
-- el formato al momento de publicar la imagen, no el esquema.
--
-- Se usa ADD COLUMN IF NOT EXISTS como salvaguarda para permitir la re-aplicación
-- idempotente en entornos donde la columna ya exista de forma manual.
ALTER TABLE publicaciones
    ADD COLUMN IF NOT EXISTS imagen_filename VARCHAR(255);