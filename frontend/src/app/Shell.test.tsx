import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import Shell, { DESTINOS_ADMIN, DESTINOS_USUARIO, GRUPOS_USUARIO, esRutaConShell } from './Shell';
import { EVENTO_NOTIFICACION_LEIDA } from './notificaciones-utils';
import { ErrorApiSesion, type UsuarioActualUI } from './sesion-utils';

/**
 * @file Shell.test.tsx
 * @description Pruebas del shell de navegación sensible a sesión y rol (PHA06TSK09)
 * y de la acción de cierre de sesión integrada en la barra (PHA07TSK03).
 *
 * Criterio de aceptación literal de la tarea PHA06TSK09:
 * "navegación de usuario/admin contiene solo destinos autorizados y sesión
 * fallida dirige a /auth".
 *
 * - USUARIO: exactamente los 7 destinos de usuario (mercado, publicar,
 *   mis publicaciones, compras, ventas, notificaciones, saldo).
 * - ADMIN: los 7 de usuario + los 5 administrativos (12 en total).
 * - Sesión fallida (fetch rechazado o 403): router.replace('/auth').
 * - Fuera de rutas autenticadas (/ y /auth) el shell no se renderiza.
 *
 * Criterio de aceptación literal de la tarea PHA07TSK03:
 * "ambos roles ven la acción, ejecutan POST /auth/logout, llegan a /auth y
 * una ruta protegida vuelve a exigir sesión".
 *
 * - USUARIO y ADMIN ven la acción "Cerrar sesión" en la barra.
 * - Al ejecutarla se llama POST /auth/logout con `credentials: 'include'`.
 * - En éxito se limpia el estado local (contenido protegido fuera del DOM)
 *   y se redirige a /auth vía router.replace.
 * - En fallo (HTTP no-2xx o red) NO se redirige: se conserva la sesión local
 *   y se muestra un mensaje de error.
 * - Tras el logout, una ruta protegida vuelve a exigir sesión: el shell
 *   vuelve a consultar GET /usuarios/me y, al fallar (cookie ya expirada),
 *   redirige de nuevo a /auth.
 *
 * Criterio de aceptación literal de la tarea PHA09TSK01:
 * "la marca del header y del sidebar es un Link con href=/publicaciones".
 *
 * - Con sesión autenticada las DOS marcas ("EasyMarket" del header superior
 *   y la de la cabecera del sidebar) son `Link` de `next/link` con
 *   `href="/publicaciones"`, sin alterar identidad visual (mismas clases).
 * - Mientras la sesión resuelve (estado `cargando`) la marca NO es un enlace:
 *   se conserva el contrato previo del shell de no exponer navegación hasta
 *   tener sesión (regresión protegida del test de PHA06TSK09).
 */

// Mocks del router y del pathname (patrón del proyecto, ver auth/auth.test.tsx).
const { mockReplace, mockPathname } = vi.hoisted(() => ({
  mockReplace: vi.fn(),
  mockPathname: vi.fn(() => '/publicaciones'),
}));

vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace: mockReplace }),
  usePathname: () => mockPathname(),
}));

const USUARIO: UsuarioActualUI = { id: 7, email: 'vendedor@easymarket.dev', rol: 'USUARIO' };
const ADMIN: UsuarioActualUI = { id: 1, email: 'admin@easymarket.dev', rol: 'ADMIN' };

