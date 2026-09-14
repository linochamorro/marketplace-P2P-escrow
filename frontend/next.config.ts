/**
 * Configuración Next.js del frontend EasyMarket (marketplace P2P con escrow).
 *
 * <p><strong>PHA16TSK08 — Headers de seguridad</strong> (plan.md, sección "PHA16 —
 * Estabilización de producción y seguridad", fila "Headers de seguridad"; informe de
 * auditoría 2026-09-13, mejora B1). La función `headers()` registra los mismos 4 headers
 * del backend (`X-Content-Type-Options`, `X-Frame-Options: DENY`, `Referrer-Policy`,
 * `Permissions-Policy` mínima) más `Content-Security-Policy` en modo <strong>Report-Only</strong>
 * en TODAS las rutas (`/:path*`, lo que incluye `/` y las rutas con shell).</p>
 *
 * <p><strong>Sintaxis seguida (frontend/AGENTS.md).</strong> Guías locales de Next 16
 * consultadas en `node_modules/next/dist/docs/`:
 * <ul>
 *   <li>`01-app/03-api-reference/05-config/01-next-config-js/headers.md` — la clave `headers()`
 *       (síncrona o async) retorna un arreglo de `{ source, headers: [{ key, value }] }`;
 *       `source: '/:path*'` cubre todas las rutas; los valores documentados de
 *       `X-Frame-Options`, `Permissions-Policy` (`camera=(), microphone=(), geolocation=(),
 *       browsing-topics=()`), `X-Content-Type-Options: nosniff` y `Referrer-Policy` se
 *       adoptan literalmente.</li>
 *   <li>`01-app/02-guides/content-security-policy.md`, sección "Without Nonces" — para apps
 *       sin nonces el CSP se define directo en `next.config` con `source: '/(.*)'`; el ejemplo
 *       canónico usa `script-src 'self' 'unsafe-inline'` (+ `'unsafe-eval'` solo en dev),
 *       `style-src 'self' 'unsafe-inline'` e `img-src 'self' blob: data:` — base adoptada aquí
 *       y extendida con la allowlist de Stripe + API sin romper `next/image` (cuyas imágenes
 *       son same-origin: archivos en `/imagenes/publicaciones/` y optimizador `/_next/image`,
 *       cubiertos por `img-src 'self'`; la guía además excluye `/_next/image` del matcher del
 *       proxy de nonces por no necesitar CSP propio).</li>
 * </ul>
 * No se usa proxy/nonces (fuera de scope: la fila exige solo Report-Only vía `next.config`).</p>
 *
 * <p><strong>Política CSP Report-Only final, directiva por directiva:</strong>
 * <ul>
 *   <li>`default-src 'self'` — base cerrada: todo lo no listado solo admite mismo origen.</li>
 *   <li>`script-src 'self' 'unsafe-inline' https://js.stripe.com` (+ `'unsafe-eval'` solo en
 *       `development`, como la guía) — `'self'`: runtime y bundles de Next; `'unsafe-inline'`:
 *       scripts bootstrap inline que Next emite sin nonces; `js.stripe.com`: loader de Stripe.js
 *       usado por `CompraButton` vía `@stripe/stripe-js`.</li>
 *   <li>`style-src 'self' 'unsafe-inline'` — Tailwind y estilos inline del shell/cards.</li>
 *   <li>`img-src 'self' data: blob:` — imágenes de producto del propio dominio, optimizador
 *       `/_next/image` (same-origin) y URIs `data:`/`blob:` (guía canónica).</li>
 *   <li>`font-src 'self'` — `next/font/google` (Geist) sirve las fuentes desde `/_next/static`
 *       (mismo origen) tras la descarga en build.</li>
 *   <li>`connect-src 'self' &lt;origen API&gt; https://api.stripe.com` — `'self'`: fetches
 *       mismo origen; `&lt;origen API&gt;`: `fetch` cross-origin a `NEXT_PUBLIC_API_URL`
 *       (derivado en carga del config vía `apiOrigin()`; `http://localhost:8080` en dev,
 *       URL de Railway en prod); `api.stripe.com`: llamadas cliente de Stripe.js.</li>
 *   <li>`frame-src 'self' https://js.stripe.com https://hooks.stripe.com` — `PaymentElement`
 *       monta sus campos en iframes de `js.stripe.com`; 3DS y métodos con redirección usan
 *       `hooks.stripe.com`.</li>
 *   <li>`object-src 'none'` — sin plugins/embeds.</li>
 *   <li>`base-uri 'self'` y `form-action 'self'` — sin navegación/posteos externos.</li>
 *   <li>`frame-ancestors 'none'` — coherente con `X-Frame-Options: DENY` (ninguna página
 *       admite ser embebida; no existe flujo de embed propio).</li>
 * </ul>
 * Se omite deliberadamente `upgrade-insecure-requests`: rompería el dev local en `http://`.
 * Sin `report-uri`: en Report-Only las violaciones quedan en consola del navegador hasta que
 * una tarea futura (fuera de scope, PHA16TSK09+) defina el endpoint de reportes.</p>
 *
 * <p><strong>Por qué Report-Only y no enforced</strong> (lo ordena la fila): un CSP enforced
 * con una allowlist incompleta rompería silenciosamente el flujo de pago (Stripe.js, iframes
 * del PaymentElement, 3DS) o las imágenes; en Report-Only nada se bloquea y el navegador
 * reporta violaciones en consola, permitiendo validar la política contra tráfico real antes
 * de endurecerla. Pasar a enforced es trabajo futuro fuera de este scope.</p>
 *
 * <p><strong>Límites de scope (fila PHA16TSK08):</strong> solo este archivo. Cero cambios en
 * componentes, CORS ni cookies; CSP enforced fuera de scope.</p>
 */
