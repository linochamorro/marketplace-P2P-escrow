import { render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import PanelSaldo, { MovimientoSaldoUI, SaldoUI } from './PanelSaldo';

/**
 * @file PanelSaldo.test.tsx
 * @description Pruebas de componente para PanelSaldo (PHA04TSK21).
 *
 * Cobertura TDD (test previo literal de tasks.md: "muestra saldo y detalle de
 * movimientos", ampliada con los estados de la capa de transporte al patrón
 * PHA04TSK20):
 * 1. Test previo literal: al montar, consume únicamente GET {base}/usuarios/me/saldo
 *    con credentials:'include' y renderiza el saldo disponible (S/ en centavos) y el
 *    detalle de movimientos devuelto por el backend (monto, fecha es-PE y
 *    transacción de origen).
 * 2. Estados de carga: indicador durante la petición en vuelo.
 * 3. Estados de error: banner con el `mensaje` del backend (respuesta no-ok, ej. 403
 *    sin sesión) y fallback con código HTTP; error de red → mensaje genérico.
 * 4. Detalle vacío legítimo (200 con movimientos: []): se muestra el saldo y el
 *    estado vacío del detalle, sin elementos de lista.
 * 5. Orden del detalle: se renderiza en el orden exacto en que llega el array del
 *    backend (el contrato NO declara orden; el componente no reordena).
 * 6. Respuesta 200 con forma inesperada (no objeto saldo): banner de error, nunca
 *    datos fabricados (un saldo inventado mentiría sobre dinero — constitution
 *    principio 3).
 */

/** URL base fija del backend para las aserciones de fetch. */
const BASE = 'http://localhost:8080';

/**
 * Construye un movimiento de saldo de prueba con valores deterministas, modelando
 * el contrato de `MovimientoSaldoResponseDto` de `GET /usuarios/me/saldo`
 * (PHA04TSK17): id, monto entero en centavos (crédito positivo), createdAt
 * ISO8601+offset y transaccionId siempre no nulo (columna NOT NULL en V9).
 *
 * @param overrides campos opcionales a sobreescribir
 * @returns objeto {@link MovimientoSaldoUI} listo para renderizar
 */
function movimientoBase(overrides: Partial<MovimientoSaldoUI> = {}): MovimientoSaldoUI {
  return {
    id: 1,
    monto: 12345,
    createdAt: '2026-08-10T17:00:00-05:00',
    transaccionId: 42,
    ...overrides
  };
}

/**
 * Construye el cuerpo de respuesta de saldo de prueba, modelando literalmente el
 * JSON de `SaldoResponseDto` de `GET /usuarios/me/saldo` (PHA04TSK17).
 *
 * @param overrides campos opcionales a sobreescribir
 * @returns objeto {@link SaldoUI} listo para renderizar
 */
function saldoBase(overrides: Partial<SaldoUI> = {}): SaldoUI {
  return {
    saldoDisponible: 37345,
    movimientos: [movimientoBase()],
    ...overrides
  };
}

/** Respuesta simulada 200 de `GET /usuarios/me/saldo` (objeto saldo). */
function okResponse(saldo: SaldoUI) {
  return { ok: true, status: 200, json: async () => saldo };
}

/**
 * Formatea la fecha con el MISMO formato que usa el componente (Intl.DateTimeFormat
 * locale `es-PE`, timeZone America/Lima). El test usa la función como oráculo para
 * no depender de la salida exacta de la librería ni de la zona horaria del runner.
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

describe('PanelSaldo (PHA04TSK21)', () => {
  const originalApiUrl = process.env.NEXT_PUBLIC_API_URL;

  beforeEach(() => {
    vi.restoreAllMocks();
    process.env.NEXT_PUBLIC_API_URL = BASE;
  });

  afterEach(() => {
    process.env.NEXT_PUBLIC_API_URL = originalApiUrl;
  });

  it('al montar consume GET {base}/usuarios/me/saldo con credentials include y muestra saldo y detalle de movimientos', async () => {
    const createdAt = '2026-08-10T17:00:00-05:00';
    const createdAt2 = '2026-08-09T10:30:00-05:00';
    const saldo = saldoBase({
      saldoDisponible: 37345,
      movimientos: [
        movimientoBase({ id: 1, monto: 12345, createdAt, transaccionId: 42 }),
        movimientoBase({ id: 2, monto: 25000, createdAt: createdAt2, transaccionId: 43 })
      ]
    });
    const fetchMock = vi.fn().mockResolvedValue(okResponse(saldo));
    global.fetch = fetchMock;

    render(<PanelSaldo />);

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(`${BASE}/usuarios/me/saldo`, {
        method: 'GET',
        credentials: 'include'
      });
    });

    // Test previo literal: el panel muestra el saldo disponible (S/ 373.45) ...
    expect(await screen.findByText('S/ 373.45')).toBeInTheDocument();
    // ... y el detalle de movimientos que lo componen (monto, fecha, transacción).
    expect(screen.getByText('S/ 123.45')).toBeInTheDocument();
    expect(screen.getByText('S/ 250.00')).toBeInTheDocument();
    expect(screen.getByText('Transacción #42')).toBeInTheDocument();
    expect(screen.getByText('Transacción #43')).toBeInTheDocument();

    // createdAt → fecha/hora legible en es-PE (oráculo con el mismo formato).
    const fechaEsperada = formatoEsperado(createdAt);
    const fechaEsperada2 = formatoEsperado(createdAt2);
    await waitFor(() => {
      expect(screen.queryAllByText(fechaEsperada)).toHaveLength(1);
      expect(screen.queryAllByText(fechaEsperada2)).toHaveLength(1);
    });
  });

  it('muestra un indicador de carga mientras la petición está en vuelo', async () => {
    // Petición que nunca resuelve: permite asertar el estado de carga sin carrera.
    const fetchMock = vi.fn().mockReturnValue(new Promise(() => {}));
    global.fetch = fetchMock;

    render(<PanelSaldo />);

    expect(screen.getByRole('status')).toHaveAttribute('aria-busy', 'true');
    expect(screen.getByText(/cargando saldo/i)).toBeInTheDocument();
  });

  it('muestra banner de error con el mensaje del backend ante respuesta no-ok (403 sin sesión)', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 403,
      json: async () => ({ mensaje: 'AccesoDenegadoException: No hay sesión válida' })
    });
    global.fetch = fetchMock;

    render(<PanelSaldo />);

    expect(await screen.findByText(/no hay sesión válida/i)).toBeInTheDocument();
    // Sin detalle que renderizar.
    expect(screen.queryByRole('listitem')).not.toBeInTheDocument();
  });

  it('muestra fallback con código HTTP cuando la respuesta no-ok no trae mensaje y mensaje genérico en error de red', async () => {
    const fetchSinJson = vi.fn().mockResolvedValue({ ok: false, status: 500, json: async () => { throw new Error('no json'); } });
    global.fetch = fetchSinJson;

    const { unmount } = render(<PanelSaldo />);

    expect(await screen.findByText(/error al cargar el saldo \(código 500\)/i)).toBeInTheDocument();
    unmount();

    const fetchRed = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));
    global.fetch = fetchRed;

    render(<PanelSaldo />);
    expect(await screen.findByText(/error de red al conectar con el servidor/i)).toBeInTheDocument();
  });

  it('saldo con movimientos vacíos (200 legítimo) muestra el saldo y el estado vacío del detalle', async () => {
    const saldo = saldoBase({ saldoDisponible: 0, movimientos: [] });
    const fetchMock = vi.fn().mockResolvedValue(okResponse(saldo));
    global.fetch = fetchMock;

    render(<PanelSaldo />);

    // Sincronización: esperamos a que el fetch del montaje haya resuelto antes de
    // asertar; el backend puede devolver saldo cacheado con lista vacía (PHA04TSK11).
    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledTimes(1);
    });
    expect(await screen.findByText('S/ 0.00')).toBeInTheDocument();
    expect(
      await screen.findByText((contenido) => contenido.replace(/\s+/g, ' ').includes('Todavía no tienes movimientos de saldo.'))
    ).toBeInTheDocument();
    expect(screen.queryByRole('listitem')).not.toBeInTheDocument();
  });

  it('renderiza el detalle en el orden exacto en que llega del backend (sin reordenar)', async () => {
    const saldo = saldoBase({
      movimientos: [
        movimientoBase({ id: 1, monto: 10000, transaccionId: 40 }),
        movimientoBase({ id: 2, monto: 50000, transaccionId: 41 })
      ]
    });
    global.fetch = vi.fn().mockResolvedValue(okResponse(saldo));

    render(<PanelSaldo />);

    const items = await screen.findAllByRole('listitem');
    expect(items).toHaveLength(2);
    expect(items[0]).toHaveTextContent('S/ 100.00');
    expect(items[1]).toHaveTextContent('S/ 500.00');
  });

  it('respuesta 200 con forma inesperada (no objeto saldo) muestra banner de error en vez de datos fabricados', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => [] });
    global.fetch = fetchMock;

    render(<PanelSaldo />);

    expect(await screen.findByText(/formato esperado/i)).toBeInTheDocument();
    // Nunca un saldo inventado: mentir sobre dinero violaría constitution principio 3.
    expect(screen.queryByText(/S\/ 0\.00/)).not.toBeInTheDocument();
  });
});