describe('Shell (PHA06TSK09)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    mockReplace.mockClear();
    mockPathname.mockReturnValue('/publicaciones');
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  describe('esRutaConShell', () => {
    it('reconoce las rutas autenticadas del mapa de la tarea', () => {
      for (const ruta of ['/publicaciones', '/publicar', '/mis-publicaciones', '/compras', '/ventas', '/notificaciones', '/saldo', '/admin', '/admin/moderacion', '/admin/categorias', '/admin/disputas', '/admin/usuarios-bloqueados']) {
        expect(esRutaConShell(ruta)).toBe(true);
      }
    });

    it('excluye la home y la autenticación del shell', () => {
      expect(esRutaConShell('/')).toBe(false);
      expect(esRutaConShell('/auth')).toBe(false);
    });

    it('mantiene dentro del shell los detalles dinámicos de compras y ventas sin aceptar prefijos ajenos', () => {
      expect(esRutaConShell('/compras/41')).toBe(true);
      expect(esRutaConShell('/ventas/72')).toBe(true);
      expect(esRutaConShell('/compras-falsas/41')).toBe(false);
      expect(esRutaConShell('/ventas-extra/72')).toBe(false);
    });
  });

  it('muestra la marca y Cargando sesión sin enlaces mientras resuelve /usuarios/me', () => {
    mockPathname.mockReturnValue('/publicaciones');
    const pendiente = new Promise<UsuarioActualUI>(() => {});
    render(
      <Shell obtenerUsuarioActual={() => pendiente}>
        <span>Contenido protegido</span>
      </Shell>
    );

    expect(screen.getByText('EasyMarket')).toBeInTheDocument();
    expect(screen.getByText('Cargando sesión…')).toBeInTheDocument();
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
    expect(screen.queryByText('Contenido protegido')).not.toBeInTheDocument();
  });

  it('USUARIO: navegación contiene solo los 7 destinos autorizados de usuario', async () => {
    mockPathname.mockReturnValue('/publicaciones');
    render(
      <Shell obtenerUsuarioActual={async () => USUARIO}>
        <span>Contenido protegido</span>
      </Shell>
    );

    await screen.findByRole('link', { name: 'Mercado' });

    for (const destino of DESTINOS_USUARIO) {
      expect(screen.getByRole('link', { name: destino.etiqueta })).toHaveAttribute('href', destino.ruta);
    }
    for (const destino of DESTINOS_ADMIN) {
      expect(screen.queryByRole('link', { name: destino.etiqueta })).not.toBeInTheDocument();
    }
    // El conteo se acota al <nav>: desde PHA09TSK01 el shell agrega 2 enlaces
    // de marca ("EasyMarket") fuera del nav, que no son destinos de menú.
    const nav = screen.getByRole('navigation', { name: 'Navegación principal' });
    expect(within(nav).getAllByRole('link')).toHaveLength(DESTINOS_USUARIO.length);
    expect(screen.getByText('Contenido protegido')).toBeInTheDocument();
  });

  it('ADMIN: navegación contiene los 7 de usuario y los 5 administrativos', async () => {
    mockPathname.mockReturnValue('/admin');
    render(
      <Shell obtenerUsuarioActual={async () => ADMIN}>
        <span>Contenido protegido</span>
      </Shell>
    );

    await screen.findByRole('link', { name: 'Administración' });

    for (const destino of [...DESTINOS_USUARIO, ...DESTINOS_ADMIN]) {
      expect(screen.getByRole('link', { name: destino.etiqueta })).toHaveAttribute('href', destino.ruta);
    }
    // Conteo acotado al <nav> (los enlaces de marca de PHA09TSK01 viven fuera).
    const nav = screen.getByRole('navigation', { name: 'Navegación principal' });
    expect(within(nav).getAllByRole('link')).toHaveLength(DESTINOS_USUARIO.length + DESTINOS_ADMIN.length);
  });

  it('sesión fallida dirige a /auth y no renderiza el contenido protegido', async () => {
    mockPathname.mockReturnValue('/publicaciones');
    render(
      <Shell obtenerUsuarioActual={async () => { throw new ErrorApiSesion('No autorizado', 403); }}>
        <span>Contenido protegido</span>
      </Shell>
    );

    await waitFor(() => expect(mockReplace).toHaveBeenCalledWith('/auth'));
    expect(screen.queryByText('Contenido protegido')).not.toBeInTheDocument();
  });

  it('en / (home) no consulta la sesión ni renderiza la barra de navegación', () => {
    mockPathname.mockReturnValue('/');
    const obtenerUsuarioActual = vi.fn(async () => USUARIO);
    render(
      <Shell obtenerUsuarioActual={obtenerUsuarioActual}>
        <span>Contenido público</span>
      </Shell>
    );

    expect(screen.getByText('Contenido público')).toBeInTheDocument();
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument();
    expect(obtenerUsuarioActual).not.toHaveBeenCalled();
  });
});

