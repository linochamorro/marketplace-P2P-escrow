# Galería de imágenes — EasyMarket

Este directorio contiene las capturas funcionales del producto utilizadas en `README.md`.

## Estado

Las capturas reales se generaron en la tarea **F01-T04** con Playwright CLI 1.61.1 (`playwright screenshot`, Chromium) contra el entorno local aislado: PostgreSQL 16.16, backend y frontend en vivo, y datos sintéticos del seed de desarrollo. No son diseños de referencia ni `code.html` de Stitch.

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

## Reglas

- Formato `.png` (salida nativa de Playwright CLI).
- Nombres en español, minúsculas, kebab-case, descriptivos.
- Enlaces desde README con rutas relativas: `docs/images/<archivo>.png`.
- Sin PII, credenciales, correos reales ni tokens en las capturas ni en el texto `alt`.
- Sin loaders, overlays de error ni artefactos de herramientas de captura.
- No publicar diseños de `docs/stitch-screens/` (`code.html`) como capturas reales.
- Las imágenes forman parte del árbol público permitido por G-6b. Los documentos de proceso interno no se enlazan desde este árbol.
