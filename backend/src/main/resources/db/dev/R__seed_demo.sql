-- R__seed_demo.sql
-- Seed demostrativo repeatable e idempotente — EXCLUSIVO del perfil dev (PHA06TSK08).
-- Traza: plan.md, sección "Integración funcional y operación (PHA06)", fila "Datos demostrativos".
--
-- Contrato rector (plan.md, línea 487):
--   * Solo el perfil dev carga este seed desde la ubicación adicional exclusiva de desarrollo
--     (classpath:db/dev, configurada en application-dev.yml).
--   * Incluye categorías, publicaciones en estados demostrables y las cuentas
--     vendedor@easymarket.dev / VendedorPass123! y comprador@easymarket.dev / CompradorPass123!.
--   * NO se ejecuta en producción (application-prod.yml no configura esta location).
--   * Las transacciones de pago se crean por el flujo real de Stripe/webhook — este script NO
--     inserta en transacciones, movimientos_saldo, idempotency_keys, processed_stripe_events,
--     transaccion_eventos, notificaciones, stripe_refund_outbox ni admin_acciones.
--
-- Repeatable e idempotente por construcción:
--   * usuarios:        ON CONFLICT (email) DO NOTHING.
--   * categorias:      ON CONFLICT (nombre) DO NOTHING.
--   * subcategorias:   ON CONFLICT (categoria_id, nombre) DO NOTHING.
--   * publicaciones:   INSERT ... SELECT ... WHERE NOT EXISTS sobre la clave semántica
--                      determinística (usuario_id, descripcion) — la tabla publicaciones no
--                      tiene constraint único natural, por lo que la idempotencia se resuelve
--                      en el SQL (decisión declarada en el Artifact PHA06TSK08-L01).
-- Los IDs de categorias/subcategorias/usuarios se resuelven por subselect sobre sus nombres o
-- emails únicos — nunca se hardcodean valores de ID (las columnas son IDENTITY y varían).

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. Cuentas de demostración (rol USUARIO; el admin ya lo siembra V6).
--    Hashes BCrypt generados con el BCryptPasswordEncoder real del sistema
--    (spring-security-crypto 6.3.0) para las contraseñas literales del contrato.
--    Credenciales de demostración definidas en plan.md; no son secretos de producción.
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO usuarios (email, password_hash, rol)
VALUES
    ('vendedor@easymarket.dev', '$2a$10$icnyWHC48Zw4Sh6KJOqRAO5IT6Z0Qc3bvU.3ADdt48YDT7V91F7i.', 'USUARIO'),
    ('comprador@easymarket.dev', '$2a$10$Z5cNYokRyRRCZl8ZMMIBl.oceJbuUoh4jRTLYcvDxeV7vT4GeSdD6', 'USUARIO')
ON CONFLICT (email) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. Catálogo de demostración: categorías y subcategorías coherentes.
--    Los IDs se resuelven por subselect sobre el nombre único (nunca hardcodeados).
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO categorias (nombre)
VALUES
    ('Electrónica'),
    ('Hogar y Decoración'),
    ('Moda')
ON CONFLICT (nombre) DO NOTHING;

