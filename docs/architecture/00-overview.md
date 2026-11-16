# EasyMarket — estructura de incorporación

Fecha: 2026-11-15. Estado: propuesta CTO, no aprobación de ejecución.

El sistema existente es una webapp con cliente Next.js/React y API Spring Boot/Java, PostgreSQL/Flyway y Stripe sandbox. Los manifiestos locales declaran Java 21, Spring Boot 4.1.0, Next.js 16.3.5 y React 19.2.4. Son versiones observadas; no se evalúa aquí su vigencia, compatibilidad ni vulnerabilidades actuales.

La estructura completa aplica por tener autenticación, persistencia e integración de pagos. Mantener `frontend/src/` y `backend/src/main/`; no fusionar las aplicaciones ni cambiar su arquitectura funcional.

```text
marketplace-P2P-escrow/
  AGENTS.md
  STATE.md
  README.md
  .env.example
  .agents/roles/                  # cinco contratos adaptados
  frontend/                      # Next.js, src/ solo producto
  backend/                       # Maven, src/main/ y recursos definitivos
  tests/
    frontend/                    # unit/component y e2e, con setup/fixtures
    backend/                     # Java y recursos, separación por tipo
  docs/
    architecture/
    decisions/
    runbooks/
    sdd/
      constitution.md
      spec.md
      plan.md
      tasks.md
      packages/
  reports/
    intake/
    implementation/<phase-id>/
    audits/<phase-id>/
    verification/
  temporary/                     # solo si hace falta; retirar al cierre
```

Este árbol es el destino propuesto, no el inventario ya materializado. El producto original ya fue trasladado íntegramente a esta raíz; la adaptación canónica y su verificación corresponden al bloque P-01 en el otro harness. Los clones aislados no reemplazan esta raíz canónica.

Mover las pruebas fuera de `frontend/src` y `backend/src/test` exige actualizar imports, setup, Vitest, Playwright, TypeScript, Maven y CI. Para Java, configurar explícitamente `testSourceDirectory` y `testResources` hacia `tests/backend/`, manteniendo los paquetes Java. La disposición exacta se fijará en el paquete aprobado; no cambiar aserciones para adaptar rutas.

La documentación gobernante se consolidará en `docs/sdd/`. El historial de `docs/avance/` (603 archivos observados) debe conservarse íntegro en una categoría permanente de `reports/`, mediante un mapa origen→destino que preserve IDs PHA/TSK/loops y contenido. Nuevas tareas usarán Fxx-Tyy. El historial no se enviará a `temporary/` ni se renombrará sin trazabilidad.

`ESTADO_PROYECTO.md` se conservará como antecedente histórico; `STATE.md` será la única fuente operativa vigente. La adaptación retirará contradicciones entre reglas antiguas y actuales, sin borrar acuerdos de negocio como atomicidad, montos enteros y auditoría append-only.
