-- V6__seed_admin_usuario.sql
-- Seed de cuenta administrativa única provisionada desde variables de entorno.
-- Traza: Story 0 (regla admin) de spec.md y plan.md ("Provisión de admin").

INSERT INTO usuarios (email, password_hash, rol)
VALUES (
    '${admin_email}',
    '${admin_password_hash}',
    'ADMIN'
)
ON CONFLICT (email) DO NOTHING;
