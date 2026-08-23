import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import PanelCentroNotificaciones, { NotificacionUI } from './PanelCentroNotificaciones';

// Mock de next/navigation para las notificaciones accionables (PHA12TSK05): el componente
// usa useRouter().push SOLO después de un PATCH exitoso (decisión 4 de PHA09TSK05-L01).
// Mismo patrón top-level de auth.test.tsx para evitar warnings de vi.mock anidado.
const mockPush = vi.fn();
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: mockPush })
}));

/**
 * @file PanelCentroNotificaciones.test.tsx
 * @description Pruebas de componente para PanelCentroNotificaciones (PHA04TSK20).
 *
 * Cobertura TDD (test previo de tasks.md, ampliado por decisión de Lino 2026-08-10):
 * 1. Test previo literal: al montar, consume únicamente GET {base}/notificaciones con
 *    credentials:'include' y renderiza la lista de notificaciones devueltas por el backend.
 * 2. Resalte visual de no leídas: una notificación con `leida === false` se distingue
 *    explícitamente (badge "No leída" con punto en Emerald #10B981 + mensaje en semibold);
 *    una con `leida === true` no lleva ese resalte.
 * 3. Mapeo de `tipo` a etiqueta legible (ENVIO_PENDIENTE_48H → "Envío pendiente",
 *    COMPRA_PENDIENTE_DIARIA → "Compra pendiente", VENTA_POR_ENTREGAR_DIARIA →
 *    "Venta por entregar"; tipo desconocido → fallback al tipo crudo en mayúsculas).
 * 4. `createdAt` ISO se muestra en formato legible con locale `es-PE` (America/Lima).
 * 5. `transaccionId` nullable: si es null no hay referencia a transacción; si existe se
 *    muestra "Transacción #N" en font-mono.
 * 6. Estados de carga: indicador durante la petición en vuelo.
 * 7. Estados de error: banner con el `mensaje` del backend (respuesta no-ok, ej. 403 sin
 *    sesión) y fallback con código HTTP; error de red → mensaje genérico de conexión.
 * 8. Array vacío → estado vacío "No tienes notificaciones".
 * 9. plan.md:366 — polling manual: re-fetch al ganar foco de la ventana (evento `focus`
 *    de window), además del fetch al montar.
 * 10. Higiene de listeners: el listener de `focus` se remueve en unmount (no fetch tras
 *     desmontar el componente).
 * 11-14. (PHA12TSK05, recuperación del incidente 2026-08-23) Notificaciones accionables:
 *     (a) ADMIN ve moderación/disputas como links a su gestión; (b) USER ve compra/venta/
 *     envío/disputa como links a detalle según "tu compra"/"tu venta" del mensaje;
 *     (c) click marca leída vía PATCH y navega (PATCH antes que push); (d) botón
 *     "Marcar como leída" hace PATCH sin navegar.
 */

/** URL base fija del backend para las aserciones de fetch. */
const BASE = 'http://localhost:8080';

/**
 * Construye una notificación de prueba con valores deterministas, modelando el contrato de
 * `NotificacionResponseDto` de `GET /notificaciones` (PHA04TSK16): id, mensaje, tipo, leida,
 * createdAt ISO8601 y transaccionId nullable.
 *
 * @param overrides campos opcionales a sobreescribir
 * @returns objeto {@link NotificacionUI} listo para renderizar
 */
function notificacionBase(overrides: Partial<NotificacionUI> = {}): NotificacionUI {
  return {
    id: 1,
    mensaje: 'Tu compra permanece sin envío tras 48 horas. Puedes esperar o cancelarla.',
    tipo: 'ENVIO_PENDIENTE_48H',
    leida: false,
    createdAt: '2026-08-10T12:00:00',
    transaccionId: 42,
    ...overrides
  };
}

/** Respuesta simulada 200 de `GET /notificaciones` (JSON array). */
function okResponse(items: NotificacionUI[]) {
  return { ok: true, status: 200, json: async () => items };
}

/**
 * Formatea la fecha con el MISMO formato que usa el componente (Intl.DateTimeFormat locale
 * `es-PE`, timeZone America/Lima). El test usa la función como oráculo para no depender de
 * la salida exacta de la librería ni de la zona horaria del runner.
 *
 * @param isoFecha timestamp ISO8601 a formatear
 * @returns string legible con fecha y hora en locale es-PE
 */
