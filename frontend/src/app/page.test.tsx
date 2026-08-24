import { render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import Home from './page';
import { ErrorApiSesion, type UsuarioActualUI } from './sesion-utils';

/**
 * @file page.test.tsx
 * @description Pruebas de la home / de EasyMarket (PHA06TSK09).
 *
 * Decisión de producto (ver spec.md, story "Inicio"): con sesión válida la
 * home redirige a /publicaciones (mercado); sin sesión muestra la landing
 * EasyMarket con llamada a la acción hacia /auth.
 */

const { mockReplace } = vi.hoisted(() => ({ mockReplace: vi.fn() }));

vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace: mockReplace }),
}));

const USUARIO: UsuarioActualUI = { id: 7, email: 'vendedor@easymarket.dev', rol: 'USUARIO' };

describe('Home / (PHA06TSK09)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    mockReplace.mockClear();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('con sesión válida redirige a /publicaciones', async () => {
    render(<Home obtenerUsuarioActual={async () => USUARIO} />);

    await waitFor(() => expect(mockReplace).toHaveBeenCalledWith('/publicaciones'));
  });

  it('sin sesión muestra la landing EasyMarket con CTA hacia /auth y no redirige', async () => {
    render(
      <Home
        obtenerUsuarioActual={async () => {
          throw new ErrorApiSesion('No autorizado', 403);
        }}
      />
    );

    expect(await screen.findByRole('heading', { name: /EasyMarket/i })).toBeInTheDocument();
    const cta = screen.getByRole('link', { name: /iniciar sesión|crear cuenta/i });
    expect(cta).toHaveAttribute('href', '/auth');
    expect(mockReplace).not.toHaveBeenCalled();
  });
});
