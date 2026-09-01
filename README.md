# EasyMarket — Marketplace P2P con Escrow

**Proyecto de portfolio** que demuestra arquitectura transaccional en un
marketplace peer-to-peer donde la plataforma actúa como intermediario de
confianza: retiene el pago del comprador hasta confirmar la recepción del
producto/servicio, y solo entonces acredita el saldo al vendedor.

> ⚠️ Este proyecto es una **demostración técnica de portfolio**, no un
> sistema de producción. Usa Stripe en modo sandbox y no procesa dinero real.

---

## Estructura del repositorio

```
marketplace-P2P-escrow/
│
├── backend/                    # API REST — Java 21 + Spring Boot 4.1.0 (Maven)
│   ├── src/main/java/          # Código fuente principal
│   ├── src/main/resources/     # Configuración por perfil (application-dev.yml, application-prod.yml)
│   │   └── db/migration/       # Scripts Flyway (V1__init.sql, V2__..., etc.)
│   ├── src/test/               # Tests unitarios y de integración (JUnit 5 + Testcontainers)
│   └── pom.xml                 # Dependencias Maven
│
├── frontend/                   # UI — React / Next.js 16.2.x (TypeScript)
│   ├── src/                    # Componentes, páginas y hooks
│   ├── .env.example            # Plantilla de variables de entorno del frontend
│   └── package.json
│
├── docs/
│   └── stitch-screens/         # Referencia visual de UI generada con Google Stitch
│                               # (no se usa como código — solo como guía de composición)
│
├── docker-compose.yml          # PostgreSQL para desarrollo local
```

## Stack técnico

| Componente      | Tecnología                                      |
|-----------------|-------------------------------------------------|
| Backend         | Java 21, Spring Boot 4.1.0, Maven               |
| Frontend        | React / Next.js 16.2.x, TypeScript 5.9.3        |
| Base de datos   | PostgreSQL (local vía Docker, producción Railway)|
| Migraciones     | Flyway                                          |
| Autenticación   | Spring Security + JWT (cookie httpOnly)         |
| Pagos           | Stripe SDK (modo sandbox)                       |
| Tests backend   | JUnit 5, Testcontainers                         |
| Tests frontend  | Vitest + React Testing Library, Playwright      |
| Deploy backend  | Railway                                         |
| Deploy frontend | Vercel                                          |

## Desarrollo local

### Prerrequisitos
- Java 21
- Maven 3.9+
- Node.js 20+
- Docker (para PostgreSQL)
- Stripe CLI (para webhooks locales)

### Backend
```bash
# Levantar la base de datos
docker compose up -d postgres

# Ejecutar tests
cd backend && mvn test

# Iniciar el servidor (perfil dev por defecto)
cd backend && mvn spring-boot:run
```

### Frontend
```bash
cd frontend
cp .env.example .env.local   # completar con valores reales
npm install
npm run dev
```

### Webhooks Stripe en local
```bash
stripe listen --forward-to http://localhost:8080/webhooks/stripe
# Copiar el whsec_... impreso y asignarlo a STRIPE_WEBHOOK_SECRET
```

### Tests E2E con Playwright (harness de cierre — PHA07TSK05)

El harness E2E valida el entorno local antes de ejecutar los specs y falla con
diagnóstico por gate si falta un servicio. Los cuatro servicios deben estar
corriendo (el harness NO inicia procesos en background):

```bash
# 1. PostgreSQL
docker compose up -d postgres

# 2. Backend con perfil dev (por defecto), con las variables del .env raíz
cd backend
# PowerShell: cargar el .env raíz en el entorno del proceso antes de arrancar
Get-Content ..\.env | ForEach-Object {
  if ($_ -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
    [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
  }
}
mvn spring-boot:run

# 3. Stripe CLI reenviando al webhook local (en otra terminal)
stripe listen --forward-to http://localhost:8080/webhooks/stripe
# Copiar el whsec_... impreso a STRIPE_WEBHOOK_SECRET en el .env raíz
# y reiniciar el backend

# 4. Frontend: lo levanta el propio webServer de Playwright; opcional en
#    otra terminal: cd frontend && npm run dev
```

Ejecutar el harness (levanta el frontend vía webServer, valida todo y corre
los specs):

```bash
cd frontend
npm run test:e2e
```

Gates que valida: PostgreSQL en 5432; `GET /health` 200 con perfil dev (seed
demo presente); CORS del backend aceptando `http://localhost:3000`; frontend en
`:3000`; proceso `stripe listen --forward-to http://localhost:8080/webhooks/stripe`
activo; `STRIPE_WEBHOOK_SECRET` y `NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY` reales
(no-placeholder); `ADMIN_EMAIL`/`ADMIN_PASSWORD` definidas en el `.env` raíz; y
ningún archivo versionado con valores de clave.

Variables locales (archivos ignorados por Git, nunca versionados):

- Raíz `.env`: `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`, `ADMIN_EMAIL`,
  `ADMIN_PASSWORD`.
- `frontend/.env.local`: `NEXT_PUBLIC_API_URL`,
  `NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY`.
