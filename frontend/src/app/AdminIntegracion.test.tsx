import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import Shell from './Shell';
import AdminPage from './admin/page';
import ModeracionPage from './admin/moderacion/page';
import CategoriasPage from './admin/categorias/page';
import DisputasPage from './admin/disputas/page';
import UsuariosBloqueadosPage from './admin/usuarios-bloqueados/page';
import PanelCategorias, { type CategoriaItem } from './PanelCategorias';

/** Ruta que el mock de Next.js expone al shell durante cada prueba. */
let pathnameActual = '/admin';

vi.mock('next/navigation', () => ({
  usePathname: () => pathnameActual,
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
}));

/** URL base determinista usada para verificar los contratos HTTP administrativos. */
const BASE = 'http://localhost:8080';

/** Identidad ADMIN válida devuelta por `GET /usuarios/me`. */
const ADMIN = { id: 1, email: 'admin@easymarket.dev', rol: 'ADMIN' } as const;

/** Identidad USUARIO válida para probar acceso directo sin acciones ni lecturas admin. */
const USUARIO = { id: 2, email: 'usuario@easymarket.dev', rol: 'USUARIO' } as const;

/**
 * Construye una respuesta `fetch` mínima y compatible con los transportes de la UI.
 *
 * @param body cuerpo JSON que devolverá la respuesta
 * @param status estado HTTP, 200 por defecto
 * @returns objeto con la forma de `Response` usada por los componentes
 */
function respuesta(body: unknown, status = 200): Response {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as Response;
}

/**
 * Renderiza una página administrativa dentro del shell real que resuelve identidad.
 *
 * @param ruta ruta administrativa simulada
 * @param Pagina componente de página correspondiente
 * @returns utilidades de render de React Testing Library
 */
function renderRuta(ruta: string, Pagina: () => React.JSX.Element) {
  pathnameActual = ruta;
  return render(<Shell><Pagina /></Shell>);
}