describe('Shell — logout (PHA07TSK03)', () => {
  const apiUrlOriginal = process.env.NEXT_PUBLIC_API_URL;

  beforeEach(() => {
    vi.restoreAllMocks();
    mockReplace.mockClear();
    mockPathname.mockReturnValue('/publicaciones');
    process.env.NEXT_PUBLIC_API_URL = 'http://localhost:8080';
  });

  afterEach(() => {
    process.env.NEXT_PUBLIC_API_URL = apiUrlOriginal;
  });

  it('USUARIO ve la acción, ejecuta POST /auth/logout y redirige a /auth sin dejar contenido protegido', async () => {
    mockPathname.mockReturnValue('/publicaciones');
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ mensaje: 'Sesión cerrada' }),
    });
    global.fetch = fetchMock;

    render(
      <Shell obtenerUsuarioActual={async () => USUARIO}>
        <span>Contenido protegido</span>
      </Shell>
    );

    await screen.findByRole('link', { name: 'Mercado' });
    const boton = screen.getByRole('button', { name: 'Cerrar sesión' });
    expect(boton).toBeInTheDocument();

    await userEvent.click(boton);

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/auth/logout', {
        method: 'POST',
        credentials: 'include',
      })
    );
    await waitFor(() => expect(mockReplace).toHaveBeenCalledWith('/auth'));
    expect(screen.queryByText('Contenido protegido')).not.toBeInTheDocument();
  });

  it('ADMIN ve la misma acción Cerrar sesión junto a sus destinos y redirige a /auth', async () => {
    mockPathname.mockReturnValue('/admin');
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ mensaje: 'Sesión cerrada' }),
    });

    render(
      <Shell obtenerUsuarioActual={async () => ADMIN}>
        <span>Contenido protegido</span>
      </Shell>
    );

    await screen.findByRole('link', { name: 'Administración' });
    expect(screen.getByRole('button', { name: 'Cerrar sesión' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Cerrar sesión' }));
    await waitFor(() => expect(mockReplace).toHaveBeenCalledWith('/auth'));
    expect(screen.queryByText('Contenido protegido')).not.toBeInTheDocument();
  });

  it('respuesta no-ok del POST no redirige, conserva la sesión local y muestra el mensaje del backend', async () => {
    mockPathname.mockReturnValue('/publicaciones');
    global.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 500,
      json: async () => ({ mensaje: 'Error interno del servidor' }),
    });

    render(
      <Shell obtenerUsuarioActual={async () => USUARIO}>
        <span>Contenido protegido</span>
      </Shell>
    );

    await screen.findByRole('link', { name: 'Mercado' });
    await userEvent.click(screen.getByRole('button', { name: 'Cerrar sesión' }));

    expect(
      await screen.findByText('No se pudo cerrar la sesión: Error interno del servidor')
    ).toBeInTheDocument();
    expect(mockReplace).not.toHaveBeenCalled();
    expect(screen.getByText('Contenido protegido')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Mercado' })).toBeInTheDocument();
  });

  it('fallo de red en el POST no redirige, conserva la sesión local y muestra mensaje genérico', async () => {
    mockPathname.mockReturnValue('/publicaciones');
    global.fetch = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));

    render(
      <Shell obtenerUsuarioActual={async () => USUARIO}>
        <span>Contenido protegido</span>
      </Shell>
    );

    await screen.findByRole('link', { name: 'Mercado' });
    await userEvent.click(screen.getByRole('button', { name: 'Cerrar sesión' }));

    expect(await screen.findByText('No se pudo contactar al servicio de sesión')).toBeInTheDocument();
    expect(mockReplace).not.toHaveBeenCalled();
    expect(screen.getByText('Contenido protegido')).toBeInTheDocument();
  });

  it('tras cerrar sesión, una ruta protegida vuelve a exigir sesión: GET /usuarios/me falla y redirige a /auth', async () => {
    mockPathname.mockReturnValue('/publicaciones');
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ mensaje: 'Sesión cerrada' }),
    });

    render(
      <Shell obtenerUsuarioActual={async () => USUARIO}>
        <span>Contenido protegido</span>
      </Shell>
    );

    await screen.findByRole('link', { name: 'Mercado' });
    await userEvent.click(screen.getByRole('button', { name: 'Cerrar sesión' }));
    await waitFor(() => expect(mockReplace).toHaveBeenCalledWith('/auth'));

    // El usuario intenta volver a una ruta protegida: la cookie httpOnly ya
    // expiró (Set-Cookie con Max-Age=0 del backend) y el shell vuelve a
    // consultar GET /usuarios/me, que ahora falla con 403.
    mockReplace.mockClear();
    render(
      <Shell
        obtenerUsuarioActual={async () => {
          throw new ErrorApiSesion('No autorizado', 403);
        }}
      >
        <span>Contenido protegido</span>
      </Shell>
    );

    await waitFor(() => expect(mockReplace).toHaveBeenCalledWith('/auth'));
    expect(screen.queryByText('Contenido protegido')).not.toBeInTheDocument();
  });
});

/**
 * Pruebas de regresión de layout del nav (PHA08TSK01).
 *
 * <p>Criterio de aceptación literal de la tarea: "los enlaces del nav
 * (USUARIO y ADMIN) conservan {@code whitespace-nowrap}/{@code shrink-0} y el
 * {@code <nav>} mantiene {@code overflow-x-auto}".</p>
 *
 * <p>Estas dos aserciones protegen la corrección del defecto detectado en la
 * aceptación manual de 2026-08-17 (a 1280px, "Mis publicaciones" y "Cuentas
 * bloqueadas" envolvían su texto a dos líneas porque los {@code Link} del nav
 * no tenían {@code whitespace-nowrap} ni {@code shrink-0}): si un cambio
 * futuro retira alguna de las dos utilidades de un enlace, o quita el
 * {@code overflow-x-auto} del contenedor, el test falla por la razón correcta
 * (clase ausente en el className real renderizado).</p>
 */
