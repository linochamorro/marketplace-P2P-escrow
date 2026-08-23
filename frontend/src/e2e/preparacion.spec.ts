/**
 * Spec de preparación del entorno E2E de cierre (PHA07TSK05).
 *
 * <p>ES el "Test E2E de preparación" del criterio de aceptación de la fila:
 * asevera contra el entorno real que (1) los servicios arrancan y son
 * validados, (2) perfiles y CORS coinciden, (3) Stripe CLI está reenviando y
 * (4) los secretos no aparecen en logs ni en el repositorio.</p>
 *
 * <p>Cada test ejecuta la verificación REAL compartida con el
 * {@code globalSetup} de Playwright (módulo {@code entorno.ts}); el spec no
 * mockea nada: hace fetch HTTP real, inspecciona procesos reales, lee los
 * archivos de entorno locales y ejecuta git real.</p>
 *
 * <p>Este spec NO recorre flujos de negocio (compra, moderación, pago): esos
 * escenarios pertenecen a PHA07TSK06-09. Aquí solo se valida el entorno.</p>
 */

import { test, expect } from '@playwright/test';
import {
  verificarPostgres,
  verificarBackend,
  verificarPerfilDev,
  verificarCors,
  verificarFrontend,
  verificarStripeCli,
  verificarWebhookSecretBackend,
  verificarPublishableKeyFrontend,
  verificarAdminVariables,
  verificarSecretosRepo,
  verificarEntornoCompleto,
  textoResultado,
  type ResultadoGate,
} from './entorno';

/**
 * Asevera que un gate del harness pasó, mostrando el diagnóstico completo en
 * caso contrario (el mensaje se incluye en el reporte de Playwright).
 *
 * @param resultado resultado del gate obtenido del módulo de entorno
 */
function aseverarGate(resultado: ResultadoGate): void {
  expect(resultado.ok, textoResultado(resultado)).toBe(true);
}

test.describe('Preparación del entorno E2E (PHA07TSK05)', () => {
  test('PostgreSQL arranca y es validado (puerto 5432)', async () => {
    aseverarGate(await verificarPostgres());
  });

  test('Backend dev arranca y responde GET /health con 200 UP', async () => {
    aseverarGate(await verificarBackend());
  });

  test('Perfil dev activo y cuentas demo presentes (seed R__seed_demo.sql)', async () => {
    aseverarGate(await verificarPerfilDev());
  });

  test('CORS del backend acepta el origen del frontend (http://localhost:3000)', async () => {
    aseverarGate(await verificarCors());
  });

  test('Frontend arranca y responde HTTP en localhost:3000', async () => {
    aseverarGate(await verificarFrontend());
  });

  test('Stripe CLI está reenviando a http://localhost:8080/webhooks/stripe', async () => {
    aseverarGate(await verificarStripeCli());
  });

  test('STRIPE_WEBHOOK_SECRET del backend es real (no-default)', async () => {
    aseverarGate(await verificarWebhookSecretBackend());
  });

  test('NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY del frontend es real (no-default)', async () => {
    aseverarGate(await verificarPublishableKeyFrontend());
  });

  test('Cuentas demo y ADMIN definidas por variables locales ignoradas por Git', async () => {
    aseverarGate(await verificarAdminVariables());
  });

  test('Secretos ausentes de logs y repositorio (git check-ignore y git ls-files)', async () => {
    aseverarGate(await verificarSecretosRepo());
  });

  test('Resumen: los diez gates del harness pasan en conjunto', async () => {
    const resultados = await verificarEntornoCompleto();
    const resumen = resultados.map(textoResultado).join('\n');
    const fallidas = resultados.filter((resultado) => !resultado.ok);
    expect(fallidas.length, `Gates fallidos: ${fallidas.length}/10\n${resumen}`).toBe(0);
  });
});