function formatoEsperado(isoFecha: string): string {
  return new Intl.DateTimeFormat('es-PE', {
    dateStyle: 'medium',
    timeStyle: 'short',
    timeZone: 'America/Lima'
  }).format(new Date(isoFecha));
}

describe('PanelCentroNotificaciones (PHA04TSK20)', () => {
  const originalApiUrl = process.env.NEXT_PUBLIC_API_URL;

  beforeEach(() => {
    vi.restoreAllMocks();
    process.env.NEXT_PUBLIC_API_URL = BASE;
  });

  afterEach(() => {
    process.env.NEXT_PUBLIC_API_URL = originalApiUrl;
  });

  it('al montar consume GET {base}/notificaciones con credentials include y renderiza la lista devuelta', async () => {
    const notificaciones = [
      notificacionBase({ id: 1, mensaje: 'Primer aviso', leida: true }),
      notificacionBase({ id: 2, mensaje: 'Segundo aviso' })
    ];
    const fetchMock = vi.fn().mockResolvedValue(okResponse(notificaciones));
    global.fetch = fetchMock;

    render(<PanelCentroNotificaciones />);

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(`${BASE}/notificaciones`, {
        method: 'GET',
        credentials: 'include'
      });
    });

    // Test previo literal: la lista renderiza LAS notificaciones devueltas por el backend.
    expect(await screen.findByText('Primer aviso')).toBeInTheDocument();
    expect(screen.getByText('Segundo aviso')).toBeInTheDocument();
  });

  it('resalte visual de no leídas: badge "No leída" + mensaje en semibold solo cuando leida es false', async () => {
    const notificaciones = [
      notificacionBase({ id: 1, mensaje: 'Aviso sin leer', leida: false }),
      notificacionBase({ id: 2, mensaje: 'Aviso ya leído', leida: true })
    ];
    const fetchMock = vi.fn().mockResolvedValue(okResponse(notificaciones));
    global.fetch = fetchMock;

    render(<PanelCentroNotificaciones />);

    // La no leída lleva el badge distinguible (resalte visual del test previo ampliado).
    expect(await screen.findByText('No leída')).toBeInTheDocument();
    expect(screen.getByText('Aviso sin leer')).toHaveClass('font-semibold');

    // Para la leída (leida === true) el resalte está ausente: sin badge ni semibold.
    expect(screen.queryAllByText('No leída')).toHaveLength(1);
    expect(screen.getByText('Aviso ya leído')).not.toHaveClass('font-semibold');
  });

  it('muestra etiqueta legible del tipo, fecha en locale es-PE y referencia a transacción en font-mono', async () => {
    const createdAt = '2026-08-10T12:00:00';
    const notificaciones = [
      notificacionBase({ tipo: 'ENVIO_PENDIENTE_48H', createdAt, transaccionId: 42, id: 1 }),
      notificacionBase({ mensaje: 'Aviso compra', tipo: 'COMPRA_PENDIENTE_DIARIA', createdAt, transaccionId: 7, id: 2 }),
      notificacionBase({ mensaje: 'Aviso venta', tipo: 'VENTA_POR_ENTREGAR_DIARIA', createdAt, transaccionId: 8, id: 3 }),
      notificacionBase({ mensaje: 'Aviso extraño', tipo: 'TIPO_DESCONOCIDO', createdAt, transaccionId: null, id: 4 })
    ];
    const fetchMock = vi.fn().mockResolvedValue(okResponse(notificaciones));
    global.fetch = fetchMock;

    render(<PanelCentroNotificaciones />);

    // Mapeo tipo → etiqueta entendible (decisión de presentación declarada en el Artifact).
    expect(await screen.findByText('Envío pendiente')).toBeInTheDocument();
    expect(screen.getByText('Compra pendiente')).toBeInTheDocument();
    expect(screen.getByText('Venta por entregar')).toBeInTheDocument();
    // Fallback para tipos desconocidos: el tipo crudo en mayúsculas.
    expect(screen.getByText('TIPO_DESCONOCIDO')).toBeInTheDocument();

    // createdAt ISO → fecha/hora legible en es-PE (oráculo con el mismo formato).
    const fechaEsperada = formatoEsperado(createdAt);
    await waitFor(() => {
      expect(screen.queryAllByText(fechaEsperada)).toHaveLength(4);
    });
    expect(screen.getAllByText(fechaEsperada)[0].textContent).toBe(fechaEsperada);

    // transaccionId presente: referenciado en font-mono; null: sin referencia a transacción.
    expect(screen.getByText('Transacción #42')).toBeInTheDocument();
    expect(screen.queryByText(/transacción #4$/i)).not.toBeInTheDocument();
  });

  it('muestra un indicador de carga mientras la petición está en vuelo', async () => {
    // Petición que nunca resuelve: permite asertar el estado de carga sin carrera.
    const fetchMock = vi.fn().mockReturnValue(new Promise(() => {}));
    global.fetch = fetchMock;

    render(<PanelCentroNotificaciones />);

    expect(screen.getByRole('status')).toHaveAttribute('aria-busy', 'true');
    expect(screen.getByText(/cargando notificaciones/i)).toBeInTheDocument();
  });

  it('muestra banner de error con el mensaje del backend ante respuesta no-ok (403 sin sesión)', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 403,
      json: async () => ({ mensaje: 'AccesoDenegadoException: No hay sesión válida' })
    });
    global.fetch = fetchMock;

    render(<PanelCentroNotificaciones />);

    expect(await screen.findByText(/no hay sesión válida/i)).toBeInTheDocument();
    // Sin lista que renderizar.
    expect(screen.queryByRole('listitem')).not.toBeInTheDocument();
  });

  it('muestra fallback con código HTTP cuando la respuesta no-ok no trae mensaje y mensaje genérico en error de red', async () => {
    const fetchSinJson = vi.fn().mockResolvedValue({ ok: false, status: 500, json: async () => { throw new Error('no json'); } });
    global.fetch = fetchSinJson;

    const { unmount } = render(<PanelCentroNotificaciones />);

    expect(await screen.findByText(/error al cargar las notificaciones \(código 500\)/i)).toBeInTheDocument();
    unmount();

    const fetchRed = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));
    global.fetch = fetchRed;

    render(<PanelCentroNotificaciones />);
    expect(await screen.findByText(/error de red al conectar con el servidor/i)).toBeInTheDocument();
  });

  it('array vacío del backend muestra el estado vacío "No tienes notificaciones"', async () => {
    const fetchMock = vi.fn().mockResolvedValue(okResponse([]));
    global.fetch = fetchMock;

    render(<PanelCentroNotificaciones />);

    // Sincronización: esperamos a que el fetch del montaje haya resuelto (200 con array
    // vacío) antes de asertar; regex tolerante al carácter separador que jsdom/React use.
    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledTimes(1);
    });
    expect(
      await screen.findByText((contenido) => contenido.replace(/\s+/g, ' ').includes('No tienes notificaciones.'))
    ).toBeInTheDocument();
    expect(screen.queryByRole('listitem')).not.toBeInTheDocument();
  });

  it('plan.md:366: al ganar foco de la ventana vuelve a consultar GET /notificaciones (polling manual)', async () => {
    const primeraCarga = [notificacionBase({ id: 1, mensaje: 'Aviso original' })];
    const trasRefoco = [
      notificacionBase({ id: 1, mensaje: 'Aviso original' }),
      notificacionBase({ id: 2, mensaje: 'Aviso nuevo al enfocar' })
    ];
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(okResponse(primeraCarga))
      .mockResolvedValueOnce(okResponse(trasRefoco));
    global.fetch = fetchMock;

    render(<PanelCentroNotificaciones />);

    expect(await screen.findByText('Aviso original')).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(1);

    // El usuario vuelve a la pestaña: window dispara el evento `focus`.
    fireEvent.focus(window);

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledTimes(2);
    });
    expect(await screen.findByText('Aviso nuevo al enfocar')).toBeInTheDocument();
  });

  it('el listener de focus se remueve al desmontar: no hay fetch tras unmount', async () => {
    const fetchMock = vi.fn().mockResolvedValue(okResponse([notificacionBase()]));
    global.fetch = fetchMock;

    const { unmount } = render(<PanelCentroNotificaciones />);
    await screen.findByText(/sin envío tras 48 horas/i);
    expect(fetchMock).toHaveBeenCalledTimes(1);

    unmount();
    fireEvent.focus(window);

    // Si el listener no se limpiara, un focus posterior dispararía una segunda petición.
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});

