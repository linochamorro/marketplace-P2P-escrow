import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { flushSync } from 'react-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import Shell from './Shell';
import ModeracionPage from './admin/moderacion/page';
import DisputasPage from './admin/disputas/page';
import UsuariosBloqueadosPage from './admin/usuarios-bloqueados/page';
import PanelCategorias from './PanelCategorias';

/** Ruta que el mock de navegación entrega al shell en cada escenario L02. */
let pathnameActual = '/admin';

vi.mock('next/navigation', () => ({
  usePathname: () => pathnameActual,
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
}));

/** URL base determinista para los contratos HTTP de la corrección L02. */
const BASE = 'http://localhost:8080';

/** Identidad administrativa válida compartida por el shell. */
const ADMIN = { id: 1, email: 'admin@easymarket.dev', rol: 'ADMIN' } as const;

/** Control manual de una promesa para observar estados antes y después de una respuesta. */
interface Diferido<T> {
  /** Promesa entregada al código bajo prueba. */
  promise: Promise<T>;
  /** Resuelve la promesa con el valor indicado. */
  resolve: (value: T) => void;
}

/**
 * Construye una promesa cuyo momento de resolución controla la prueba.
 *
 * @returns promesa y función de resolución asociada
 */
function diferido<T>(): Diferido<T> {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((resolver) => { resolve = resolver; });
  return { promise, resolve };
}

/**
 * Construye una respuesta HTTP mínima compatible con los consumidores administrativos.
 *
 * @param body cuerpo JSON devuelto por la respuesta
 * @param status código HTTP, 200 por defecto
 * @returns respuesta simulada con estado y lector JSON
 */
function respuesta(body: unknown, status = 200): Response {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as Response;
}

/**
 * Monta una ruta administrativa dentro del shell real de identidad.
 *
 * @param ruta ruta visible para el mock de Next.js
 * @param Pagina componente de página administrativa
 * @returns resultado del render para desmontaje e inspección
 */
function renderRuta(ruta: string, Pagina: () => React.JSX.Element) {
  pathnameActual = ruta;
  return render(<Shell><Pagina /></Shell>);
}

