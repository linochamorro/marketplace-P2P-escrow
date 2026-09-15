# EasyMarket — Marketplace P2P con Escrow

Marketplace peer-to-peer donde la plataforma actúa como intermediario de
confianza: retiene el pago del comprador en escrow hasta confirmar la
recepción del producto y solo entonces acredita el saldo al vendedor. Si
hay disputa, un administrador la resuelve con trazabilidad total.

[![CI](https://github.com/linochamorro/marketplace-P2P-escrow/actions/workflows/ci.yml/badge.svg)](https://github.com/linochamorro/marketplace-P2P-escrow/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-blue)
![Spring Boot 4.1.0](https://img.shields.io/badge/Spring_Boot-4.1.0-brightgreen)
![Next.js 16](https://img.shields.io/badge/Next.js-16-black)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-336791)
![Stripe sandbox](https://img.shields.io/badge/Stripe-sandbox-635BFF)

> ⚠️ Proyecto de **portfolio técnico**, no un sistema de producción. Usa
> Stripe en modo sandbox, no mueve dinero real y el "saldo" del vendedor
> es un ledger contable interno, sin retiros a dinero real.

---

## Contenido

- [Características](#características)
- [Arquitectura](#arquitectura)
- [Stack](#stack)
- [Estados del negocio](#estados-del-negocio)
- [API](#api)
- [Desarrollo local](#desarrollo-local)
- [Tests](#tests)
- [Despliegue](#despliegue)
- [Seguridad](#seguridad)
- [Estructura del repositorio](#estructura-del-repositorio)
- [Licencia](#licencia)

---

## Características

- **Compra con escrow:** el pago se captura de inmediato (PEN, en
  centavos) y el webhook `payment_intent.succeeded` crea la transacción
  `reservada` con decremento atómico de stock y snapshot inmutable de
  precio, todo en una sola transacción de base de datos.
- **Disputa por la última unidad:** ante compras simultáneas gana el
  timestamp más temprano; el pago perdedor genera una orden durable de
  reembolso total, procesada sin duplicados.
- **Reembolsos por saga con outbox:** cancelaciones y disputas a favor
  del comprador emiten el reembolso a Stripe con clave idempotente
  determinista y reintentos seguros.
- **Webhooks verificados:** firma `Stripe-Signature` validada antes de
  tocar la base de datos, idempotencia por evento y por compra.
- **Automatización por tiempos:** confirmación automática a las 48h sin
  respuesta, avisos de envío pendiente y avisos diarios, con hora de
  Perú (UTC-5).
- **Ledger auditable:** saldo cacheado + movimientos y eventos de
  transacción append-only protegidos a nivel de base de datos.
- **Catálogo moderado:** publicaciones con revisión, categorías y
  subcategorías, código de producto autogenerado e imágenes.
- **Cuentas con rate limiting:** bloqueo escalado ante intentos fallidos
  y desbloqueo administrativo auditado.
- **Notificaciones in-app** según rol, con accesos directos al flujo
  correspondiente.

## Arquitectura

```
┌──────────────┐  HTTPS + cookie de sesión  ┌──────────────────┐  JDBC     ┌────────────┐
│  Next.js 16  │ ─────────────────────────▶ │  Spring Boot 4   │ ────────▶ │ PostgreSQL │
│  (Vercel)    │ ◀───────────────────────── │  (Railway)       │           │ + Flyway   │
└──────────────┘   JSON                     └──────────────────┘           └────────────┘
       │ Stripe.js (PaymentElement)                 │ jobs programados
       ▼                                            ▼
┌──────────────┐                           ┌──────────────────┐
│ Stripe test  │ ◀── webhooks firmados ─── │ stripe-java      │
│ mode         │    (Stripe CLI en local)  │                  │
└──────────────┘                           └──────────────────┘
```

Decisiones principales: captura inmediata en lugar de auth-hold (las
ventanas del escrow superan la vigencia de una autorización); ledger
interno en lugar de transferencias reales; temporizadores por
timestamp en base de datos; migraciones Flyway versionadas con
validación estricta de esquema (`ddl-auto=validate`).

## Stack

| Componente | Tecnología |
|---|---|
| Backend | Java 21, Spring Boot 4.1.0, Maven 3.9.16 |
| Frontend | React 19, Next.js 16.3.5, TypeScript 5.9 |
| Base de datos | PostgreSQL 16 + Flyway |
| Sesión | Spring Security + JWT en cookie `httpOnly; Secure; SameSite=None` |
| Pagos | Stripe en modo sandbox, captura inmediata |
| Tests backend | JUnit 5 + Testcontainers |
| Tests frontend | Vitest + React Testing Library, Playwright |
| CI | GitHub Actions |
| Despliegue | Railway (API) · Vercel (web) |
| Moneda / zona horaria | Soles en centavos (enteros) · America/Lima |

## Estados del negocio

**Publicación:** `pendiente_revisión` → `aprobada` ·
`pendiente_revisión` → `cambios_solicitados` ⇄ `pendiente_revisión` ·
`pendiente_revisión` → `rechazada` (editable o eliminable) ·
`aprobada` ⇄ `oculta` (sin stock ⇄ con stock).

**Transacción:** `reservada` → `enviado` → `entregado` → `recibido` ·
`entregado` + 48h sin respuesta → `recibido_sin_respuesta` ·
`entregado` + reclamo → `disputa` → `completada` | `cancelada` ·
`reservada`/`enviado` + motivo → `cancelada`.

Las cancelaciones y rechazos exigen motivo y cada transición queda en
el log de auditoría.

## API

| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/health` | Salud del servicio |
| `POST` | `/auth/registro`, `/auth/login`, `/auth/logout` | Autenticación (cookie de sesión) |
| `GET` | `/usuarios/me`, `/usuarios/me/saldo` | Identidad, saldo y movimientos |
| `GET` | `/categorias` | Árbol público de categorías |
| `POST/PUT/DELETE` | `/categorias/**` | Catálogo (admin) |
| `GET` | `/publicaciones`, `/publicaciones/{id}`, `/publicaciones/mias` | Listado con filtros, detalle, propias |
| `POST` | `/publicaciones` | Publicar un producto |
| `PATCH` | `/publicaciones/{id}`, `/corregir`, `/moderar` | Editar, corregir, moderar |
| `DELETE` | `/publicaciones/{id}` | Eliminar publicación propia |
| `POST` | `/compras` | Comprar (cabecera `Idempotency-Key`) |
| `PATCH` | `/transacciones/{id}/enviar\|/entregar\|/confirmar\|/reclamar\|/cancelar` | Ciclo del escrow |
| `GET` | `/transacciones/compras\|/ventas\|/{id}` | Consultas por actor |
| `PATCH` | `/disputas/{id}/resolver` | Resolución binaria (admin) |
| `GET` | `/notificaciones`, `/notificaciones/no-leidas/count` | Centro y contador |
| `PATCH` | `/notificaciones/{id}/leer` | Marcar como leída |
| `GET` | `/admin/tablero`, `/admin/disputas`, `/admin/usuarios/bloqueados` | Operación (admin) |
| `POST` | `/admin/usuarios/{id}/desbloquear` | Desbloqueo (admin) |
| `POST` | `/webhooks/stripe` | Webhook de Stripe (firma HMAC) |

Los errores responden `{"mensaje": "..."}`. En desarrollo hay
documentación interactiva autogenerada (springdoc-openapi).

## Desarrollo local

### Prerrequisitos

Java 21 · Maven 3.9+ · Node.js 20+ · Docker · Stripe CLI

### 1. Variables de entorno

```bash
# Backend (raíz): plantilla versionada, valores reales solo en local
cp .env.example .env   # completar: DB_*, STRIPE_*, JWT_SECRET, FRONTEND_URL, ADMIN_*

# Frontend
cd frontend
cp .env.example .env.local   # NEXT_PUBLIC_API_URL, NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY
```

Ver [.env.example](./.env.example) y
[frontend/.env.example](./frontend/.env.example). Los `.env` reales
están ignorados por Git y nunca se versionan.

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

# Tests y arranque (perfil dev + datos demo)
mvn test
mvn spring-boot:run
```

Cuentas demo del seed local:

| Rol | Email | Password |
|---|---|---|
| Vendedor | `vendedor@easymarket.dev` | `VendedorPass123!` |
| Comprador | `comprador@easymarket.dev` | `CompradorPass123!` |
| Admin | vía `ADMIN_EMAIL` del `.env` | vía variable local, nunca versionada |

### 3. Frontend

```bash
cd frontend
npm ci
npm run dev
```

### 4. Webhooks de Stripe en local

```bash
stripe listen --forward-to http://localhost:8080/webhooks/stripe
# Copiar el whsec_... impreso a STRIPE_WEBHOOK_SECRET en el .env raíz
# y reiniciar el backend
```

### Tests E2E con Playwright

El harness valida el entorno local antes de correr los specs y falla
con diagnóstico si falta un servicio. Los cuatro deben estar
corriendo (el harness no inicia procesos): PostgreSQL, backend `dev`
(paso 2), `stripe listen` (paso 4) y frontend (lo levanta el
`webServer` de Playwright; opcional `npm run dev` aparte).

```bash
cd frontend
npm run test:e2e
```

## Tests

```bash
# Backend (requiere Docker por Testcontainers)
cd backend && mvn clean test

# Frontend
cd frontend
npm run lint          # ESLint sin errores
npx tsc --noEmit      # TypeScript estricto
npm test              # Vitest + React Testing Library
npm run build         # build de producción
```

El workflow de CI (`.github/workflows/ci.yml`) corre estos mismos
gates en cada push y pull request: job de backend en Ubuntu con JDK 21
y Docker del runner, y job de frontend con Node 20.

## Despliegue

- **API:** Railway desde la rama `main` (`rootDirectory: /backend`).
  Cada push redespliega y Flyway aplica las migraciones pendientes.
- **Web:** Vercel conectado al repositorio (producción desde `main`,
  previews por pull request).
- **Base de datos:** PostgreSQL en Railway para producción, Docker
  local para desarrollo. El seed demo solo existe en el perfil `dev` y
  jamás se ejecuta en producción.

## Seguridad

- Sesión en cookie `httpOnly` (inaccesible para JavaScript).
- Validación de `Origin` en métodos de escritura, con exención del
  webhook de Stripe.
- Validación de entrada en login/registro y rate limiting persistente.
- Webhooks por firma HMAC sobre el cuerpo crudo, con idempotencia por
  evento y por compra.
- Cabeceras `X-Content-Type-Options`, `X-Frame-Options: DENY`,
  `Referrer-Policy`, `Permissions-Policy` mínima y CSP en modo
  Report-Only con allowlist de Stripe.
- Dinero solo en enteros y secretos solo en variables de entorno.

## Estructura del repositorio

```
marketplace-P2P-escrow/
├── backend/                          # API — Java 21 + Spring Boot
│   ├── src/main/java/.../            # controllers, services, model, repository,
│   │                                 # security, config, dto, exception
│   ├── src/main/resources/
│   │   ├── application-dev.yml / application-prod.yml
│   │   └── db/migration/             # Flyway V1–V23 · db/dev/seed solo-dev
│   └── src/test/                     # JUnit 5 + Testcontainers
├── frontend/                         # Next.js + React + Tailwind
│   ├── src/app/                      # Rutas y paneles (mercado, escrow, admin)
│   ├── src/e2e/                      # Specs Playwright + validación de entorno
│   ├── next.config.ts                # Cabeceras de seguridad + CSP Report-Only
│   └── .env.example
├── .github/workflows/ci.yml          # CI backend + frontend
├── docs/stitch-screens/              # Referencia visual de pantallas
├── .env.example                      # Plantilla del backend (copiar a .env)
└── docker-compose.yml                # PostgreSQL local
```

## Licencia

Sin licencia definida — todos los derechos reservados.
