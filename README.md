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