describe('PHA06TSK13-L02 - cobertura administrativa requerida', () => {
  const apiOriginal = process.env.NEXT_PUBLIC_API_URL;

  beforeEach(() => {
    vi.restoreAllMocks();
    process.env.NEXT_PUBLIC_API_URL = BASE;
  });

  afterEach(() => {
    process.env.NEXT_PUBLIC_API_URL = apiOriginal;
  });

  it('rechaza completa una publicación cuya pareja categoría/subcategoría no existe, sin montar PanelModeracion', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      expect(init).toEqual(expect.objectContaining({ method: 'GET', credentials: 'include' }));
      // PHA15TSK06: la campana del shell consulta el contador al cargar la sesión (sin signal:
      // no es una lectura administrativa abortable). Se responde antes del gate de signal.
      if (url === `${BASE}/notificaciones/no-leidas/count`) return respuesta({ cantidad: 0 });
      if (url !== `${BASE}/usuarios/me`) expect(init?.signal).toBeInstanceOf(AbortSignal);
      if (url === `${BASE}/usuarios/me`) return respuesta(ADMIN);
      if (url === `${BASE}/publicaciones?estado=PENDIENTE_REVISION`) return respuesta([{
        id: 41, usuarioId: 9, categoriaId: 8, subcategoriaId: 999, precio: 25000,
        stock: 2, descripcion: 'Clasificación rota', usuarioEmail: 'vendedor@example.com', imagenFilename: null, estado: 'PENDIENTE_REVISION',
      }]);
      if (url === `${BASE}/categorias`) return respuesta([{ id: 8, nombre: 'Tecnología', subcategorias: [{ id: 81, nombre: 'Laptops' }] }]);
      throw new Error(`URL inesperada: ${url}`);
    });
    global.fetch = fetchMock;

    renderRuta('/admin/moderacion', ModeracionPage);

    expect(await screen.findByRole('alert')).toHaveTextContent('La clasificación de una publicación pendiente no existe en el catálogo');
    expect(screen.queryByText('Panel de Moderaciones (Admin)')).not.toBeInTheDocument();
    expect(screen.queryByText('Clasificación rota')).not.toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(4);
  });

  it('acepta fechas de envío y entrega null del DTO real y conserva controles ADMIN', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      expect(init).toEqual(expect.objectContaining({ method: 'GET', credentials: 'include' }));
      // PHA15TSK06: contador de la campana antes del gate de signal (sin AbortSignal).
      if (url === `${BASE}/notificaciones/no-leidas/count`) return respuesta({ cantidad: 0 });
      if (url !== `${BASE}/usuarios/me`) expect(init?.signal).toBeInstanceOf(AbortSignal);
      if (url === `${BASE}/usuarios/me`) return respuesta(ADMIN);
      if (url === `${BASE}/admin/disputas`) return respuesta([{
        id: 77, estado: 'disputa', precioSnapshot: 4500,
        fechaReservada: '2026-08-15T10:00:00Z', fechaEnviado: null, fechaEntregado: null,
        publicacionDescripcion: 'Teclado sin fechas posteriores',
      }]);
      throw new Error(`URL inesperada: ${url}`);
    });
    global.fetch = fetchMock;

    renderRuta('/admin/disputas', DisputasPage);

    expect(await screen.findByText('Teclado sin fechas posteriores')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /liberar fondos/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /reembolsar al comprador/i })).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('conserva la cuenta y muestra el mensaje backend cuando falla el POST de desbloqueo', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === `${BASE}/usuarios/me`) return respuesta(ADMIN);
      // PHA15TSK06: contador de la campana del shell (no es lectura admin).
      if (url === `${BASE}/notificaciones/no-leidas/count`) return respuesta({ cantidad: 0 });
      if (url === `${BASE}/admin/usuarios/bloqueados`) return respuesta([{ usuarioId: 93, email: 'bloqueado@example.com' }]);
      if (url === `${BASE}/admin/usuarios/93/desbloquear`) {
        expect(init).toEqual({ method: 'POST', credentials: 'include' });
        return respuesta({ mensaje: 'La cuenta ya no tiene bloqueo permanente' }, 400);
      }
      throw new Error(`URL inesperada: ${url}`);
    });
    global.fetch = fetchMock;

    renderRuta('/admin/usuarios-bloqueados', UsuariosBloqueadosPage);
    fireEvent.click(await screen.findByRole('button', { name: /desbloquear/i }));

    expect(await screen.findByRole('alert')).toHaveTextContent('La cuenta ya no tiene bloqueo permanente');
    expect(screen.getByText('bloqueado@example.com')).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(4);
  });

  it('aborta causalmente la lectura administrativa al desmontar la ruta', async () => {
    const cargaTardia = diferido<Response>();
    let signalAdmin: AbortSignal | undefined;
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input) === `${BASE}/usuarios/me`) return respuesta(ADMIN);
      // PHA15TSK06: el contador de la campana no es lectura abortable — se responde antes de
      // capturar el signal de la lectura administrativa para no ensuciar la aserción de aborto.
      if (String(input) === `${BASE}/notificaciones/no-leidas/count`) return respuesta({ cantidad: 0 });
      signalAdmin = init?.signal ?? undefined;
      return cargaTardia.promise;
    });
    global.fetch = fetchMock;

    const vista = renderRuta('/admin/disputas', DisputasPage);
    expect(await screen.findByText(/cargando datos administrativos/i)).toBeInTheDocument();
    await waitFor(() => expect(signalAdmin).toBeInstanceOf(AbortSignal));
    expect(signalAdmin?.aborted).toBe(false);
    vista.unmount();

    expect(signalAdmin?.aborted).toBe(true);
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('valida el motivo de otra publicación aunque una moderación siga en vuelo, sin segundo PATCH', async () => {
    const patchPendiente = diferido<Response>();
    const publicaciones = [
      { id: 41, usuarioId: 9, categoriaId: 8, subcategoriaId: 81, precio: 25000, stock: 2, descripcion: 'Fila A', usuarioEmail: 'vendedor-a@example.com', imagenFilename: null, estado: 'PENDIENTE_REVISION' },
      { id: 42, usuarioId: 10, categoriaId: 8, subcategoriaId: 81, precio: 35000, stock: 1, descripcion: 'Fila B', usuarioEmail: 'vendedor-b@example.com', imagenFilename: null, estado: 'PENDIENTE_REVISION' },
    ];
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === `${BASE}/usuarios/me`) return respuesta(ADMIN);
      if (url === `${BASE}/publicaciones?estado=PENDIENTE_REVISION`) return respuesta(publicaciones);
      if (url === `${BASE}/categorias`) return respuesta([{ id: 8, nombre: 'Tecnología', subcategorias: [{ id: 81, nombre: 'Laptops' }] }]);
      if (url === `${BASE}/publicaciones/41/moderar`) return patchPendiente.promise;
      throw new Error(`URL inesperada: ${url}`);
    });
    global.fetch = fetchMock;

    renderRuta('/admin/moderacion', ModeracionPage);
    const motivos = await screen.findAllByLabelText(/motivo/i);
    fireEvent.change(motivos[0], { target: { value: 'Corregir categoría' } });
    const solicitar = screen.getAllByRole('button', { name: /solicitar cambios/i });
    fireEvent.click(solicitar[0]);
    fireEvent.click(solicitar[1]);

    expect(await screen.findByText('El motivo es obligatorio para esta acción')).toBeInTheDocument();
    expect(fetchMock.mock.calls.filter(([url]) => String(url).includes('/moderar'))).toHaveLength(1);
  });

  it('permite reintentar moderación tras error HTTP y muta la fila solo después del éxito', async () => {
    const cuerpos: string[] = [];
    let intentos = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === `${BASE}/usuarios/me`) return respuesta(ADMIN);
      if (url === `${BASE}/publicaciones?estado=PENDIENTE_REVISION`) return respuesta([{
        id: 51, usuarioId: 9, categoriaId: 8, subcategoriaId: 81, precio: 25000,
        stock: 2, descripcion: 'Moderación reintentable', usuarioEmail: 'vendedor@example.com', imagenFilename: null, estado: 'PENDIENTE_REVISION',
      }]);
      if (url === `${BASE}/categorias`) return respuesta([{ id: 8, nombre: 'Tecnología', subcategorias: [{ id: 81, nombre: 'Laptops' }] }]);
      if (url === `${BASE}/publicaciones/51/moderar`) {
        cuerpos.push(String(init?.body));
        intentos += 1;
        return intentos === 1 ? respuesta({ mensaje: 'Fallo temporal' }, 503) : respuesta({ id: 51, estado: 'CAMBIOS_SOLICITADOS' });
      }
      throw new Error(`URL inesperada: ${url}`);
    });
    global.fetch = fetchMock;

    renderRuta('/admin/moderacion', ModeracionPage);
    fireEvent.change(await screen.findByLabelText(/motivo/i), { target: { value: 'Mover categoría' } });
    fireEvent.click(screen.getByRole('button', { name: /solicitar cambios/i }));
    expect(await screen.findByText('Fallo temporal')).toBeInTheDocument();
    expect(screen.getByText('Moderación reintentable')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /solicitar cambios/i }));

    expect(await screen.findByText('Publicación moderada exitosamente')).toBeInTheDocument();
    expect(screen.queryByText('Moderación reintentable')).not.toBeInTheDocument();
    expect(cuerpos).toEqual([
      JSON.stringify({ accion: 'solicitar-cambios', motivo: 'Mover categoría' }),
      JSON.stringify({ accion: 'solicitar-cambios', motivo: 'Mover categoría' }),
    ]);
  });

  it('permite reintentar una disputa tras error HTTP con segundo body exacto y retiro solo tras éxito', async () => {
    const cuerpos: string[] = [];
    let intentos = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === `${BASE}/usuarios/me`) return respuesta(ADMIN);
      if (url === `${BASE}/admin/disputas`) return respuesta([{
        id: 88, estado: 'disputa', precioSnapshot: 5500, fechaReservada: '2026-08-15T10:00:00Z',
        fechaEnviado: null, fechaEntregado: null, publicacionDescripcion: 'Disputa reintentable',
      }]);
      if (url === `${BASE}/disputas/88/resolver`) {
        cuerpos.push(String(init?.body));
        intentos += 1;
        return intentos === 1 ? respuesta({ mensaje: 'Conflicto temporal' }, 409) : respuesta(null);
      }
      throw new Error(`URL inesperada: ${url}`);
    });
    global.fetch = fetchMock;

    renderRuta('/admin/disputas', DisputasPage);
    fireEvent.change(await screen.findByLabelText(/motivo de la resolución/i), { target: { value: 'Entrega acreditada' } });
    fireEvent.click(screen.getByRole('button', { name: /liberar fondos/i }));
    expect(await screen.findByText('Conflicto temporal')).toBeInTheDocument();
    expect(screen.getByText('Disputa reintentable')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /liberar fondos/i }));

    expect(await screen.findByText(/disputa resuelta a favor del vendedor/i)).toBeInTheDocument();
    expect(screen.queryByText('Disputa reintentable')).not.toBeInTheDocument();
    expect(cuerpos).toEqual([
      JSON.stringify({ decision: 'A_FAVOR_VENDEDOR', motivo: 'Entrega acreditada' }),
      JSON.stringify({ decision: 'A_FAVOR_VENDEDOR', motivo: 'Entrega acreditada' }),
    ]);
  });

  it('mantiene loading hasta sincronizar la lista de bloqueados y nunca deja un frame vacío', async () => {
    const cargaBloqueados = diferido<Response>();
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === `${BASE}/usuarios/me`) return respuesta(ADMIN);
      // PHA15TSK06: contador de la campana del shell, resuelto de inmediato (no participa del
      // diferido de la lectura administrativa).
      if (url === `${BASE}/notificaciones/no-leidas/count`) return respuesta({ cantidad: 0 });
      return cargaBloqueados.promise;
    });
    global.fetch = fetchMock;

    renderRuta('/admin/usuarios-bloqueados', UsuariosBloqueadosPage);
    expect(await screen.findByText(/cargando datos administrativos/i)).toBeInTheDocument();

    cargaBloqueados.resolve(respuesta([{ usuarioId: 93, email: 'sin-frame@example.com' }]));
    await cargaBloqueados.promise;
    await Promise.resolve();
    flushSync(() => undefined);

    expect(screen.queryByText(/cargando datos administrativos/i) ?? screen.queryByText('sin-frame@example.com')).not.toBeNull();
    expect(await screen.findByText('sin-frame@example.com')).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('doble acción de desbloqueo en el mismo tick produce un solo POST y una sola retirada tras 200', async () => {
    const post = diferido<Response>();
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === `${BASE}/usuarios/me`) return respuesta(ADMIN);
      if (url === `${BASE}/admin/usuarios/bloqueados`) return respuesta([{ usuarioId: 93, email: 'doble@example.com' }]);
      if (url === `${BASE}/admin/usuarios/93/desbloquear`) return post.promise;
      throw new Error(`URL inesperada: ${url}`);
    });
    global.fetch = fetchMock;

    renderRuta('/admin/usuarios-bloqueados', UsuariosBloqueadosPage);
    const boton = await screen.findByRole('button', { name: /desbloquear/i });
    act(() => {
      boton.click();
      boton.click();
    });

    expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/desbloquear'))).toHaveLength(1);
    expect(screen.getByText('doble@example.com')).toBeInTheDocument();
    await act(async () => { post.resolve(respuesta({ mensaje: 'Cuenta desbloqueada exitosamente' })); });
    expect(await screen.findByText('Cuenta desbloqueada exitosamente')).toBeInTheDocument();
    expect(screen.queryByText('doble@example.com')).not.toBeInTheDocument();
  });

  it('solicitar-cambios envía motivo literal una sola vez y retira solo después del éxito', async () => {
    const patch = diferido<Response>();
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === `${BASE}/usuarios/me`) return respuesta(ADMIN);
      if (url === `${BASE}/publicaciones?estado=PENDIENTE_REVISION`) return respuesta([{
        id: 41, usuarioId: 9, categoriaId: 8, subcategoriaId: 81, precio: 25000,
        stock: 2, descripcion: 'Laptop usada', usuarioEmail: 'vendedor@example.com', imagenFilename: null, estado: 'PENDIENTE_REVISION',
      }]);
      if (url === `${BASE}/categorias`) return respuesta([{ id: 8, nombre: 'Tecnología', subcategorias: [{ id: 81, nombre: 'Laptops' }] }]);
      if (url === `${BASE}/publicaciones/41/moderar`) {
        expect(init).toEqual({
          method: 'PATCH', headers: { 'Content-Type': 'application/json' }, credentials: 'include',
          body: JSON.stringify({ accion: 'solicitar-cambios', motivo: 'Mover a periféricos' }),
        });
        return patch.promise;
      }
      throw new Error(`URL inesperada: ${url}`);
    });
    global.fetch = fetchMock;

    renderRuta('/admin/moderacion', ModeracionPage);
    fireEvent.change(await screen.findByLabelText(/motivo/i), { target: { value: 'Mover a periféricos' } });
    const boton = screen.getByRole('button', { name: /solicitar cambios/i });
    act(() => {
      boton.click();
      boton.click();
    });

    expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/publicaciones/41/moderar'))).toHaveLength(1);
    expect(screen.getByText('Laptop usada')).toBeInTheDocument();
    await act(async () => { patch.resolve(respuesta({ id: 41, estado: 'CAMBIOS_SOLICITADOS' })); });
    expect(await screen.findByText('Publicación moderada exitosamente')).toBeInTheDocument();
    expect(screen.queryByText('Laptop usada')).not.toBeInTheDocument();
  });

  it('doble resolución de disputa en el mismo tick produce un solo PATCH y estado coherente', async () => {
    const patch = diferido<Response>();
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === `${BASE}/usuarios/me`) return respuesta(ADMIN);
      if (url === `${BASE}/admin/disputas`) return respuesta([{
        id: 77, estado: 'disputa', precioSnapshot: 4500, fechaReservada: '2026-08-15T10:00:00Z',
        fechaEnviado: null, fechaEntregado: null, publicacionDescripcion: 'Disputa doble',
      }]);
      if (url === `${BASE}/disputas/77/resolver`) {
        expect(init).toEqual({
          method: 'PATCH', headers: { 'Content-Type': 'application/json' }, credentials: 'include',
          body: JSON.stringify({ decision: 'A_FAVOR_VENDEDOR', motivo: 'Entrega acreditada' }),
        });
        return patch.promise;
      }
      throw new Error(`URL inesperada: ${url}`);
    });
    global.fetch = fetchMock;

    renderRuta('/admin/disputas', DisputasPage);
    fireEvent.change(await screen.findByLabelText(/motivo de la resolución/i), { target: { value: 'Entrega acreditada' } });
    const boton = screen.getByRole('button', { name: /liberar fondos/i });
    act(() => {
      boton.click();
      boton.click();
    });

    expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/disputas/77/resolver'))).toHaveLength(1);
    expect(screen.getByText('Disputa doble')).toBeInTheDocument();
    await act(async () => { patch.resolve(respuesta(null)); });
    expect(await screen.findByText(/disputa resuelta a favor del vendedor/i)).toBeInTheDocument();
    expect(screen.queryByText('Disputa doble')).not.toBeInTheDocument();
  });

  it('doble alta de categoría en el mismo tick produce un solo POST y una sola fila', async () => {
    const post = diferido<Response>();
    const fetchMock = vi.fn().mockReturnValue(post.promise);
    global.fetch = fetchMock;
    render(<PanelCategorias categoriasIniciales={[]} />);
    fireEvent.change(screen.getByLabelText(/nombre de la nueva categoría/i), { target: { value: 'Deportes' } });
    const boton = screen.getByRole('button', { name: /crear categoría/i });
    act(() => {
      boton.click();
      boton.click();
    });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledWith(`${BASE}/categorias`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, credentials: 'include',
      body: JSON.stringify({ nombre: 'Deportes' }),
    });
    await act(async () => { post.resolve(respuesta({ id: 20, nombre: 'Deportes' }, 201)); });
    expect(await screen.findAllByText('Deportes')).toHaveLength(1);
  });
});