describe('PHA06TSK13 - rutas administrativas reales', () => {
  const apiOriginal = process.env.NEXT_PUBLIC_API_URL;

  beforeEach(() => {
    vi.restoreAllMocks();
    process.env.NEXT_PUBLIC_API_URL = BASE;
  });

  afterEach(() => {
    process.env.NEXT_PUBLIC_API_URL = apiOriginal;
  });

  it('ADMIN carga identidad y las seis métricas reales del tablero, incluidos centavos PEN sin float', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === `${BASE}/usuarios/me`) return respuesta(ADMIN);
      if (url === `${BASE}/admin/tablero`) return respuesta({
        publicacionesPendientes: 7,
        disputasAbiertas: 3,
        cuentasBloqueadas: 2,
        volumenEscrowCentavos: 123456,
        fondosLiberadosCentavos: 98765,
        transaccionesFinalizadas: 11,
      });
      throw new Error(`URL inesperada: ${url}`);
    });
    global.fetch = fetchMock;

    renderRuta('/admin', AdminPage);

    expect(screen.getByText(/cargando sesión/i)).toBeInTheDocument();
    expect(await screen.findByText('S/ 1234.56')).toBeInTheDocument();
    expect(screen.getByText('S/ 987.65')).toBeInTheDocument();
    for (const valor of ['7', '3', '2', '11']) expect(screen.getByText(valor)).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledWith(`${BASE}/usuarios/me`, { method: 'GET', credentials: 'include' });
    expect(fetchMock).toHaveBeenCalledWith(`${BASE}/admin/tablero`, expect.objectContaining({ method: 'GET', credentials: 'include', signal: expect.any(AbortSignal) }));
  });

  it('moderación carga pendientes y catálogo, resuelve nombres por IDs reales y conserva el PATCH existente', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === `${BASE}/usuarios/me`) return respuesta(ADMIN);
      if (url === `${BASE}/publicaciones?estado=PENDIENTE_REVISION`) return respuesta([{
        id: 41, precio: 25000, stock: 2, estado: 'PENDIENTE_REVISION', descripcion: 'Laptop usada',
        categoriaId: 8, subcategoriaId: 81, usuarioId: 9, usuarioEmail: 'vendedor@example.com', imagenFilename: 'laptop-usada.jpg',
      }]);
      if (url === `${BASE}/categorias`) return respuesta([{ id: 8, nombre: 'Tecnología', subcategorias: [{ id: 81, nombre: 'Laptops' }] }]);
      if (url === `${BASE}/publicaciones/41/moderar` && init?.method === 'PATCH') return respuesta({ id: 41, estado: 'APROBADA' });
      throw new Error(`URL inesperada: ${url}`);
    });
    global.fetch = fetchMock;

    renderRuta('/admin/moderacion', ModeracionPage);

    expect(await screen.findByText('Tecnología / Laptops')).toBeInTheDocument();
    expect(screen.getByText('S/ 250.00')).toBeInTheDocument();
    expect(screen.getByRole('img', { name: 'Laptop usada' })).toHaveAttribute('src', '/imagenes/publicaciones/laptop-usada.jpg');
    expect(screen.getByText('vendedor@example.com')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Aprobar' }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(`${BASE}/publicaciones/41/moderar`, expect.objectContaining({
      method: 'PATCH', credentials: 'include', body: JSON.stringify({ accion: 'aprobar' }),
    })));
  });

  it('catálogo carga el árbol real y muestra el estado vacío sin fabricar categorías', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === `${BASE}/usuarios/me`) return respuesta(ADMIN);
      if (url === `${BASE}/categorias`) return respuesta([]);
      throw new Error(`URL inesperada: ${url}`);
    });
    global.fetch = fetchMock;

    renderRuta('/admin/categorias', CategoriasPage);

    expect(await screen.findByText(/no hay categorías registradas/i)).toBeInTheDocument();
    expect(screen.queryByText('Electrónica')).not.toBeInTheDocument();
  });

  it('disputas carga GET /admin/disputas y pasa lista y rol ADMIN reales a PanelDisputas', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === `${BASE}/usuarios/me`) return respuesta(ADMIN);
      if (url === `${BASE}/admin/disputas`) return respuesta([{
        id: 77, estado: 'disputa', precioSnapshot: 4500, fechaReservada: '2026-08-15T10:00:00Z',
        fechaEnviado: '2026-08-15T11:00:00Z', fechaEntregado: '2026-08-15T12:00:00Z',
        publicacionDescripcion: 'Teclado mecánico',
      }]);
      throw new Error(`URL inesperada: ${url}`);
    });
    global.fetch = fetchMock;

    renderRuta('/admin/disputas', DisputasPage);

    expect(await screen.findByText('Teclado mecánico')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /liberar fondos/i })).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledWith(`${BASE}/admin/disputas`, expect.objectContaining({ method: 'GET', credentials: 'include', signal: expect.any(AbortSignal) }));
  });

  it('cuentas bloqueadas carga usuarios reales y desbloquea por usuarioId sin body', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === `${BASE}/usuarios/me`) return respuesta(ADMIN);
      if (url === `${BASE}/admin/usuarios/bloqueados`) return respuesta([{ usuarioId: 93, email: 'bloqueado@example.com' }]);
      if (url === `${BASE}/admin/usuarios/93/desbloquear` && init?.method === 'POST') return respuesta({ mensaje: 'Cuenta desbloqueada exitosamente' });
      throw new Error(`URL inesperada: ${url}`);
    });
    global.fetch = fetchMock;

    renderRuta('/admin/usuarios-bloqueados', UsuariosBloqueadosPage);

    expect(await screen.findByText('bloqueado@example.com')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /desbloquear/i }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(`${BASE}/admin/usuarios/93/desbloquear`, {
      method: 'POST', credentials: 'include',
    }));
    expect(await screen.findByText('Cuenta desbloqueada exitosamente')).toBeInTheDocument();
    expect(screen.queryByText('bloqueado@example.com')).not.toBeInTheDocument();
  });

  it.each([
    ['/admin', AdminPage],
    ['/admin/moderacion', ModeracionPage],
    ['/admin/categorias', CategoriasPage],
    ['/admin/disputas', DisputasPage],
    ['/admin/usuarios-bloqueados', UsuariosBloqueadosPage],
  ] as const)('USUARIO en URL directa %s no renderiza acciones ni dispara lecturas/escrituras admin', async (ruta, Pagina) => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      // PHA15TSK06: la campana del shell consulta el contador de no leídas al cargar la
      // sesión — no es una lectura/escritura administrativa y se responde explícitamente.
      if (url === `${BASE}/notificaciones/no-leidas/count`) return respuesta({ cantidad: 0 });
      return respuesta(USUARIO);
    });
    global.fetch = fetchMock;

    renderRuta(ruta, Pagina);

    expect(await screen.findByText(/no tienes permisos para acceder a esta sección/i)).toBeInTheDocument();
    // PHA15TSK07-L02: el fetch del contador de la campana (PHA15TSK06) se despacha en los
    // efectos pasivos del mismo commit que revela el mensaje de denegación — una aserción
    // inmediata de conteo pierde la carrera contra ese despacho (fallo observado:
    // "expected to be called 2 times, but got 1 times"). waitFor espera a que ambas llamadas
    // (identidad + contador) existan; el conteo se estabiliza en 2 porque el shell no vuelve
    // a consultar el contador (sin evento focus de ventana ni evento del panel en jsdom).
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    expect(fetchMock).toHaveBeenCalledWith(`${BASE}/usuarios/me`, { method: 'GET', credentials: 'include' });
    // La única llamada adicional del shell es el contador de la campana (PHA15TSK06).
    expect(fetchMock).toHaveBeenCalledWith(`${BASE}/notificaciones/no-leidas/count`, { method: 'GET', credentials: 'include' });
    const areaContenido = screen.getByRole('main');
    expect(within(areaContenido).queryAllByRole('button')).toHaveLength(0);
    expect(within(areaContenido).queryAllByRole('form')).toHaveLength(0);
  });

  it('presenta errores HTTP y de red sin mostrar métricas ni filas fabricadas', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === `${BASE}/usuarios/me`) return respuesta(ADMIN);
      return respuesta({ mensaje: 'Servicio administrativo no disponible' }, 503);
    });
    global.fetch = fetchMock;

    renderRuta('/admin', AdminPage);

    expect(await screen.findByRole('alert')).toHaveTextContent('Servicio administrativo no disponible');
    expect(screen.queryByText('S/ 0.00')).not.toBeInTheDocument();
  });

  it('mantiene un loading explícito mientras la lectura administrativa está pendiente', async () => {
    const tableroPendiente = new Promise<Response>(() => undefined);
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === `${BASE}/usuarios/me`) return respuesta(ADMIN);
      return tableroPendiente;
    });
    global.fetch = fetchMock;

    renderRuta('/admin', AdminPage);

    expect(await screen.findByText(/cargando datos administrativos/i)).toBeInTheDocument();
  });

  it.each([
    ['/admin/moderacion', ModeracionPage, 'No hay publicaciones pendientes de revisión.'],
    ['/admin/disputas', DisputasPage, 'No hay disputas pendientes de resolución.'],
    ['/admin/usuarios-bloqueados', UsuariosBloqueadosPage, 'No hay cuentas con bloqueo permanente.'],
  ] as const)('%s representa la lista vacía real sin fallback mock', async (ruta, Pagina, textoVacio) => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === `${BASE}/usuarios/me`) return respuesta(ADMIN);
      return respuesta([]);
    });
    global.fetch = fetchMock;

    renderRuta(ruta, Pagina);

    expect(await screen.findByText(textoVacio)).toBeInTheDocument();
  });

  it('muestra un error de red real sin sustituirlo por publicaciones mock', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (String(input) === `${BASE}/usuarios/me`) return respuesta(ADMIN);
      throw new TypeError('Failed to fetch');
    });
    global.fetch = fetchMock;

    renderRuta('/admin/moderacion', ModeracionPage);

    expect(await screen.findByRole('alert')).toHaveTextContent('Error de red al conectar con el servidor');
    expect(screen.queryByText('Electrónica')).not.toBeInTheDocument();
  });
});

