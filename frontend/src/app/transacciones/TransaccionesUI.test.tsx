import { act, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ComprasPage from '../compras/page';
import VentasPage from '../ventas/page';
import DetalleTransaccion from './TransaccionesUI';

/** Props observables del doble del panel existente. */
interface PanelMockProps {
  /** Transacción real entregada al panel. */
  transaccion: { id: number; estado: string; precioSnapshot: number };
  /** Rol derivado de pertenencia y nunca de la URL solamente. */
  rol: 'COMPRADOR' | 'VENDEDOR';
}

vi.mock('../PanelTransaccion', () => ({
  /**
   * Expone como texto las props recibidas sin duplicar acciones PATCH.
   *
   * @param props transacción y rol entregados por el detalle
   * @returns región observable del contrato de integración
   */
  default: function PanelTransaccionMock({ transaccion, rol }: PanelMockProps) {
    return <section aria-label="Panel de transacción">panel:{transaccion.id}:{transaccion.estado}:{transaccion.precioSnapshot}:{rol}</section>;
  }
}));

/** URL base determinista para comprobar cada lectura. */
const BASE = 'http://localhost:8080';

/**
 * Construye una respuesta Fetch mínima con cuerpo JSON.
 *
 * @param body cuerpo remoto de prueba
 * @param ok resultado HTTP
 * @param status código HTTP
 * @returns respuesta compatible con los transportes productivos
 */
function respuesta(body: unknown, ok = true, status = 200) {
  return { ok, status, json: async () => body } as Response;
}

/** DTO válido y completo del contrato real. */
const COMPRA = {
  id: 41,
  estado: 'enviado',
  precioSnapshot: 12345,
  fechaReservada: '2026-08-15T10:00:00-05:00',
  fechaEnviado: '2026-08-15T12:00:00-05:00',
  fechaEntregado: null,
  publicacionDescripcion: 'Teclado mecánico'
};

/** DTO válido de venta para comprobar aislamiento de flujos. */
const VENTA = {
  ...COMPRA,
  id: 72,
  estado: 'reservada',
  fechaEnviado: null,
  publicacionDescripcion: 'Monitor profesional'
};

describe('Listas reales de transacciones PHA06TSK12', () => {
  const originalApiUrl = process.env.NEXT_PUBLIC_API_URL;

  beforeEach(() => {
    process.env.NEXT_PUBLIC_API_URL = BASE;
    vi.restoreAllMocks();
  });

  afterEach(() => {
    process.env.NEXT_PUBLIC_API_URL = originalApiUrl;
  });

  it('/compras consulta exactamente compras con GET y cookie, renderiza DTO real y enlaza solo a /compras/<id>', async () => {
    const fetchMock = vi.fn().mockResolvedValue(respuesta([COMPRA]));
    global.fetch = fetchMock;
    render(<ComprasPage />);

    expect(await screen.findByText('Teclado mecánico')).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledWith(`${BASE}/transacciones/compras`, { method: 'GET', credentials: 'include' });
    expect(screen.getByText('S/ 123.45')).toBeInTheDocument();
    expect(screen.getByText('enviado')).toBeInTheDocument();
    expect(screen.getByText(/Reservada: 2026-08-15T10:00:00-05:00/)).toBeInTheDocument();
    const enlace = screen.getByRole('link', { name: /ver compra teclado mecánico/i });
    expect(enlace).toHaveAttribute('href', '/compras/41');
    expect(enlace).not.toHaveAttribute('href', '/ventas/41');
  });

  it('/ventas consulta exactamente ventas con GET y cookie, preserva orden y enlaza solo a /ventas/<id>', async () => {
    const segunda = { ...VENTA, id: 73, publicacionDescripcion: 'Mouse vertical' };
    const fetchMock = vi.fn().mockResolvedValue(respuesta([VENTA, segunda]));
    global.fetch = fetchMock;
    render(<VentasPage />);

    expect(await screen.findByText('Monitor profesional')).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledWith(`${BASE}/transacciones/ventas`, { method: 'GET', credentials: 'include' });
    const filas = screen.getAllByRole('listitem');
    expect(within(filas[0]).getByText('Monitor profesional')).toBeInTheDocument();
    expect(within(filas[1]).getByText('Mouse vertical')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /ver venta monitor profesional/i })).toHaveAttribute('href', '/ventas/72');
    expect(screen.queryByRole('link', { name: /ver compra monitor profesional/i })).not.toBeInTheDocument();
  });

  it('lista muestra loading y luego vacío explícito sin fabricar filas', async () => {
    let resolver!: (value: Response) => void;
    global.fetch = vi.fn().mockReturnValue(new Promise<Response>((resolve) => { resolver = resolve; }));
    render(<ComprasPage />);
    expect(screen.getByRole('status')).toHaveTextContent('Cargando compras');
    resolver(respuesta([]));
    expect(await screen.findByText('Aún no tienes compras.')).toBeInTheDocument();
    expect(screen.queryAllByRole('listitem')).toHaveLength(0);
  });

  it('lista muestra mensaje honesto del backend ante error y no renderiza datos', async () => {
    global.fetch = vi.fn().mockResolvedValue(respuesta({ mensaje: 'Sesión vencida' }, false, 403));
    render(<VentasPage />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Sesión vencida');
    expect(screen.queryAllByRole('listitem')).toHaveLength(0);
  });
});

