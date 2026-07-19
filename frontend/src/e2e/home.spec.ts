import { test, expect } from '@playwright/test';

/**
 * Pruebas E2E para la página de inicio.
 * Verifica que la página de inicio responda en el puerto de desarrollo local
 * y contenga el texto descriptivo de inicio de Next.js.
 */
test('has get started title', async ({ page }) => {
  await page.goto('/');

  // Verifica que el h1 de la página de inicio contenga el texto esperado
  const heading = page.locator('h1');
  await expect(heading).toContainText(/To get started, edit the page\.tsx file\./i);
});
