-- V22__add_codigo_producto_to_publicaciones.sql
-- Migración para PHA15TSK12 (plan.md, sección "PHA15 — Código de producto (id_producto)",
-- decisiones de Lino 2026-09-01; constitution, principio 1).
--
-- Todo producto (publicación) obtiene un identificador de negocio autogenerado por el Sistema,
-- distinto del id IDENTITY interno. El código tiene el formato {YYYY}{PREF}{NNNNN}:
--   * {YYYY}   = año del created_at (4 dígitos).
--   * {PREF}   = primeras 3 letras del nombre de la categoría, normalizadas a mayúsculas y sin
--                acentos/símbolos/espacios; si hay menos de 3 letras útiles se usan las disponibles.
--   * {NNNNN}  = consecutivo GLOBAL de la secuencia seq_codigo_producto, en 5 dígitos con padding a 0.
--
-- Decisiones de esquema declaradas (coherentes con plan.md y las decisiones de Lino 2026-09-01):
--   * El consecutivo es GLOBAL (nunca se reinicia): la secuencia seq_codigo_producto se comparte
--     entre todos los productos, sin importar categoría ni año. La unicidad del código completo la
--     refuerza la constraint UNIQUE sobre la columna (decisión de Lino).
--   * El trigger tg_publicaciones_codigo_producto es la ÚNICA fuente de generación: cubre el
--     servicio de dominio (insert vía JPA), el seed SQL del perfil dev (insert directo,
--     PHA06TSK08) y cualquier inserción futura, sin duplicar la regla en SQL adicional ni en Java.
--   * El trigger actúa SOLO en BEFORE INSERT (generador de valor), a diferencia de los triggers
--     append-only V10/V11/V15/V17: NO bloquea UPDATE ni DELETE sobre publicaciones, porque la
--     columna es un dato derivado mutable por diseño y no un registro de auditoría.
--   * El backfill de productos existentes se computa con row_number() OVER (ORDER BY id) para que
--     el consecutivo sea determinístico (1..N por id) y finaliza con setval() para que los
--     productos nuevos continúen la numeración en N+1. No se usa nextval() durante el backfill
--     porque un UPDATE no garantiza el orden de procesamiento de las filas.
--
-- Nota de formato del año: se usa to_char(created_at AT TIME ZONE 'UTC', 'YYYY'). El año se
-- determina en UTC de forma explícita y determinística porque to_char(timestamptz, 'YYYY')
-- depende de la zona horaria de la sesión: un created_at próximo a la frontera de año puede
-- cambiar de año según la sesión (p. ej. 2023-01-01T00:00:00+00 es 2022-12-31 en America/Lima,
-- UTC-5). Al fijar UTC, el backfill y el trigger producen el mismo año en cualquier entorno, lo
-- que además respeta la exigencia de backfill determinístico de la tarea. El patrón numérico
-- to_char(..., 'FM0000') sugerido en la nota de la tarea devuelve literalmente '0000' sobre un
-- timestamptz (verificado en PostgreSQL 16): no es válido para extraer el año; se documenta en el
-- Artifact.

-- 1. Secuencia global del consecutivo del código de producto (nunca se reinicia).
CREATE SEQUENCE IF NOT EXISTS seq_codigo_producto;

-- 2. Función compartida de normalización del prefijo de categoría.
--    La regla de derivación vive UNA sola vez aquí y la usan tanto el backfill como el trigger,
--    evitando duplicar la lógica.
--    Normalización: quita acentos (translate con mapeo literal de vocales + ñ + ü), descarta todo
--    carácter que no sea A-Z, pasa a mayúsculas y toma las 3 primeras letras (o las disponibles).
CREATE OR REPLACE FUNCTION normalizar_prefijo_categoria(nombre_categoria TEXT)
RETURNS TEXT
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    normalizado TEXT;
BEGIN
    normalizado := translate(nombre_categoria, 'áéíóúÁÉÍÓÚñÑüÜ', 'aeiouAEIOUnNuU');
    normalizado := regexp_replace(upper(normalizado), '[^A-Z]', '', 'g');
    RETURN left(normalizado, 3);
END;
$$;

-- 3. Columna codigo_producto. Se agrega NULLABLE (el backfill la completa enseguida) y recién
--    después se fija NOT NULL + UNIQUE.
ALTER TABLE publicaciones
    ADD COLUMN IF NOT EXISTS codigo_producto VARCHAR(20);

-- 4. Backfill determinístico de los productos existentes: año de su created_at + prefijo de su
--    categoría + consecutivo de la secuencia, en orden por id. La función de normalización y el
--    formato se comparten con el trigger (no se duplican).
WITH filas AS (
    SELECT p.id,
           to_char(p.created_at AT TIME ZONE 'UTC', 'YYYY')
               || normalizar_prefijo_categoria(c.nombre)
               || lpad(row_number() OVER (ORDER BY p.id)::text, 5, '0') AS codigo
    FROM publicaciones p
    JOIN categorias c ON c.id = p.categoria_id
)
UPDATE publicaciones p
SET codigo_producto = f.codigo
FROM filas f
WHERE p.id = f.id;

-- 4bis. Ajuste de la secuencia para que los productos nuevos continúen la numeración en COUNT(*)+1.
--       GREATEST(COUNT(*), 1) evita setval(seq, 0, true), que PostgreSQL rechaza (valor 0 fuera de
--       rango) cuando la tabla está vacía al momento de la migración (verificado en PostgreSQL 16).
SELECT setval('seq_codigo_producto', GREATEST(COUNT(*), 1), COUNT(*) > 0) FROM publicaciones;

-- 5. La columna pasa a NOT NULL + UNIQUE (la unicidad del código completo la refuerza la constraint).
ALTER TABLE publicaciones
    ALTER COLUMN codigo_producto SET NOT NULL,
    ADD CONSTRAINT uk_publicaciones_codigo_producto UNIQUE (codigo_producto);

-- 6. Trigger generador del código: única fuente de generación. Asigna el valor cuando
--    NEW.codigo_producto IS NULL (si la aplicación fija un código explícito, el trigger lo respeta).
CREATE OR REPLACE FUNCTION asignar_codigo_producto()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    prefijo_categoria TEXT;
    anio TEXT;
    consecutivo TEXT;
BEGIN
    IF NEW.codigo_producto IS NULL THEN
        anio := to_char(COALESCE(NEW.created_at, CURRENT_TIMESTAMP) AT TIME ZONE 'UTC', 'YYYY');
        SELECT normalizar_prefijo_categoria(c.nombre)
        INTO prefijo_categoria
        FROM categorias c
        WHERE c.id = NEW.categoria_id;
        prefijo_categoria := COALESCE(prefijo_categoria, '');
        consecutivo := lpad(nextval('seq_codigo_producto')::text, 5, '0');
        NEW.codigo_producto := anio || prefijo_categoria || consecutivo;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS tg_publicaciones_codigo_producto ON publicaciones;
CREATE TRIGGER tg_publicaciones_codigo_producto
BEFORE INSERT ON publicaciones
FOR EACH ROW
EXECUTE FUNCTION asignar_codigo_producto();
