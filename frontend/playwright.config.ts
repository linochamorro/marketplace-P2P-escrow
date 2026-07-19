import { defineConfig, devices } from '@playwright/test';

/**
 * Configuración de Playwright para pruebas de integración End-to-End (E2E).
 * Define el directorio de pruebas en './src/e2e', configura un servidor local en el puerto 3000
 * y usa un proyecto basado en Chromium para la ejecución de pruebas.
 */
export default defineConfig({
  testDir: './src/e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: 'html',
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
  },
});
