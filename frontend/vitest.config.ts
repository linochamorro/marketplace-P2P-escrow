import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import path from 'path';

/**
 * Configuración de Vitest para el entorno de Next.js.
 * Utiliza jsdom para pruebas de componentes React y configura alias de
 * importación '@'.
 *
 * Timeouts de tests y hooks en 15000ms (decisión de Lino 2026-08-31,
 * plan.md § "Estabilidad de pruebas e higiene del repositorio (PHA15)",
 * fila "Timeout global de tests frontend"): los 5000ms por defecto de
 * Vitest fallan intermitentemente bajo carga del entorno (fallos
 * documentados en CHANGELOG 2026-08-31 en `PHA06TSK14.test.tsx:193`,
 * `publicaciones/page.test.tsx:56` y
 * `UsuariosBloqueadosSincronizacionL02.test.tsx:54`). El valor NO
 * enmascara aserciones incorrectas — solo da holgura al entorno lento.
 */
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test/setup.ts',
    include: ['src/**/*.test.{ts,tsx}', 'src/**/*.spec.{ts,tsx}'],
    exclude: ['node_modules', 'dist', '.next', 'src/e2e'],
    // Timeout global de tests y hooks: 15000ms (decisión de Lino
    // 2026-08-31, plan.md § "Estabilidad de pruebas e higiene del
    // repositorio (PHA15)", fila "Timeout global de tests frontend").
    // Los 5000ms por defecto de Vitest fallan intermitentemente bajo
    // carga del entorno (evidencia en CHANGELOG 2026-08-31:
    // PHA06TSK14.test.tsx:193, publicaciones/page.test.tsx:56 y
    // UsuariosBloqueadosSincronizacionL02.test.tsx:54). El valor NO
    // enmascara aserciones incorrectas — solo da holgura al entorno
    // lento.
    testTimeout: 15000,
    hookTimeout: 15000,
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
});