describe('PanelCentroNotificaciones — notificaciones accionables (PHA12TSK05)', () => {
  const originalApiUrl = process.env.NEXT_PUBLIC_API_URL;

  /**
   * Construye un fetch simulado que responde el GET inicial con la lista dada y cualquier
   * PATCH (marcar como leída) con 200 y DTO vacío — suficiente para los flujos del criterio
   * (a)-(d), que validan la LLAMADA al PATCH y sus efectos locales, no el cuerpo devuelto
   * (la actualización del badge es local, decisión declarada en el Artifact).
   *
   * @param items notificaciones que devuelve el GET de montaje
   * @returns mock de fetch que despacha por método HTTP
   */
  function fetchGetYPatch(items: NotificacionUI[]) {
    // vi.fn() sin firma concreta (patrón de los tests previos del archivo): asignable a
    // global.fetch sin casts; el despacho por método HTTP vive en mockImplementation.
    return vi.fn().mockImplementation((url: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'PATCH') {
        return Promise.resolve({ ok: true, status: 200, json: async () => ({}) });
      }
      return Promise.resolve(okResponse(items));
    });
  }

  beforeEach(() => {
    vi.restoreAllMocks();
    mockPush.mockClear();
    process.env.NEXT_PUBLIC_API_URL = BASE;
  });

  afterEach(() => {
    process.env.NEXT_PUBLIC_API_URL = originalApiUrl;
  });

  it('(a) ADMIN ve notificaciones de moderación/disputas como links a su gestión', async () => {
    const notificaciones = [
      notificacionBase({
        id: 1,
        tipo: 'PUBLICACION_PENDIENTE_APROBAR',
        mensaje: 'Publicación #12 pendiente de aprobación',
        transaccionId: null
      }),
      notificacionBase({
        id: 2,
        tipo: 'DISPUTA_PENDIENTE_RESOLVER',
        mensaje: 'Disputa abierta en la transacción #7; pendiente de resolución administrativa',
        transaccionId: 7
      })
    ];
    const fetchMock = fetchGetYPatch(notificaciones);
    global.fetch = fetchMock;

    render(<PanelCentroNotificaciones />);

    await screen.findByText('Publicación #12 pendiente de aprobación');
    // Ambas notificaciones ADMIN son links navegables.
    const links = screen.getAllByRole('link');
    expect(links).toHaveLength(2);

    // La fila de moderación navega a la gestión de moderación del admin.
    fireEvent.click(within(links[0]).getByText(/pendiente de aprobación/i));
    await waitFor(() => expect(mockPush).toHaveBeenCalledWith('/admin/moderacion'));

    // La fila de disputas navega al panel de disputas del admin.
    fireEvent.click(within(links[1]).getByText(/resolución administrativa/i));
    await waitFor(() => expect(mockPush).toHaveBeenCalledWith('/admin/disputas'));
  });

  it('(b) USER ve links a detalle de compra/venta/envío/disputa según el mensaje ("tu compra"/"tu venta")', async () => {
    const notificaciones = [
      notificacionBase({ id: 1, tipo: 'COMPRA_CONFIRMADA', mensaje: 'Confirmaste la recepción de tu compra #41', transaccionId: 41 }),
      notificacionBase({ id: 2, tipo: 'ENVIO_MARCADO', mensaje: 'Marcaste tu venta #41 como enviada', transaccionId: 41 }),
      notificacionBase({ id: 3, tipo: 'ENTREGA_MARCADA', mensaje: 'El vendedor marcó tu compra #43 como entregada; confirma la recepción dentro de las próximas 48 horas', transaccionId: 43 }),
      notificacionBase({ id: 4, tipo: 'DISPUTA_ABIERTA', mensaje: 'Abriste una disputa sobre tu compra #42; los fondos quedan congelados hasta la resolución', transaccionId: 42 }),
      notificacionBase({ id: 5, tipo: 'DISPUTA_RESUELTA', mensaje: 'Se resolvió la disputa de tu venta #42: fondos devueltos al comprador', transaccionId: 42 })
    ];
    const fetchMock = fetchGetYPatch(notificaciones);
    global.fetch = fetchMock;

    render(<PanelCentroNotificaciones />);

    await screen.findByText('Confirmaste la recepción de tu compra #41');
    // Las 5 notificaciones USER accionables son links navegables.
    expect(screen.getAllByRole('link')).toHaveLength(5);

    // Mensaje dirigido al comprador ("tu compra") → detalle en /compras/{transaccionId}.
    const filaCompra = screen.getByText(/Abriste una disputa sobre tu compra/).closest('li');
    fireEvent.click(within(filaCompra as HTMLElement).getByRole('link'));
    await waitFor(() => expect(mockPush).toHaveBeenCalledWith('/compras/42'));

    // Mensaje dirigido al vendedor ("tu venta") → detalle en /ventas/{transaccionId}.
    const filaVenta = screen.getByText(/Se resolvió la disputa de tu venta/).closest('li');
    fireEvent.click(within(filaVenta as HTMLElement).getByRole('link'));
    await waitFor(() => expect(mockPush).toHaveBeenCalledWith('/ventas/42'));
  });

  it('(c) click en la notificación marca leida vía PATCH y navega al destino (PATCH antes que push)', async () => {
    const compra = notificacionBase({ id: 5, tipo: 'COMPRA_CONFIRMADA', mensaje: 'Confirmaste la recepción de tu compra #44', transaccionId: 44 });
    const envio = notificacionBase({ id: 6, tipo: 'ENVIO_MARCADO', mensaje: 'El vendedor marcó tu compra #45 como enviada', transaccionId: 45 });
    const fetchMock = vi.fn().mockImplementation((url: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'PATCH') {
        return Promise.resolve({ ok: true, status: 200, json: async () => ({}) });
      }
      return Promise.resolve(okResponse([compra, envio]));
    });
    global.fetch = fetchMock;

    render(<PanelCentroNotificaciones />);

    // Estado inicial: sin leer → badge "No leída" visible en AMBAS filas.
    expect((await screen.findAllByText('No leída')).length).toBe(2);

    // Click con mouse sobre la primera fila: await PATCH → estado local → router.push.
    const filaCompra = screen.getByText(/recepción de tu compra #44/).closest('li');
    fireEvent.click(within(filaCompra as HTMLElement).getByRole('link'));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(`${BASE}/notificaciones/5/leer`, {
        method: 'PATCH',
        credentials: 'include'
      });
    });

    // Actualización local SIN refetch: el badge de ESA fila desaparece y solo hubo 1 GET.
    await waitFor(() => {
      expect(within(filaCompra as HTMLElement).queryByText('No leída')).not.toBeInTheDocument();
    });
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method !== 'PATCH')).toHaveLength(1);
    expect(mockPush).toHaveBeenCalledWith('/compras/44');

    // Orden garantizado: el PATCH se emitió ANTES de router.push (decisión 4 PHA09TSK05-L01).
    const indicePatch = fetchMock.mock.calls.findIndex(([, init]) => init?.method === 'PATCH');
    expect(fetchMock.mock.invocationCallOrder[indicePatch]).toBeLessThan(mockPush.mock.invocationCallOrder[0]);

    // Accesibilidad teclado: Enter sobre otra fila dispara el mismo flujo completo.
    const filaEnvio = screen.getByText(/marcó tu compra #45 como enviada/).closest('li');
    fireEvent.keyDown(within(filaEnvio as HTMLElement).getByRole('link'), { key: 'Enter' });

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(`${BASE}/notificaciones/6/leer`, {
        method: 'PATCH',
        credentials: 'include'
      });
    });
    await waitFor(() => {
      expect(within(filaEnvio as HTMLElement).queryByText('No leída')).not.toBeInTheDocument();
    });
    expect(mockPush).toHaveBeenLastCalledWith('/compras/45');
  });

  it('(d) el botón "Marcar como leída" hace PATCH sin navegar ni disparar el click del contenedor', async () => {
    const entrega = notificacionBase({
      id: 9,
      tipo: 'ENTREGA_MARCADA',
      mensaje: 'El vendedor marcó tu compra #46 como entregada; confirma la recepción dentro de las próximas 48 horas',
      transaccionId: 46
    });
    const fetchMock = fetchGetYPatch([entrega]);
    global.fetch = fetchMock;

    render(<PanelCentroNotificaciones />);

    expect(await screen.findByText('No leída')).toBeInTheDocument();

    // El botón vive DENTRO del contenedor clickable: stopPropagation debe impedir que el
    // click burbujee hasta el div[role="link"] (que navegaría tras el PATCH).
    fireEvent.click(screen.getByRole('button', { name: 'Marcar como leída' }));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(`${BASE}/notificaciones/9/leer`, {
        method: 'PATCH',
        credentials: 'include'
      });
    });

    // Badge retirado por actualización local, sin refetch (1 solo GET, el del montaje).
    await waitFor(() => expect(screen.queryByText('No leída')).not.toBeInTheDocument());
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method !== 'PATCH')).toHaveLength(1);

    // Sin navegación: el botón solo marca leída (decisión 5 de PHA09TSK05-L01).
    expect(mockPush).not.toHaveBeenCalled();
  });
});