describe('Shell — layout del nav sin wrapping (PHA08TSK01)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    mockReplace.mockClear();
    mockPathname.mockReturnValue('/publicaciones');
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  /**
   * Verifica que los 7 enlaces del nav del rol USUARIO conservan las
   * utilidades anti-wrapping y que el contenedor sigue siendo scrolleable.
   *
   * @returns promesa resuelta cuando todas las aserciones de layout pasan
   */
  it('USUARIO: los enlaces del nav conservan whitespace-nowrap y shrink-0, y el nav mantiene overflow-x-auto', async () => {
    mockPathname.mockReturnValue('/publicaciones');
    render(
      <Shell obtenerUsuarioActual={async () => USUARIO}>
        <span>Contenido protegido</span>
      </Shell>
    );

    await screen.findByRole('link', { name: 'Mercado' });

    const nav = screen.getByRole('navigation', { name: 'Navegación principal' });
    expect(nav).toHaveClass('overflow-x-auto');

    const enlaces = within(nav).getAllByRole('link');
    expect(enlaces).toHaveLength(DESTINOS_USUARIO.length);
    for (const enlace of enlaces) {
      expect(enlace).toHaveClass('whitespace-nowrap');
      expect(enlace).toHaveClass('shrink-0');
    }
  });

  /**
   * Verifica que los 12 enlaces del nav del rol ADMIN (7 de usuario + 5
   * exclusivos) conservan las utilidades anti-wrapping y que el contenedor
   * sigue siendo scrolleable.
   *
   * @returns promesa resuelta cuando todas las aserciones de layout pasan
   */
  it('ADMIN: los 12 enlaces del nav conservan whitespace-nowrap y shrink-0, y el nav mantiene overflow-x-auto', async () => {
    mockPathname.mockReturnValue('/admin');
    render(
      <Shell obtenerUsuarioActual={async () => ADMIN}>
        <span>Contenido protegido</span>
      </Shell>
    );

    await screen.findByRole('link', { name: 'Administración' });

    const nav = screen.getByRole('navigation', { name: 'Navegación principal' });
    expect(nav).toHaveClass('overflow-x-auto');

    const enlaces = within(nav).getAllByRole('link');
    expect(enlaces).toHaveLength(DESTINOS_USUARIO.length + DESTINOS_ADMIN.length);
    for (const enlace of enlaces) {
      expect(enlace).toHaveClass('whitespace-nowrap');
      expect(enlace).toHaveClass('shrink-0');
    }
  });
});

/**
 * Pruebas del sidebar lateral agrupado con botón hamburguesa (requerimiento de
 * UX de navegación: el menú plano del header se vuelve extenso y se reorganiza
 * en un cajón colapsado por defecto, con acciones agrupadas por tipo).
 *
 * <p>Contrato de comportamiento:
 * - El sidebar está colapsado por defecto ({@code aria-expanded="false"} en el
 *   botón hamburguesa y clase {@code -translate-x-full} en el {@code aside}).
 * - El botón hamburguesa (único con {@code aria-controls="sidebar-navegacion"})
 *   alterna la apertura; el estado queda expuesto en {@code aria-expanded}.
 * - Las acciones se agrupan bajo títulos de categoría (Explorar,
 *   Publicaciones, Transacciones, Mi Cuenta y Administración para ADMIN).
 * - Escape, el clic en el backdrop y el clic en un enlace cierran el menú.
 * - El botón "Cerrar sesión" vive en el pie del sidebar (acción de cuenta).</p>
 */