import type { NextConfig } from "next";

/**
 * Deriva el origen (`esquema://autoridad`) de la API backend desde
 * `NEXT_PUBLIC_API_URL` para la directiva `connect-src` del CSP.
 *
 * <p>La variable se lee en carga del config (build y `next start`), por lo que el valor
 * corresponde al entorno desplegado (`http://localhost:8080` en dev, URL de Railway en
 * prod) sin hardcodear hosts.</p>
 *
 * @returns el origen de la API (ej. `https://api.ejemplo.com`), o `null` si la variable
 *          está ausente o no es una URL parseable — en ese caso `connect-src` conserva
 *          `'self'` y el host de Stripe sin el origen de la API.
 */
function apiOrigin(): string | null {
  const raw = process.env.NEXT_PUBLIC_API_URL;
  if (!raw) {
    return null;
  }
  try {
    return new URL(raw).origin;
  } catch {
    return null;
  }
}

/** Origen de la API backend para `connect-src` (`null` si no se pudo derivar). */
const apiOrigen = apiOrigin();

/**
 * Política `Content-Security-Policy-Report-Only` final (ver TSDoc del módulo para la
 * justificación directiva por directiva).
 */
const politicaCspReportOnly = [
  "default-src 'self'",
  `script-src 'self' 'unsafe-inline'${process.env.NODE_ENV === "development" ? " 'unsafe-eval'" : ""} https://js.stripe.com`,
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' data: blob:",
  "font-src 'self'",
  `connect-src 'self'${apiOrigen ? ` ${apiOrigen}` : ""} https://api.stripe.com`,
  "frame-src 'self' https://js.stripe.com https://hooks.stripe.com",
  "object-src 'none'",
  "base-uri 'self'",
  "form-action 'self'",
  "frame-ancestors 'none'",
].join("; ");

const nextConfig: NextConfig = {
  /**
   * Registra los headers de seguridad en todas las rutas (PHA16TSK08).
   *
   * @returns arreglo con una entrada `/:path*` que emite los 4 headers del backend más
   *          `Content-Security-Policy-Report-Only`.
   */
  async headers() {
    return [
      {
        source: "/:path*",
        headers: [
          {
            key: "X-Content-Type-Options",
            value: "nosniff",
          },
          {
            key: "X-Frame-Options",
            value: "DENY",
          },
          {
            key: "Referrer-Policy",
            value: "strict-origin-when-cross-origin",
          },
          {
            key: "Permissions-Policy",
            value: "camera=(), microphone=(), geolocation=()",
          },
          {
            key: "Content-Security-Policy-Report-Only",
            value: politicaCspReportOnly,
          },
        ],
      },
    ];
  },
};

export default nextConfig;
