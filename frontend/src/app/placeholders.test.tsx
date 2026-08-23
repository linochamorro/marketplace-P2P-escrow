import { render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import NotificacionesPage from './notificaciones/page';
import SaldoPage from './saldo/page';

// Mock next/navigation for components that use useRouter (PanelCentroNotificaciones)
const mockPush = vi.fn();
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: mockPush })
}));

/**
 * @file placeholders.test.tsx
 * @description Regresión de las 2 rutas cuyo placeholder se retira en PHA06TSK14.
 *
 * Preserva la cobertura de alcanzabilidad creada en PHA06TSK09, pero invierte su
 * contrato obsoleto: ambas rutas deben presentar su panel funcional y nunca el aviso
 * de construcción. Los contratos de datos completos viven en PHA06TSK14.test.tsx.
 */

const casos: Array<{ titulo: string; pagina: () => React.JSX.Element }> = [
  { titulo: 'Notificaciones', pagina: NotificacionesPage },
  { titulo: 'Saldo', pagina: SaldoPage },
];

/** Implementación de fetch activa antes de cada caso, restaurada al finalizar. */
let fetchAnterior: typeof global.fetch = global.fetch;

beforeEach(() => {
  fetchAnterior = global.fetch;
});

afterEach(() => {
  global.fetch = fetchAnterior;
  vi.restoreAllMocks();
});

describe('Rutas antes placeholder, integradas en PHA06TSK14', () => {
  for (const caso of casos) {
    it(`${caso.titulo}: conserva una cabecera alcanzable y retira el aviso de construcción`, () => {
      global.fetch = vi.fn().mockReturnValue(new Promise(() => {}));
      const Pagina = caso.pagina;
      render(<Pagina />);

      expect(screen.getByRole('heading', { name: new RegExp(caso.titulo, 'i') })).toBeInTheDocument();
      expect(screen.queryByText(/sección en construcción/i)).not.toBeInTheDocument();
    });
  }
});
