# Arquitectura del sistema — EasyMarket

Este documento describe la arquitectura **efectiva** del código versionado en este repositorio: componentes, ubicación real de fuentes y pruebas, flujo de datos y decisiones técnicas que gobiernan el comportamiento. No describe un estado futuro ni una estructura planificada.

## Componentes

| Capa | Tecnología | Ubicación real en el repositorio |
|---|---|---|
| Cliente web | Next.js 16 / React 19 / TypeScript | `frontend/src/app` (rutas y paneles) |
| API | Spring Boot 4 / Java 21 / Maven | `backend/src/main` (código y recursos) |
| Persistencia | PostgreSQL 16 + Flyway | `backend/src/main/resources/db/migration` (V1–V23) y `backend/src/main/resources/db/dev` (seed `R__seed_demo.sql`, solo perfil `dev`) |
| Pagos | Stripe en modo sandbox, captura inmediata y webhooks firmados | Integración `stripe-java` en el backend y `Stripe.js`/PaymentElement en el cliente |

El backend y el frontend se ejecutan como procesos independientes en desarrollo (`mvn spring-boot:run` y `npm run dev`). `docker-compose.yml` describe únicamente la infraestructura de base de datos de referencia; la instancia de desarrollo local puede ser también un PostgreSQL nativo aislado.

## Flujo de datos

1. El navegador carga las rutas de Next.js (`frontend/src/app`), que consumen la API JSON de Spring Boot. La sesión viaja en una cookie JWT con atributos `HttpOnly; Secure; SameSite=None`.
2. La API persiste en PostgreSQL mediante JDBC/JPA. Flyway aplica las migraciones versionadas V1–V23 al arrancar; con el perfil `dev` añade la ubicación `classpath:db/dev`, que ejecuta el seed demostrativo. El perfil de producción no incluye esa ubicación, por lo que el seed nunca se ejecuta allí.
3. El pago se inicia desde el PaymentElement de Stripe y se **captura de inmediato**. El webhook `payment_intent.succeeded`, con la firma `Stripe-Signature` validada HMAC sobre el cuerpo crudo antes de cualquier escritura, crea la transacción en `reservada`: descuento atómico de stock y snapshot inmutable de precio en una sola transacción de base de datos.
4. Las cancelaciones y disputas a favor del comprador emiten reembolsos mediante una saga con patrón outbox, clave idempotente determinista y reintentos seguros. La idempotencia se refuerza por evento de Stripe y por compra.
5. El saldo del vendedor es un **ledger contable interno**: saldo cacheado más movimientos y eventos de transacción en un log append-only protegido a nivel de base de datos. No hay transferencias ni retiros a dinero real.
6. Los temporizadores del escrow (confirmación automática a las 48 h, avisos de envío y notificaciones diarias) se resuelven por timestamps en base de datos, con zona horaria `America/Lima` (UTC-5). Los montos se almacenan siempre como enteros en centavos.

## Decisiones técnicas

- **Captura inmediata en lugar de autorización diferida**: las ventanas del escrow superan la vigencia de una autorización de pago.
- **Ledger interno en lugar de dinero real**: el "saldo" es contable y no implica movimientos de fondos externos.
- **Idempotencia explícita** en compras (`Idempotency-Key`), eventos de webhook y reembolsos, para que reintentos y entregas duplicadas no generen efectos dobles.
- **Webhooks firmados**: se valida la firma antes de tocar la base de datos; las cabeceras de seguridad y la validación de `Origin` complementan el control de acceso.
- **Esquema validado**: `spring.jpa.hibernate.ddl-auto=validate` junto con migraciones Flyway versionadas; la aplicación no modifica el esquema por su cuenta.
- **Configuración por entorno**: secretos y parámetros de conexión se leen de variables de entorno (`application-dev.yml` / `application-prod.yml`); los valores sensibles no se versionan.

## Pruebas y su ubicación real

- `backend/src/test`: pruebas JUnit 5 con Testcontainers (requieren Docker disponible al ejecutar `mvn test`).
- `frontend/src`: pruebas unitarias y de componente con Vitest y React Testing Library.
- `frontend/src/e2e`: especificaciones Playwright y validación de entorno (`playwright.config.ts`, `global-setup.ts`, `entorno.ts`).

El repositorio **no contiene un directorio `tests/` separado**: las pruebas conviven con cada aplicación. Esa es la disposición efectiva del código; no se ha movido ninguna prueba. Una reorganización futura exigiría ajustar imports, configuración de Vitest/Playwright/TypeScript/Maven y CI, sin modificar aserciones.

La suite E2E completa (`npm run test:e2e` en `frontend/`) ejecuta `playwright test` con un `globalSetup` que valida diez comprobaciones antes de los specs: PostgreSQL en `127.0.0.1:5432`, backend con perfil `dev` (`/health` → 200 y seed presente), CORS, frontend en `localhost:3000`, Stripe CLI reenviando a `/webhooks/stripe`, claves Stripe reales (no placeholders), variables `ADMIN_EMAIL`/`ADMIN_PASSWORD` definidas y ausencia de secretos en archivos versionados. Por tanto requiere PostgreSQL, backend dev, frontend y Stripe CLI levantados. La navegación y captura local de vistas es una actividad distinta y no necesita Stripe CLI.

## Puesta en marcha

Requisitos, creación de la base de datos, carga de variables de entorno y comandos de arranque se describen en el [README del repositorio](../../README.md). El perfil `dev` es el predeterminado y es el único que carga el seed demostrativo.

## Documentación relacionada

- [README del repositorio](../../README.md)
- [Decisiones técnicas de formato y visibilidad](../decisions/index.md)
- [Galería de capturas funcionales](../images/README.md)