INSERT INTO subcategorias (categoria_id, nombre)
SELECT c.id, s.nombre
FROM (VALUES
    ('Electrónica',         'Auriculares'),
    ('Electrónica',         'Smartwatches'),
    ('Hogar y Decoración',  'Muebles'),
    ('Hogar y Decoración',  'Iluminación'),
    ('Moda',                'Ropa'),
    ('Moda',                'Calzado')
) AS s(categoria_nombre, nombre)
JOIN categorias c ON c.nombre = s.categoria_nombre
ON CONFLICT (categoria_id, nombre) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- 3. Publicaciones en estados navegables (PHA06TSK09-14):
--      aprobada ×2            → mercado, detalle y compra (PHA06TSK11)
--      pendiente_revisión ×2  → moderación (PHA06TSK13)
--      rechazada ×1           → corrección/eliminación Story 3 (PHA06TSK10)
--      cambios_solicitados ×1 → corrección Story 3 (PHA06TSK10)
--      oculta ×1 (stock 0)    → estado oculta por stock cero, navegable en "mis publicaciones"
--    Precios en centavos (PEN) — constitution principio 3, dinero como enteros.
--    Todas pertenecen al vendedor de demostración; el comprador navega/compra.
--    Clave de idempotencia: (usuario_id, descripcion) — determinística porque el email del
--    vendedor es único y la descripcion es un literal fijo del seed.
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO publicaciones (usuario_id, categoria_id, subcategoria_id, precio, stock, estado, descripcion)
SELECT u.id, c.id, sub.id, p.precio, p.stock, p.estado, p.descripcion
FROM (VALUES
    ('Auriculares inalámbricos Bluetooth con cancelación de ruido',      'Electrónica',        'Auriculares',  18900, 5, 'aprobada'),
    ('Smartwatch deportivo con GPS y monitor de ritmo cardíaco',         'Electrónica',        'Smartwatches', 45900, 3, 'aprobada'),
    ('Mesa de centro de madera de cedro de 90 cm',                       'Hogar y Decoración', 'Muebles',      125000, 1, 'pendiente_revisión'),
    ('Lámpara de escritorio LED regulable',                              'Hogar y Decoración', 'Iluminación',  8900, 2, 'pendiente_revisión'),
    ('Zapatillas urbanas de cuero talla 42',                             'Moda',               'Calzado',      24500, 1, 'rechazada'),
    ('Camisa de lino de manga larga color azul',                         'Moda',               'Ropa',         15900, 2, 'cambios_solicitados'),
    ('Auriculares con cable de estudio',                                 'Electrónica',        'Auriculares',  5900, 0, 'oculta')
) AS p(descripcion, categoria_nombre, subcategoria_nombre, precio, stock, estado)
JOIN usuarios u ON u.email = 'vendedor@easymarket.dev'
JOIN categorias c ON c.nombre = p.categoria_nombre
JOIN subcategorias sub ON sub.nombre = p.subcategoria_nombre AND sub.categoria_id = c.id
WHERE NOT EXISTS (
    SELECT 1
    FROM publicaciones existente
    WHERE existente.usuario_id = u.id
      AND existente.descripcion = p.descripcion
);

-- ─────────────────────────────────────────────────────────────────────────────
-- 4. Imagen por publicación (PHA13TSK01): asigna imagen_filename a las 7 publicaciones
--    del seed mapeando cada descripción EXACTA del INSERT anterior a un archivo real
--    existente en frontend/public/imagenes/publicaciones/ (mapeo por coincidencia
--    semántica, declarado en docs/avance/PHA13TSK01-L01-programmer.md).
--
--    Idempotencia: la guardia AND imagen_filename IS NULL garantiza que re-ejecuciones
--    del seed (o bases donde ya corrió y el usuario asignó otra imagen) nunca
--    sobrescriban una decisión de imagen previa — solo rellena valores aún nulos.
-- ─────────────────────────────────────────────────────────────────────────────
UPDATE publicaciones SET imagen_filename = 'auriculares-bluetooth.jpg'
WHERE descripcion = 'Auriculares inalámbricos Bluetooth con cancelación de ruido'
  AND imagen_filename IS NULL;

UPDATE publicaciones SET imagen_filename = 'smartwatch-gps.jpg'
WHERE descripcion = 'Smartwatch deportivo con GPS y monitor de ritmo cardíaco'
  AND imagen_filename IS NULL;

UPDATE publicaciones SET imagen_filename = 'mesa-centro-cedro.jpg'
WHERE descripcion = 'Mesa de centro de madera de cedro de 90 cm'
  AND imagen_filename IS NULL;

UPDATE publicaciones SET imagen_filename = 'lampara-escritorio-led.jpg'
WHERE descripcion = 'Lámpara de escritorio LED regulable'
  AND imagen_filename IS NULL;

UPDATE publicaciones SET imagen_filename = 'zapatillas-cuero-42.jpg'
WHERE descripcion = 'Zapatillas urbanas de cuero talla 42'
  AND imagen_filename IS NULL;

UPDATE publicaciones SET imagen_filename = 'camisa-lino-azul.jpg'
WHERE descripcion = 'Camisa de lino de manga larga color azul'
  AND imagen_filename IS NULL;

UPDATE publicaciones SET imagen_filename = 'auriculares-estudio.jpg'
WHERE descripcion = 'Auriculares con cable de estudio'
  AND imagen_filename IS NULL;