describe('Shell — sidebar agrupado con hamburguesa', () => {
  const apiUrlOriginal = process.env.NEXT_PUBLIC_API_URL;

  beforeEach(() => {
    vi.restoreAllMocks();
    mockReplace.mockClear();
    mockPathname.mockReturnValue('/publicaciones');
    process.env.NEXT_PUBLIC_API_URL = 'http://localhost:8080';
  });

  afterEach(() => {
    process.env.NEXT_PUBLIC_API_URL = apiUrlOriginal;
    vi.restoreAllMocks();
  });

  it('colapsado por defecto: hamburguesa con aria-expanded false y aside oculto por translate', async () => {
    render(
      <Shell obtenerUsuarioActual={async () => USUARIO}>
        <span>Contenido protegido</span>
      </Shell>
    );

    await screen.findByRole('link', { name: 'Mercado' });

    const botonMenu = screen.getByRole('button', { name: 'Abrir menú' });
    expect(botonMenu).toHaveAttribute('aria-expanded', 'false');
    expect(botonMenu).toHaveAttribute('aria-controls', 'sidebar-navegacion');

    const aside = document.getElementById('sidebar-navegacion');
    expect(aside).not.toBeNull();
    expect(aside).toHaveClass('-translate-x-full');
    expect(aside).not.toHaveClass('translate-x-0');
  });

  it('el botón hamburguesa expande el sidebar y lo vuelve a colapsar al alternar', async () => {
    render(
      <Shell obtenerUsuarioActual={async () => USUARIO}>
        <span>Contenido protegido</span>
      </Shell>
    );

    await screen.findByRole('link', { name: 'Mercado' });

    const botonMenu = screen.getByRole('button', { name: 'Abrir menú' });
    await userEvent.click(botonMenu);

    const aside = document.getElementById('sidebar-navegacion');
    expect(aside).toHaveClass('translate-x-0');
    expect(aside).not.toHaveClass('-translate-x-full');

    // El botón ahora expone el estado abierto.
    expect(screen.getByRole('button', { name: 'Cerrar menú' })).toHaveAttribute('aria-expanded', 'true');

    // Alternar de nuevo colapsa.
    await userEvent.click(screen.getByRole('button', { name: 'Cerrar menú' }));
    expect(aside).toHaveClass('-translate-x-full');
    expect(aside).not.toHaveClass('translate-x-0');
  });

  it('agrupa las acciones bajo títulos de categoría (Explorar, Publicaciones, Transacciones, Mi Cuenta)', async () => {
    render(
      <Shell obtenerUsuarioActual={async () => USUARIO}>
        <span>Contenido protegido</span>
      </Shell>
    );

    await screen.findByRole('link', { name: 'Mercado' });

    for (const grupo of GRUPOS_USUARIO) {
      expect(screen.getByText(grupo.titulo)).toBeInTheDocument();
    }
    expect(screen.queryByText('Administración')).not.toBeInTheDocument();
  });

  it('ADMIN agrega el grupo Administración con sus 5 destinos exclusivos', async () => {
    mockPathname.mockReturnValue('/admin');
    render(
      <Shell obtenerUsuarioActual={async () => ADMIN}>
        <span>Contenido protegido</span>
      </Shell>
    );

    await screen.findByRole('link', { name: 'Administración' });

    for (const grupo of GRUPOS_USUARIO) {
      expect(screen.getByText(grupo.titulo)).toBeInTheDocument();
    }
    expect(screen.getByText('Administración', { selector: 'div' })).toBeInTheDocument();
    for (const destino of DESTINOS_ADMIN) {
      expect(screen.getByRole('link', { name: destino.etiqueta })).toHaveAttribute('href', destino.ruta);
    }
  });

  it('la tecla Escape cierra el sidebar expandido', async () => {
    render(
      <Shell obtenerUsuarioActual={async () => USUARIO}>
        <span>Contenido protegido</span>
      </Shell>
    );

    await screen.findByRole('link', { name: 'Mercado' });

    const botonMenu = screen.getByRole('button', { name: 'Abrir menú' });
    await userEvent.click(botonMenu);
    expect(document.getElementById('sidebar-navegacion')).toHaveClass('translate-x-0');

    await userEvent.keyboard('{Escape}');

    expect(document.getElementById('sidebar-navegacion')).toHaveClass('-translate-x-full');
    expect(screen.getByRole('button', { name: 'Abrir menú' })).toHaveAttribute('aria-expanded', 'false');
  });

  it('el clic en el backdrop cierra el sidebar', async () => {
    render(
      <Shell obtenerUsuarioActual={async () => USUARIO}>
        <span>Contenido protegido</span>
      </Shell>
    );

    await screen.findByRole('link', { name: 'Mercado' });

    const botonMenu = screen.getByRole('button', { name: 'Abrir menú' });
    await userEvent.click(botonMenu);
    expect(document.getElementById('sidebar-navegacion')).toHaveClass('translate-x-0');

    const backdrop = document.querySelector('[role="presentation"]');
    expect(backdrop).not.toBeNull();
    await userEvent.click(backdrop as HTMLElement);

    expect(document.getElementById('sidebar-navegacion')).toHaveClass('-translate-x-full');
  });

  it('clic en un enlace del sidebar cierra el menú', async () => {
    render(
      <Shell obtenerUsuarioActual={async () => USUARIO}>
        <span>Contenido protegido</span>
      </Shell>
    );

    await screen.findByRole('link', { name: 'Mercado' });

    const botonMenu = screen.getByRole('button', { name: 'Abrir menú' });
    await userEvent.click(botonMenu);
    expect(document.getElementById('sidebar-navegacion')).toHaveClass('translate-x-0');

    await userEvent.click(screen.getByRole('link', { name: 'Publicar' }));

    expect(document.getElementById('sidebar-navegacion')).toHaveClass('-translate-x-full');
    expect(screen.getByRole('button', { name: 'Abrir menú' })).toHaveAttribute('aria-expanded', 'false');
  });

  it('el botón Cerrar sesión vive en el pie del sidebar y ejecuta el logout', async () => {
    mockPathname.mockReturnValue('/publicaciones');
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ mensaje: 'Sesión cerrada' }),
    });
    global.fetch = fetchMock;

    render(
      <Shell obtenerUsuarioActual={async () => USUARIO}>
        <span>Contenido protegido</span>
      </Shell>
    );

    await screen.findByRole('link', { name: 'Mercado' });

    const botonMenu = screen.getByRole('button', { name: 'Abrir menú' });
    await userEvent.click(botonMenu);

    const aside = document.getElementById('sidebar-navegacion');
    expect(aside).toHaveClass('translate-x-0');

    const botonLogout = within(aside as HTMLElement).getByRole('button', { name: 'Cerrar sesión' });
    expect(botonLogout).toBeInTheDocument();

    await userEvent.click(botonLogout);

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/auth/logout', {
        method: 'POST',
        credentials: 'include',
      })
    );
    await waitFor(() => expect(mockReplace).toHaveBeenCalledWith('/auth'));
    expect(screen.queryByText('Contenido protegido')).not.toBeInTheDocument();
  });
});