describe('PHA06TSK13 - las seis familias CRUD del catálogo', () => {
  const categoriasIniciales: CategoriaItem[] = [{ id: 10, nombre: 'Hogar', subcategorias: [{ id: 101, nombre: 'Muebles' }] }];

  beforeEach(() => {
    vi.restoreAllMocks();
    process.env.NEXT_PUBLIC_API_URL = BASE;
  });

  it('crea, edita y elimina categorías y subcategorías con rutas/body exactos y respuestas confirmadas', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(respuesta({ id: 20, nombre: 'Deportes' }, 201))
      .mockResolvedValueOnce(respuesta({ id: 10, nombre: 'Casa' }))
      .mockResolvedValueOnce(respuesta({}, 204))
      .mockResolvedValueOnce(respuesta({ id: 102, nombre: 'Decoración', categoriaId: 10 }, 201))
      .mockResolvedValueOnce(respuesta({ id: 101, nombre: 'Mobiliario', categoriaId: 10 }))
      .mockResolvedValueOnce(respuesta({}, 204));
    global.fetch = fetchMock;

    render(<PanelCategorias categoriasIniciales={categoriasIniciales} />);

    fireEvent.change(screen.getByLabelText(/nombre de la nueva categoría/i), { target: { value: 'Deportes' } });
    fireEvent.click(screen.getByRole('button', { name: /crear categoría/i }));
    expect(await screen.findByText('Deportes')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /editar categoría Hogar/i }));
    fireEvent.change(screen.getByLabelText(/nuevo nombre de la categoría Hogar/i), { target: { value: 'Casa' } });
    fireEvent.click(screen.getByRole('button', { name: /guardar categoría Hogar/i }));
    expect(await screen.findByText('Casa')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /eliminar categoría Deportes/i }));
    await waitFor(() => expect(screen.queryByText('Deportes')).not.toBeInTheDocument());

    fireEvent.change(screen.getByLabelText(/nueva subcategoría de Casa/i), { target: { value: 'Decoración' } });
    fireEvent.click(screen.getByRole('button', { name: /crear subcategoría en Casa/i }));
    expect(await screen.findByText('Decoración')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /editar subcategoría Muebles/i }));
    fireEvent.change(screen.getByLabelText(/nuevo nombre de la subcategoría Muebles/i), { target: { value: 'Mobiliario' } });
    fireEvent.click(screen.getByRole('button', { name: /guardar subcategoría Muebles/i }));
    expect(await screen.findByText('Mobiliario')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /eliminar subcategoría Decoración/i }));
    await waitFor(() => expect(screen.queryByText('Decoración')).not.toBeInTheDocument());

    expect(fetchMock.mock.calls).toEqual([
      [`${BASE}/categorias`, expect.objectContaining({ method: 'POST', credentials: 'include', body: JSON.stringify({ nombre: 'Deportes' }) })],
      [`${BASE}/categorias/10`, expect.objectContaining({ method: 'PUT', credentials: 'include', body: JSON.stringify({ nombre: 'Casa' }) })],
      [`${BASE}/categorias/20`, { method: 'DELETE', credentials: 'include' }],
      [`${BASE}/categorias/10/subcategorias`, expect.objectContaining({ method: 'POST', credentials: 'include', body: JSON.stringify({ nombre: 'Decoración' }) })],
      [`${BASE}/categorias/10/subcategorias/101`, expect.objectContaining({ method: 'PUT', credentials: 'include', body: JSON.stringify({ nombre: 'Mobiliario' }) })],
      [`${BASE}/categorias/10/subcategorias/102`, { method: 'DELETE', credentials: 'include' }],
    ]);
  });

  it('usa Deep Navy y texto blanco en el botón Editar de categoría', () => {
    render(<PanelCategorias categoriasIniciales={categoriasIniciales} />);

    const editarCategoria = screen.getByRole('button', { name: /editar categoría hogar/i });
    expect(editarCategoria).toHaveClass('bg-[#0F172A]', 'text-white');
  });
});
