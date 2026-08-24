import { defineConfig, devices } from '@playwright/test';

/**
 * Configuración de Playwright para pruebas de integración End-to-End (E2E).
 * Define el directorio de pruebas en './src/e2e', configura un servidor local en el puerto 3000
 * y usa un proyecto basado en Chromium para la ejecución de pruebas.
 *
 * Harness de cierre (PHA07TSK05):
 * - `globalSetup`: preparación explícita que valida PostgreSQL, backend dev, frontend y
 *   Stripe CLI antes de los specs, con diagnóstico por gate y comando sugerido.
 * - `reporter`: se agrega el reportero 'list' a 'html' para que la consola muestre el detalle
 *   de cada gate (evidencia requerida por el ciclo TDD); el reporte HTML se genera sin abrirse.
 * - `webServer.timeout`: 180s para cubrir la primera compilación de Next.js en dev.
 *   No se usa `webServer` como array: el harness solo necesita levantar/reutilizar el frontend;
 *   backend, PostgreSQL y Stripe CLI se validan (nunca se inician) desde el globalSetup.
 */
export default defineConfig({
  testDir: './src/e2e',
  globalSetup: './src/e2e/global-setup.ts',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: 'http://localhost:3000',
    trace: 'on-first-retry',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:3000',
    reuseExistingServer: !process.env.CI,
    timeout: 180_000,
  },
});