/**
 * Pruebas de la marca clicable del shell (PHA09TSK01).
 *
 * <p>Criterio de aceptación literal de la tarea: "la marca del header y del
 * sidebar es un `Link` con `href="/publicaciones"`". La marca vive en dos
 * lugares de `Shell.tsx`: el header superior izquierdo (visible siempre en
 * rutas con shell) y la cabecera del sidebar (solo con sesión autenticada).
 * Como ambos tienen el mismo nombre accesible "EasyMarket", la aserción usa
 * `getAllByRole` y `within(aside)` para distinguirlos.</p>
 *
 * <p>El sidebar solo existe autenticado, así que el estado cubierto para las
 * dos marcas es `autenticado`. El estado sin enlaces (sesión resolviendo) se
 * cubre por separado para proteger el contrato previo de PHA06TSK09: mientras
 * resuelve `/usuarios/me` la marca no debe ser interactiva.</p>
 */
describe('Shell — marca clicable (PHA09TSK01)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    mockReplace.mockClear();
    mockPathname.mockReturnValue('/publicaciones');
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  /**
   * Verifica que con sesión autenticada las dos marcas ("EasyMarket") son
   * enlaces a `/publicaciones`: una en la cabecera del sidebar (dentro del
   * `aside`) y la otra en el header superior (fuera del `aside`).
   *
   * @returns promesa resuelta cuando todas las aserciones de la marca pasan
   */
  it('la marca del header y la del sidebar son Links a /publicaciones con sesión (USUARIO)', async () => {
    render(
      <Shell obtenerUsuarioActual={async () => USUARIO}>
        <span>Contenido protegido</span>
      </Shell>
    );

    await screen.findByRole('link', { name: 'Mercado' });

    const marcas = screen.getAllByRole('link', { name: 'EasyMarket' });
    expect(marcas).toHaveLength(2);
    for (const marca of marcas) {
      expect(marca).toHaveAttribute('href', '/publicaciones');
    }

    // La marca de la cabecera del sidebar vive dentro del aside de navegación.
    const aside = screen.getByRole('complementary', { name: 'Menú lateral de navegación' });
    const marcaSidebar = within(aside).getByRole('link', { name: 'EasyMarket' });
    expect(marcaSidebar).toHaveAttribute('href', '/publicaciones');

    // La marca del header superior es la que queda fuera del aside.
    const marcaHeader = marcas.find((marca) => !aside.contains(marca));
    expect(marcaHeader).toBeDefined();
    expect(marcaHeader).toHaveAttribute('href', '/publicaciones');
    expect(marcaHeader!.closest('header')).not.toBeNull();
  });

  /**
   * Verifica que mientras la sesión resuelve (estado `cargando`) la marca se
   * conserva como texto pero NO es un enlace: el shell no expone navegación
   * hasta tener identidad autenticada (contrato de PHA06TSK09).
   */
  it('la marca no es enlace mientras resuelve la sesión: se conserva como texto sin navegar', () => {
    const pendiente = new Promise<UsuarioActualUI>(() => {});
    render(
      <Shell obtenerUsuarioActual={() => pendiente}>
        <span>Contenido protegido</span>
      </Shell>
    );

    expect(screen.getByText('EasyMarket')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'EasyMarket' })).not.toBeInTheDocument();
  });
});

/**
 * Pruebas de la campana de notificaciones del header (PHA15TSK06).
 *
 * <p>Criterio de aceptación literal de la tarea: "ícono accesible de
 * notificaciones para ADMIN y USUARIO, enlazado a `/notificaciones`, con
 * contador de no leídas obtenido del endpoint autorizado... Actualizar
 * contador al cargar sesión, volver al foco y después de marcar como leída,
 * sin duplicar fetches ni romper navegación".</p>
 *
 * <p>Decisiones cubiertas:</p>
 * <ul>
 *   <li>La campana es un `Link` a `/notificaciones` con nombre accesible
 *       "Ver notificaciones", visible SOLO con sesión autenticada (estado
 *       `autenticado`), para ambos roles. El nombre accesible NO es
 *       "Notificaciones" a propósito: colisionaría con el enlace del sidebar
 *       del mismo nombre y rompería la resolución `getByRole` existente.</li>
 *   <li>Badge numérico SOLO cuando la cantidad es mayor a cero; cantidad 0,
 *       error de red o HTTP no-ok → sin badge y header funcional.</li>
 *   <li>Fetch del contador `GET /notificaciones/no-leidas/count` con
 *       `credentials: 'include'` al cargar sesión, al recuperar el foco de la
 *       ventana y al recibir el evento de ventana
 *       `easymarket:notificacion-leida` que emite el panel tras marcar como
 *       leída (mecanismo de sincronización sin dependencias nuevas).</li>
 *   <li>Anti-duplicación: focos repetidos mientras la consulta está en vuelo
 *       no agregan peticiones (guarda de concurrencia).</li>
 * </ul>
 */
