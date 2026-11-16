# Galería de imágenes — EasyMarket

Este directorio contiene las capturas funcionales del producto enlazadas desde el `README.md`.

## Origen de las capturas

Son capturas reales obtenidas con Playwright CLI 1.61.1 (`playwright screenshot`, Chromium) contra un entorno local: PostgreSQL 16.16, backend y frontend en ejecución y datos sintéticos del seed de desarrollo. No son diseños de referencia ni maquetas estáticas.

## Capturas incluidas

| Archivo | Vista funcional | Viewport |
|---|---|---|
| `home-institucional.png` | Home / institucional | 1280 × 800 |
| `explorar-publicaciones.png` | Explorar publicaciones | 1280 × 800 |
| `detalle-del-producto.png` | Detalle del producto y escrow | 1280 × 800 |
| `acceso-seguro-login.png` | Acceso seguro (login / registro) | 1280 × 800 |
| `panel-de-administracion.png` | Panel de administración | 1280 × 800 |
| `gestion-de-transaccion-escrow.png` | Compras / escrow (estado vacío del seed) | 1280 × 800 |
| `mis-publicaciones.png` | Gestión de publicaciones propias | 1280 × 800 |
| `notificaciones.png` | Centro de notificaciones | 1280 × 800 |
| `saldo-perfil.png` | Panel de saldo | 1280 × 800 |
| `home-institucional-movil.png` | Comprobación móvil de la home | Emulación `Pixel 5` (Chromium) |

Las vistas de compras, notificaciones y saldo muestran el estado vacío del seed de demostración: las transacciones de pago se crean con el flujo real de Stripe y no forman parte de la navegación local capturada.

## Reglas

- Formato `.png` (salida nativa de Playwright CLI).
- Nombres en español, minúsculas, kebab-case y descriptivos.
- Enlaces desde el README con rutas relativas: `docs/images/<archivo>.png`.
- Sin PII, credenciales, correos reales ni tokens en las capturas ni en el texto `alt`.
- Sin loaders, overlays de error ni artefactos de herramientas de captura.
- Solo capturas funcionales: las referencias de diseño no se publican como capturas reales.
