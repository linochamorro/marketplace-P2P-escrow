# EasyMarket — Marketplace P2P con Escrow

Marketplace peer-to-peer donde la plataforma actúa como intermediario de
confianza: retiene el pago del comprador en escrow hasta confirmar la
recepción del producto y solo entonces acredita el saldo al vendedor. Si
hay disputa, un admin la resuelve de forma binaria con trazabilidad total.

[![CI](https://github.com/linochamorro/marketplace-P2P-escrow/actions/workflows/ci.yml/badge.svg)](https://github.com/linochamorro/marketplace-P2P-escrow/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-blue)
![Spring Boot 4.1.0](https://img.shields.io/badge/Spring_Boot-4.1.0-brightgreen)
![Next.js 16](https://img.shields.io/badge/Next.js-16-black)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-336791)
![Stripe sandbox](https://img.shields.io/badge/Stripe-sandbox-635BFF)

> ⚠️ Proyecto de **portfolio técnico**, no un sistema de producción. Usa
> Stripe en modo sandbox, no mueve dinero real y el "saldo" del vendedor
> es un ledger contable interno (sin payouts — ver alcance).

---

## Tabla de contenidos

- [Demo](#demo)
- [Qué demuestra](#qué-demuestra)
- [Arquitectura](#arquitectura)
- [Stack técnico](#stack-técnico)
- [Máquinas de estados](#máquinas-de-estados)
- [API — endpoints principales](#api--endpoints-principales)
- [Desarrollo local](#desarrollo-local)
- [Testing](#testing)
- [Integración continua y despliegue](#integración-continua-y-despliegue)
- [Seguridad](#seguridad)
- [Estructura del repositorio](#estructura-del-repositorio)
- [Metodología](#metodología)
- [Estado del proyecto](#estado-del-proyecto)
- [Licencia](#licencia)

---

## Demo

| Pieza | URL |
|---|---|
| API (health) | `https://marketplace-p2p-escrow-production.up.railway.app/health` |
| Frontend | Desplegado en Vercel (ver URL en el dashboard del proyecto) |

Cuentas demo (perfil `dev`, seed `db/dev/R__seed_demo.sql`):

| Rol | Email | Password |
|---|---|---|
| Vendedor | `vendedor@easymarket.dev` | `VendedorPass123!` |
| Comprador | `comprador@easymarket.dev` | `CompradorPass123!` |
| Admin | vía `ADMIN_EMAIL` del `.env` raíz | vía variable local (nunca versionada) |

## Qué demuestra

- **Escrow transaccional real:** `POST /compras` crea el `PaymentIntent`
  (captura inmediata, PEN en centavos); el webhook
  `payment_intent.succeeded` crea la transacción `reservada` con
  decremento atómico de stock y snapshot inmutable de precio — todo en
  una sola transacción de BD. Sin estados intermedios "stock apartado
  sin pago".
- **Carrera de stock con perdedor reembolsado:** ante la última unidad,
  gana el timestamp más temprano; el pago perdedor genera una orden
  durable de reembolso (outbox) procesada idempotentemente.
- **Saga con outbox para reembolsos:** cancelaciones y disputas a favor
  del comprador emiten `Refund.create` vía `stripe_refund_outbox`
  (clave determinista, reintentos sin duplicar).
- **Webhooks idempotentes y autenticados:** firma `Stripe-Signature`
  verificada antes de tocar la BD + tabla `processed_stripe_events`.
- **Automatización temporal:** jobs `@Scheduled` con
  `FOR UPDATE SKIP LOCKED` (auto-confirmación a las 48h, avisos de
  envío, avisos diarios) calculados en hora de Perú (UTC-5).
- **Ledger contable append-only:** `saldo_disponible` cacheado +
  `movimientos_saldo` y `transaccion_eventos` protegidos por triggers
  PostgreSQL que rechazan `UPDATE`/`DELETE`.
- **Moderación y catálogo:** publicaciones con revisión admin,
  categorías/subcategorías, código de producto autogenerado
  (`2026ELE00001`: año + prefijo de categoría + consecutivo global vía
  trigger).
- **Auth con rate limiting escalado:** 5min → 30min → 24h → permanente
  (solo admin desbloquea), JWT en cookie `httpOnly`, validación de
  `Origin` anti-CSRF y Bean Validation en login/registro.
- **Notificaciones in-app accionables por rol** con campana, badge y
  navegación al flujo correspondiente.

## Arquitectura

```
┌──────────────┐  HTTPS + cookie jwt   ┌──────────────────┐  JDBC      ┌────────────┐
│  Next.js 16  │ ───────────────────▶  │  Spring Boot 4   │ ─────────▶ │ PostgreSQL │
│  (Vercel)    │ ◀───────────────────  │  (Railway, 1      │            │ + Flyway   │
└──────────────┘   JSON                │   instancia)     │            │  V1…V23    │
       │                               └──────────────────┘            └────────────┘
       │ Stripe.js (PaymentElement)            │ @Scheduled (48h, diarios)
       ▼                                       ▼
┌──────────────┐                      ┌──────────────────┐
│ Stripe test  │ ◀── webhooks HMAC ── │ stripe-java      │
│ mode         │    (Stripe CLI local) │ 32.1.0           │
└──────────────┘                      └──────────────────┘
```

Decisiones clave (detalle en `plan.md`): captura inmediata en vez de
auth-hold (las ventanas de escrow superan la expiración de
autorizaciones Stripe); ledger interno en vez de Stripe Connect;
polling por timestamp en vez de countdowns en memoria; Flyway con
`ddl-auto=validate` (cero `update`/`create`).

## Stack técnico

| Componente | Tecnología |
|---|---|
| Backend | Java 21, Spring Boot 4.1.0, Maven 3.9.16 |
| Frontend | React 19.2.4, Next.js 16.3.5, TypeScript 5.9.3 |
| Base de datos | PostgreSQL 16 + Flyway (V1–V23, `ddl-auto=validate`) |
| Auth | Spring Security + JWT en cookie `httpOnly; Secure; SameSite=None` |
| Pagos | `stripe-java` 32.1.0, modo sandbox, captura inmediata |
| API docs | springdoc-openapi (Swagger UI en dev) |
| Tests backend | JUnit 5 + Testcontainers (478 tests) |
| Tests frontend | Vitest + React Testing Library (25 archivos / 213 tests), Playwright E2E |
| CI | GitHub Actions (`.github/workflows/ci.yml`) |
| Deploy backend | Railway (rama `main`, `rootDirectory: /backend`) |
| Deploy frontend | Vercel |
| Moneda / zona horaria | PEN en centavos (enteros, nunca float) · America/Lima |

## Máquinas de estados

**Publicación:** `pendiente_revisión` → `aprobada` ·
`pendiente_revisión` → `cambios_solicitados` ⇄ `pendiente_revisión` ·
`pendiente_revisión` → `rechazada` → (editar→`pendiente_revisión` |
eliminar) · `aprobada` ⇄ `oculta` (stock 0 ⇄ reposición).

**Transacción:** `reservada` → `enviado` → `entregado` → `recibido` ·
`entregado` + 48h sin acción → `recibido_sin_respuesta` · `entregado` +
reclamo 48h → `disputa` → `completada` (vendedor) | `cancelada`
(comprador) · `reservada`/`enviado` + motivo → `cancelada`.

Toda transición con actor exige motivo en cancelaciones/rechazos y se
registra en el log append-only.

## API — endpoints principales

| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/health` | Health check público |
| `POST` | `/auth/registro`, `/auth/login`, `/auth/logout` | Auth (cookie `jwt`) |
| `GET` | `/usuarios/me`, `/usuarios/me/saldo` | Identidad y saldo + movimientos |
| `GET` | `/categorias` | Árbol público de categorías |
| `POST/PUT/DELETE` | `/categorias/**` | Catálogo (ADMIN) |
| `GET` | `/publicaciones`, `/publicaciones/{id}`, `/publicaciones/mias` | Listado con filtros/orden, detalle, propias |
| `POST` | `/publicaciones` | Publicar (→ `pendiente_revisión`) |
| `PATCH` | `/publicaciones/{id}`, `/corregir`, `/moderar` | Editar, corregir, moderar (ADMIN) |
| `DELETE` | `/publicaciones/{id}` | Eliminar propia (409 si tiene transacciones) |
| `POST` | `/compras` | Comprar (header `Idempotency-Key`) |
| `PATCH` | `/transacciones/{id}/enviar\|/entregar\|/confirmar\|/reclamar\|/cancelar` | Ciclo de vida del escrow |
| `GET` | `/transacciones/compras\|/ventas\|/{id}` | Consultas por actor (terceros: 404) |
| `PATCH` | `/disputas/{id}/resolver` | Resolución binaria (ADMIN) |
| `GET` | `/notificaciones`, `/notificaciones/no-leidas/count` | Centro y badge |
| `PATCH` | `/notificaciones/{id}/leer` | Marcar leída (idempotente) |
| `GET` | `/admin/tablero`, `/admin/disputas`, `/admin/usuarios/bloqueados` | Operación (ADMIN) |
| `POST` | `/admin/usuarios/{id}/desbloquear` | Desbloqueo (ADMIN) |
| `POST` | `/webhooks/stripe` | Webhook Stripe (HMAC, raw body) |

Errores con formato `{"mensaje": "..."}`. Documentación interactiva
generada en dev vía springdoc-openapi.

## Desarrollo local

### Prerrequisitos

- Java 21 · Maven 3.9+ · Node.js 20+ · Docker · Stripe CLI

### 1. Variables de entorno

```bash
# Backend (raíz): plantilla versionada, valores reales solo en local
cp .env.example .env   # completar: DB_*, STRIPE_*, JWT_SECRET, FRONTEND_URL, ADMIN_*

# Frontend
cd frontend
cp .env.example .env.local   # NEXT_PUBLIC_API_URL, NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY
```

Ver [.env.example](./.env.example) y
[frontend/.env.example](./frontend/.env.example). Los archivos `.env`
reales están ignorados por Git y nunca se versionan.

### 2. Backend

```bash
# PostgreSQL local
docker compose up -d postgres

# Cargar el .env raíz en el proceso (PowerShell, desde backend/)
Get-Content ..\.env | ForEach-Object {
  if ($_ -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
    [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
  }
}

# Tests y arranque (perfil dev por defecto + seed demo)
mvn test
mvn spring-boot:run
```

### 3. Frontend

```bash
cd frontend
npm ci
npm run dev
```

### 4. Webhooks Stripe en local

```bash
stripe listen --forward-to http://localhost:8080/webhooks/stripe
# Copiar el whsec_... impreso a STRIPE_WEBHOOK_SECRET en el .env raíz
# y reiniciar el backend
```

### Tests E2E con Playwright (harness PHA07TSK05)

El harness valida el entorno local antes de ejecutar los specs y falla
con diagnóstico por gate si falta un servicio. Los cuatro servicios
deben estar corriendo (el harness NO inicia procesos en background):
PostgreSQL, backend `dev` (paso 2), `stripe listen` (paso 4) y
frontend (lo levanta el `webServer` de Playwright; opcional
`npm run dev` en otra terminal).

```bash
cd frontend
npm run test:e2e
```

Gates: PostgreSQL en 5432 · `GET /health` 200 con perfil dev (seed demo
presente) · CORS aceptando `http://localhost:3000` · frontend en
`:3000` · `stripe listen --forward-to
http://localhost:8080/webhooks/stripe` activo ·
`STRIPE_WEBHOOK_SECRET` y `NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY` reales
(no-placeholder) · `ADMIN_EMAIL`/`ADMIN_PASSWORD` en el `.env` raíz ·
cero secretos versionados.

## Testing

```bash
# Backend — suite completa (Testcontainers, Docker requerido)
cd backend && mvn clean test        # 478 tests, 0 fallos (2 skips: Stripe sandbox sin clave)

# Frontend
cd frontend
npm run lint          # ESLint, 0 errores
npx tsc --noEmit      # TypeScript estricto, limpio
npm test              # Vitest: 25 archivos / 213 tests
npm run build         # build de producción, 17 rutas
```

El CI (`.github/workflows/ci.yml`) ejecuta exactamente estos gates en
cada push/PR: job backend (`ubuntu-latest`, Temurin 21, `mvn -B -ntp
clean test` con Docker del runner) y job frontend (Node 20, `npm ci` +
los 5 gates). Sin secretos ni despliegue desde CI.

## Integración continua y despliegue

- **CI:** GitHub Actions en push/PR. La primera corrida real solo corre
  tras push a GitHub.
- **Backend:** Railway, servicio `marketplace-P2P-escrow`, rama `main`,
  `rootDirectory: /backend`. Cada push a `main` redespliega y Flyway
  aplica las migraciones pendientes sobre la BD de producción.
- **Frontend:** Vercel conectado al repo (producción desde `main`,
  previews por PR).
- **Migraciones:** versionadas `V1–V23` + seed repeatable solo-dev
  (`db/dev/R__seed_demo.sql`, jamás en producción).

## Seguridad

- JWT en cookie `httpOnly; Secure; SameSite=None` (nunca localStorage).
- Validación de `Origin`/`Referer` contra allowlist CORS en
  POST/PUT/PATCH/DELETE (403 JSON, exento `/webhooks/stripe`).
- Bean Validation en login/registro (400 antes de tocar dominio).
- Rate limiting persistente por `email+IP` con escalado y desbloqueo
  admin auditado.
- Webhooks Stripe por firma HMAC sobre raw body + idempotencia por
  evento y por compra (`Idempotency-Key`).
- Headers: `X-Content-Type-Options`, `X-Frame-Options: DENY`,
  `Referrer-Policy`, `Permissions-Policy` mínima y CSP en modo
  Report-Only con allowlist de Stripe.
- Dinero siempre en enteros (centavos); secretos solo por variables de
  entorno (`.env.example` como plantilla, cero secretos versionados).

## Estructura del repositorio

```
marketplace-P2P-escrow/
├── backend/                          # API — Java 21 + Spring Boot 4.1.0
│   ├── src/main/java/com/easymarket/marketplace/
│   │   ├── controller/               # REST (auth, publicaciones, compras, transacciones, admin, webhooks)
│   │   ├── service/                  # Dominio (reserva, escrow, disputas, saldo, notificaciones, jobs)
│   │   ├── model/ · repository/      # JPA + Spring Data
│   │   ├── security/ · config/       # JWT, filtro Origin, headers, CORS
│   │   └── dto/ · exception/         # Contratos y errores {"mensaje"}
│   ├── src/main/resources/
│   │   ├── application-dev.yml / application-prod.yml
│   │   └── db/migration/             # Flyway V1–V23 · db/dev/R__seed_demo.sql (solo dev)
│   └── src/test/                     # JUnit 5 + Testcontainers
├── frontend/                         # Next.js 16.3.5 + React 19 + Tailwind
│   ├── src/app/                      # Rutas, paneles y utilidades (shell, mercado, escrow, admin)
│   ├── src/e2e/                      # Specs Playwright + harness de gates
│   ├── next.config.ts                # Headers de seguridad + CSP Report-Only
│   └── .env.example
├── .github/workflows/ci.yml          # CI backend + frontend
├── docs/stitch-screens/              # Referencia visual (Google Stitch, solo composición)
├── .env.example                      # Plantilla backend (copiar a .env, nunca versionar reales)
├── docker-compose.yml                # PostgreSQL local
├── DESIGN.md                         # Sistema de diseño (tokens Deep Navy / Emerald)
└── CHANGELOG.md                      # Historial de tareas cerradas del roadmap
```

## Metodología

Desarrollo dirigido por especificación (SDD manual): `constitution.md`
(principios no negociables) → `spec.md` (user stories) → `plan.md`
(decisiones de arquitectura) → `tasks.md` (roadmap por fases y capas:
dato → dominio → API → UI). Cada tarea se implementa por ciclo
Developer→Tester con TDD estricto, JavaDoc/TSDoc obligatorio y
Artifacts de proceso en `docs/` (no versionados).

## Estado del proyecto

Fase **PHA16 — Estabilización de producción y seguridad: cerrada
13/13** (refund durable al perdedor de stock, upgrade Next.js 16.3.5,
CSRF por `Origin`, validación de auth, ruteo de notificaciones,
headers, `.env.example`, higiene, CI y seed del laptop). Ver detalle en
[CHANGELOG.md](./CHANGELOG.md).

## Licencia

Proyecto de portfolio sin licencia definida — todos los derechos
reservados por defecto. Si quieres reutilizarlo, contacta al autor.
