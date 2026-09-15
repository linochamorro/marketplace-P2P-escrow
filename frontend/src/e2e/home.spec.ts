import { test, expect } from '@playwright/test';

/**
 * Pruebas E2E para la página de inicio (PHA06TSK09).
 * Verifica que la home sin sesión muestre la landing EasyMarket con la
 * llamada a la acción hacia /auth.
 */
test('muestra la landing EasyMarket con CTA hacia /auth', async ({ page }) => {
  await page.goto('/');

  // Verifica el título de la landing y la llamada a la acción de acceso.
  const heading = page.locator('h1');
  await expect(heading).toContainText(/EasyMarket/i);

  const cta = page.getByRole('link', { name: /iniciar sesión|crear cuenta/i });
  await expect(cta).toBeVisible();
  await expect(cta).toHaveAttribute('href', '/auth');
});