describe('Shell — campana de notificaciones (PHA15TSK06)', () => {
  const apiUrlOriginal = process.env.NEXT_PUBLIC_API_URL;

  /** Respuesta 200 del endpoint del contador con la cantidad dada. */
  function okCantidad(cantidad: number) {
    return { ok: true, status: 200, json: async () => ({ cantidad }) };
  }

  beforeEach(() => {
    vi.restoreAllMocks();
    mockReplace.mockClear();
    mockPathname.mockReturnValue('/publicaciones');
    process.env.NEXT_PUBLIC_API_URL = 'http://localhost:8080';
  });

  afterEach(() => {
    process.env.NEXT_PUBLIC_API_URL = apiUrlOriginal;
    vi.restoreAllMocks();
  });

  /**
   * Verifica la campana para USUARIO: enlace en el header (role banner) con
   * href `/notificaciones`, nombre accesible "Ver notificaciones" y badge
   * numérico con la cantidad devuelta por el endpoint del contador, al que se
   * consulta con `credentials: 'include'` al cargar la sesión.
   *
   * @returns promesa resuelta cuando todas las aserciones de campana pasan
   */
  it('USUARIO autenticado: campana en el header enlaza /notificaciones y muestra el badge con la cantidad', async () => {
    const fetchMock = vi.fn().mockResolvedValue(okCantidad(3));
    global.fetch = fetchMock;

    render(
      <Shell obtenerUsuarioActual={async () => USUARIO}>
        <span>Contenido protegido</span>
      </Shell>
    );

    await screen.findByRole('link', { name: 'Mercado' });

    const header = screen.getByRole('banner');
    const campana = within(header).getByRole('link', { name: 'Ver notificaciones' });
    expect(campana).toHaveAttribute('href', '/notificaciones');
    // El fetch del contador resuelve después del render: findByText espera el badge.
    expect(await within(campana).findByText('3')).toBeInTheDocument();

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/notificaciones/no-leidas/count', {
        method: 'GET',
        credentials: 'include',
      });
    });
  });

  /**
   * Verifica que el rol ADMIN también recibe la campana con su propio contador
   * (el endpoint es el mismo; el rol lo resuelve el backend desde el JWT).
   *
   * @returns promesa resuelta cuando todas las aserciones de campana pasan
   */
  it('ADMIN autenticado: también ve la campana con su contador de no leídas', async () => {
    const fetchMock = vi.fn().mockResolvedValue(okCantidad(2));
    global.fetch = fetchMock;

    render(
      <Shell obtenerUsuarioActual={async () => ADMIN}>
        <span>Contenido protegido</span>
      </Shell>
    );

    await screen.findByRole('link', { name: 'Administración' });

    const header = screen.getByRole('banner');
    const campana = within(header).getByRole('link', { name: 'Ver notificaciones' });
    expect(await within(campana).findByText('2')).toBeInTheDocument();
  });

  /**
   * Verifica el estado cero: cantidad 0 del backend → el enlace de la campana
   * existe pero SIN badge numérico (un "0" engañoso no se renderiza).
   *
   * @returns promesa resuelta cuando la aserción de ausencia de badge pasa
   */
  it('cantidad 0: campana presente pero sin badge numérico', async () => {
    global.fetch = vi.fn().mockResolvedValue(okCantidad(0));

    render(
      <Shell obtenerUsuarioActual={async () => USUARIO}>
        <span>Contenido protegido</span>
      </Shell>
    );

    await screen.findByRole('link', { name: 'Mercado' });

    const header = screen.getByRole('banner');
    const campana = within(header).getByRole('link', { name: 'Ver notificaciones' });
    await waitFor(() => {
      expect(within(campana).queryByText('0')).not.toBeInTheDocument();
    });
  });

  /**
   * Verifica la resiliencia del header: fallo de red en la consulta del
   * contador → sin badge, SIN error visible y el header queda funcional (la
   * marca y la navegación siguen presentes).
   *
   * @returns promesa resuelta cuando las aserciones de resiliencia pasan
   */
  it('error de red en el contador: sin badge y el header sigue funcional', async () => {
    global.fetch = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));

    render(
      <Shell obtenerUsuarioActual={async () => USUARIO}>
        <span>Contenido protegido</span>
      </Shell>
    );

    await screen.findByRole('link', { name: 'Mercado' });

    const header = screen.getByRole('banner');
    const campana = within(header).getByRole('link', { name: 'Ver notificaciones' });
    await waitFor(() => {
      expect(campana.querySelector('span[aria-hidden="true"]')).toBeNull();
    });
    // El header sigue funcional: marca y cierre de sesión presentes.
    expect(within(header).getByText('EasyMarket')).toBeInTheDocument();
  });

  /**
   * Verifica el refresco por foco (plan.md §Campana del header): al volver el
   * foco a la ventana se consulta de nuevo el contador y el badge se actualiza
   * con la cantidad fresca.
   *
   * @returns promesa resuelta cuando el refresco por foco pasa
   */
  it('al recuperar el foco de la ventana refresca el contador (1 -> 2 fetch, badge actualizado)', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(okCantidad(1))
      .mockResolvedValueOnce(okCantidad(4));
    global.fetch = fetchMock;

    render(
      <Shell obtenerUsuarioActual={async () => USUARIO}>
        <span>Contenido protegido</span>
      </Shell>
    );

    await screen.findByRole('link', { name: 'Mercado' });
    const header = screen.getByRole('banner');
    const campana = within(header).getByRole('link', { name: 'Ver notificaciones' });
    expect(await within(campana).findByText('1')).toBeInTheDocument();

    // El usuario vuelve a la pestaña: window dispara el evento `focus`.
    fireEvent.focus(window);

    expect(await within(campana).findByText('4')).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  /**
   * Verifica la sincronización panel → header SIN dependencias nuevas: el
   * evento de ventana `easymarket:notificacion-leida` (emitido por el panel
   * tras un PATCH exitoso) dispara el refetch del contador.
   *
   * @returns promesa resuelta cuando el refresco por evento pasa
   */
  it('el evento easymarket:notificacion-leida refresca el contador tras marcar como leída', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(okCantidad(1))
      .mockResolvedValueOnce(okCantidad(0));
    global.fetch = fetchMock;

    render(
      <Shell obtenerUsuarioActual={async () => USUARIO}>
        <span>Contenido protegido</span>
      </Shell>
    );

    await screen.findByRole('link', { name: 'Mercado' });
    const header = screen.getByRole('banner');
    const campana = within(header).getByRole('link', { name: 'Ver notificaciones' });
    expect(await within(campana).findByText('1')).toBeInTheDocument();

    window.dispatchEvent(new Event(EVENTO_NOTIFICACION_LEIDA));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledTimes(2);
    });
    // Con la cantidad fresca en 0 el badge desaparece (estado cero sin badge).
    await waitFor(() => {
      expect(within(campana).queryByText('1')).not.toBeInTheDocument();
    });
  });

  /**
   * Verifica la guarda anti-duplicación: dos focos disparados mientras la
   * primera consulta del contador está en vuelo NO agregan peticiones — un
   * único fetch sirve al montaje y a los disparos concurrentes.
   *
   * @returns promesa resuelta cuando la guarda anti-duplicación pasa
   */
  it('no duplica fetches: focos concurrentes con consulta en vuelo no agregan peticiones', async () => {
    let resolverConteo: (valor: { ok: boolean; status: number; json: () => Promise<{ cantidad: number }> }) => void =
      () => {};
    const conteoEnVuelo = new Promise<{ ok: boolean; status: number; json: () => Promise<{ cantidad: number }> }>(
      (resolve) => {
        resolverConteo = resolve;
      }
    );
    const fetchMock = vi.fn().mockReturnValue(conteoEnVuelo);
    global.fetch = fetchMock;

    render(
      <Shell obtenerUsuarioActual={async () => USUARIO}>
        <span>Contenido protegido</span>
      </Shell>
    );

    await screen.findByRole('link', { name: 'Mercado' });
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));

    // Dos focos mientras la consulta del montaje sigue en vuelo.
    fireEvent.focus(window);
    fireEvent.focus(window);

    resolverConteo(okCantidad(2));
    await waitFor(() => {
      expect(within(screen.getByRole('banner')).getByRole('link', { name: 'Ver notificaciones' })).toContainElement(
        screen.getByText('2')
      );
    });
    // La guarda deduplicó: sigue habiendo UNA sola petición al contador.
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  /**
   * Verifica que la campana NO se renderiza mientras la sesión resuelve
   * (estado `cargando`) — mismo contrato del shell de no exponer navegación
   * hasta tener identidad autenticada.
   */
  it('mientras la sesión resuelve no hay campana en el header', async () => {
    const pendiente = new Promise<UsuarioActualUI>(() => {});
    render(
      <Shell obtenerUsuarioActual={() => pendiente}>
        <span>Contenido protegido</span>
      </Shell>
    );

    const header = screen.getByRole('banner');
    expect(within(header).queryByRole('link', { name: 'Ver notificaciones' })).not.toBeInTheDocument();
  });
});