describe('Detalle real y rol por pertenencia PHA06TSK12', () => {
  beforeEach(() => {
    process.env.NEXT_PUBLIC_API_URL = BASE;
    vi.restoreAllMocks();
  });

  it('compra completa detalle y lista canónica antes de pasar DTO real + COMPRADOR al panel', async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL) => Promise.resolve(respuesta(String(input).endsWith('/compras') ? [COMPRA] : COMPRA)));
    global.fetch = fetchMock;
    await act(async () => { render(<DetalleTransaccion params={Promise.resolve({ id: '41' })} flujo="compras" />); });

    expect(await screen.findByLabelText('Panel de transacción')).toHaveTextContent('panel:41:enviado:12345:COMPRADOR');
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock).toHaveBeenCalledWith(`${BASE}/transacciones/41`, { method: 'GET', credentials: 'include' });
    expect(fetchMock).toHaveBeenCalledWith(`${BASE}/transacciones/compras`, { method: 'GET', credentials: 'include' });
  });

  it('venta usa lista ventas y pasa VENDEDOR sin cruzar compras', async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL) => Promise.resolve(respuesta(String(input).endsWith('/ventas') ? [VENTA] : VENTA)));
    global.fetch = fetchMock;
    await act(async () => { render(<DetalleTransaccion params={Promise.resolve({ id: '72' })} flujo="ventas" />); });

    expect(await screen.findByLabelText('Panel de transacción')).toHaveTextContent('panel:72:reservada:12345:VENDEDOR');
    expect(fetchMock).toHaveBeenCalledWith(`${BASE}/transacciones/ventas`, { method: 'GET', credentials: 'include' });
    expect(fetchMock).not.toHaveBeenCalledWith(`${BASE}/transacciones/compras`, expect.anything());
  });

  it('ID ausente de lista canónica muestra error y no monta panel aunque detalle responda 200', async () => {
    global.fetch = vi.fn((input: RequestInfo | URL) => Promise.resolve(respuesta(String(input).endsWith('/compras') ? [] : COMPRA)));
    await act(async () => { render(<DetalleTransaccion params={Promise.resolve({ id: '41' })} flujo="compras" />); });

    expect(await screen.findByRole('alert')).toHaveTextContent(/no pertenece a tus compras/i);
    expect(screen.queryByLabelText('Panel de transacción')).not.toBeInTheDocument();
  });

  it('fallo de cualquiera de las dos lecturas muestra error y nunca usa detalle como sustituto', async () => {
    global.fetch = vi.fn((input: RequestInfo | URL) => Promise.resolve(String(input).endsWith('/compras')
      ? respuesta({ mensaje: 'No fue posible verificar compras' }, false, 500)
      : respuesta(COMPRA)));
    await act(async () => { render(<DetalleTransaccion params={Promise.resolve({ id: '41' })} flujo="compras" />); });

    expect(await screen.findByRole('alert')).toHaveTextContent('No fue posible verificar compras');
    expect(screen.queryByLabelText('Panel de transacción')).not.toBeInTheDocument();
  });

  it.each(['0', '-4', '7.5', '12abc', '9007199254740992'])('ID inválido %s no consulta ni monta acciones', async (id) => {
    const fetchMock = vi.fn();
    global.fetch = fetchMock;
    await act(async () => { render(<DetalleTransaccion params={Promise.resolve({ id })} flujo="ventas" />); });

    expect(await screen.findByRole('alert')).toHaveTextContent(/identificador de la transacción no es válido/i);
    expect(fetchMock).not.toHaveBeenCalled();
    expect(screen.queryByLabelText('Panel de transacción')).not.toBeInTheDocument();
  });

  it('navegación A→B no combina respuesta tardía A con identidad B', async () => {
    let resolverA!: (value: Response) => void;
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith('/41')) return new Promise<Response>((resolve) => { resolverA = resolve; });
      if (url.endsWith('/72')) return Promise.resolve(respuesta(VENTA));
      if (url.endsWith('/ventas')) return Promise.resolve(respuesta([VENTA]));
      return Promise.resolve(respuesta([COMPRA]));
    });
    global.fetch = fetchMock;
    let rerender!: ReturnType<typeof render>['rerender'];
    await act(async () => {
      ({ rerender } = render(<DetalleTransaccion params={Promise.resolve({ id: '41' })} flujo="compras" />));
    });
    await act(async () => {
      rerender(<DetalleTransaccion params={Promise.resolve({ id: '72' })} flujo="ventas" />);
    });

    expect(await screen.findByLabelText('Panel de transacción')).toHaveTextContent('panel:72:reservada:12345:VENDEDOR');
    resolverA(respuesta(COMPRA));
    await waitFor(() => expect(screen.getByLabelText('Panel de transacción')).toHaveTextContent('panel:72:reservada:12345:VENDEDOR'));
  });
});
