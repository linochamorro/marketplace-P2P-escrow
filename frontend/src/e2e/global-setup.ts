/**
 * GlobalSetup del harness E2E de cierre (PHA07TSK05).
 *
 * <p>Preparación explícita de Playwright que valida los cuatro servicios del
 * entorno local ANTES de ejecutar los specs y falla con diagnóstico accionable
 * y comando sugerido por gate si falta cualquiera:</p>
 *
 * <ul>
 *   <li>PostgreSQL (docker compose / puerto 5432).</li>
 *   <li>Backend con perfil {@code dev} (GET /health 200 + seed demo presente).</li>
 *   <li>Frontend HTTP en localhost:3000 (levantado o reutilizado por el {@code webServer}).</li>
 *   <li>Stripe CLI reenviando a {@code http://localhost:8080/webhooks/stripe}.</li>
 * </ul>
 *
 * <p>Además valida las claves reales (no-default), las variables ADMIN
 * definidas y la ausencia de secretos en el repositorio. No inicia ningún
 * proceso en background: solo valida servicios ya corriendo (decisión de Lino
 * 2026-08-16, plan.md Fase 7 — Harness PHA07TSK05, filas "mecanismo" y
 * "arranque").</p>
 *
 * <p>Interacción con {@code webServer}: en Playwright el plugin del
 * {@code webServer} (plugin setup) se ejecuta antes de las funciones de
 * {@code globalSetup}, por lo que el frontend ya está disponible cuando este
 * módulo valida el entorno. Si el frontend no responde, el {@code webServer}
 * lo reporta al fallar su propio arranque y este gate confirma el estado.</p>
 */

import { verificarEntornoCompleto, textoResultado } from './entorno';

/**
 * Ejecuta la validación completa del entorno y lanza un error con el
 * diagnóstico de todos los gates fallidos si el entorno no está listo.
 *
 * @returns promesa que resuelve cuando el entorno está validado
 * @throws Error con el detalle de cada gate fallido y su comando sugerido
 */
export default async function globalSetup(): Promise<void> {
  const resultados = await verificarEntornoCompleto();
  const fallidas = resultados.filter((resultado) => !resultado.ok);

  if (fallidas.length === 0) {
    console.log(
      `[global-setup] Entorno E2E validado: ${resultados.length}/${resultados.length} gates OK.`,
    );
    return;
  }

  const detalle = fallidas.map(textoResultado).join('\n');
  throw new Error(
    `[global-setup] Entorno E2E NO listo — ${fallidas.length}/${resultados.length} gates fallaron.\n` +
      `${detalle}\n` +
      'Levante los servicios manualmente según README.md y vuelva a ejecutar npm run test:e2e.',
  );
}
