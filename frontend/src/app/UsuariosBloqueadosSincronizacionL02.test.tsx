import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import Shell from './Shell';
import UsuariosBloqueadosPage from './admin/usuarios-bloqueados/page';

/**
 * Demora únicamente el efecto que copia el resultado remoto a un segundo estado local.
 * React no garantiza que un efecto pasivo se ejecute dentro del mismo frame del commit que lo
 * programa; omitirlo en esta prueba vuelve observable la dependencia incorrecta de esa copia.
 */
vi.mock('react', async (importOriginal) => {
  const react = await importOriginal<typeof import('react')>();
  return {
    ...react,
    useEffect: (effect: React.EffectCallback, dependencies?: React.DependencyList) => {
      if (effect.toString().includes('setUsuarios')) return react.useEffect(() => undefined, dependencies);
      return react.useEffect(effect, dependencies);
    },
  };
});

vi.mock('next/navigation', () => ({
  usePathname: () => '/admin/usuarios-bloqueados',
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
}));

/** URL base determinista de la lectura bajo prueba. */
const BASE = 'http://localhost:8080';

/**
 * Construye una respuesta mínima para identidad y usuarios bloqueados.
 *
 * @param body cuerpo JSON de respuesta
 * @returns respuesta HTTP exitosa simulada
 */
function respuesta(body: unknown): Response {
  return { ok: true, status: 200, json: async () => body } as Response;
}

describe('PHA06TSK13-L02 - sincronización visible de usuarios bloqueados', () => {
  beforeEach(() => {
    process.env.NEXT_PUBLIC_API_URL = BASE;
    global.fetch = vi.fn(async (input: RequestInfo | URL) => {
      if (String(input) === `${BASE}/usuarios/me`) {
        return respuesta({ id: 1, email: 'admin@easymarket.dev', rol: 'ADMIN' });
      }
      return respuesta([{ usuarioId: 93, email: 'sin-frame@example.com' }]);
    });
  });

  it('no depende de un efecto pasivo posterior para sustituir loading por la lista remota', async () => {
    render(<Shell><UsuariosBloqueadosPage /></Shell>);

    expect(await screen.findByText('sin-frame@example.com')).toBeInTheDocument();
    expect(screen.queryByText(/cargando datos administrativos/i)).not.toBeInTheDocument();
    expect(global.fetch).toHaveBeenCalledWith(`${BASE}/admin/usuarios/bloqueados`, expect.objectContaining({
      method: 'GET', credentials: 'include', signal: expect.any(AbortSignal),
    }));
  });
});
