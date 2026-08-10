import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import PanelDisputas, { DisputaPanel } from './PanelDisputas';

/**
 * @file PanelDisputas.test.tsx
 * @description Pruebas de componente para PanelDisputas (PHA04TSK19).
 *
 * Cobertura TDD (test previo de tasks.md: "botones de resolución solo visibles para admin"):
 * 1. Test previo literal: con `rol="USUARIO"` el panel se ve (la disputa se renderiza) pero
 *    NO muestra botones de resolución ni el campo de motivo; con `rol="ADMIN"` sí.
 * 2. Motivo obligatorio para AMBAS decisiones: botones deshabilitados con motivo vacío (sin
 *    llamada a fetch) y habilitados al escribir texto no vacío.
 * 3. Resolver a favor del vendedor: PATCH {base}/disputas/{id}/resolver con
 *    `{"decision":"A_FAVOR_VENDEDOR","motivo":"..."}`; el 200 remueve la disputa de la lista
 *    local y muestra el mensaje de éxito.
 * 4. Resolver a favor del comprador: ídem con `A_FAVOR_COMPRADOR`.
 * 5. Manejo de errores HTTP 400/403/409/404: muestra el `mensaje` del backend en el banner.
 */

/** URL base fija del backend para las aserciones de fetch. */
const BASE = 'http://localhost:8080';

/**
 * Construye una disputa de prueba (transacción en estado `disputa`) con valores deterministas.
 *
 * @param id ID de la transacción disputada (el `{id}` de la ruta PATCH, contrato DisputaController)
 * @param overrides campos opcionales a sobreescribir
 * @returns objeto {@link DisputaPanel} listo para renderizar
 */
function disputaBase(id = 42, overrides: Partial<DisputaPanel> = {}): DisputaPanel {
  return {
    id,
    precioSnapshot: 25000, // S/ 250.00 en centavos (constitución, principio 3)
    estado: 'disputa',
    ...overrides
  };
}

describe('PanelDisputas (PHA04TSK19)', () => {
  const originalApiUrl = process.env.NEXT_PUBLIC_API_URL;

  beforeEach(() => {
    vi.restoreAllMocks();
    process.env.NEXT_PUBLIC_API_URL = BASE;
  });

  afterEach(() => {
    process.env.NEXT_PUBLIC_API_URL = originalApiUrl;
  });

  it('test previo: con rol USUARIO el panel se ve pero NO muestra botones de resolución ni campo de motivo', () => {
    const fetchMock = vi.fn();
    global.fetch = fetchMock;

    render(<PanelDisputas disputasIniciales={[disputaBase()]} rol="USUARIO" />);

    // El panel es visible: la disputa se renderiza (ID en font-mono y chip de estado).
    // El h2 separa el texto en nodos ("Transacción en disputa " + span "#42"); getByRole
    // con name computa el nombre accesible completo concatenado.
    expect(
      screen.getByRole('heading', { level: 2, name: /transacción en disputa #42/i })
    ).toBeInTheDocument();
    expect(screen.getByText('disputa')).toBeInTheDocument();

    // Criterio literal del test previo: sin botones de resolución para no-admin.
    expect(screen.queryByRole('button', { name: /liberar fondos/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /reembolsar/i })).not.toBeInTheDocument();
    expect(screen.queryAllByRole('button')).toHaveLength(0);

    // Tampoco el campo de motivo (es parte de los controles de resolución).
    expect(screen.queryByLabelText(/motivo de la resolución/i)).not.toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('admin: los botones de resolución exigen motivo — deshabilitados con motivo vacío y sin llamada a fetch', async () => {
    const fetchMock = vi.fn();
    global.fetch = fetchMock;

    render(<PanelDisputas disputasIniciales={[disputaBase()]} rol="ADMIN" />);

    const liberarBtn = screen.getByRole('button', { name: /liberar fondos/i });
    const reembolsarBtn = screen.getByRole('button', { name: /reembolsar/i });

    expect(liberarBtn).toBeDisabled();
    expect(reembolsarBtn).toBeDisabled();

    // Un clic sobre el botón deshabilitado no puede disparar la petición.
    fireEvent.click(liberarBtn);
    fireEvent.click(reembolsarBtn);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('admin: escribir un motivo no vacío habilita ambos botones de resolución', () => {
    const fetchMock = vi.fn();
    global.fetch = fetchMock;

    render(<PanelDisputas disputasIniciales={[disputaBase()]} rol="ADMIN" />);

    const motivoInput = screen.getByLabelText(/motivo de la resolución/i) as HTMLInputElement;
    expect(motivoInput.value).toBe('');

    fireEvent.change(motivoInput, { target: { value: 'El vendedor entregó en plazo y forma' } });

    expect(motivoInput.value).toBe('El vendedor entregó en plazo y forma');
    expect(screen.getByRole('button', { name: /liberar fondos/i })).toBeEnabled();
    expect(screen.getByRole('button', { name: /reembolsar/i })).toBeEnabled();
  });

  it('admin: resolver a favor del vendedor envía PATCH /disputas/{id}/resolver con A_FAVOR_VENDEDOR, remueve la disputa y muestra éxito', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({}) });
    global.fetch = fetchMock;

    render(<PanelDisputas disputasIniciales={[disputaBase()]} rol="ADMIN" />);

    fireEvent.change(screen.getByLabelText(/motivo de la resolución/i), {
      target: { value: 'El vendedor entregó en plazo y forma' }
    });
    fireEvent.click(screen.getByRole('button', { name: /liberar fondos/i }));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(`${BASE}/disputas/42/resolver`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({
          decision: 'A_FAVOR_VENDEDOR',
          motivo: 'El vendedor entregó en plazo y forma'
        })
      });
    });

    expect(
      await screen.findByText(/disputa resuelta a favor del vendedor/i)
    ).toBeInTheDocument();
    // Tras el 200 la disputa resuelta se remueve de la lista local (patrón PanelModeracion).
    await waitFor(() => {
      expect(
        screen.queryByRole('heading', { level: 2, name: /transacción en disputa #42/i })
      ).not.toBeInTheDocument();
    });
  });

  it('admin: resolver a favor del comprador envía PATCH /disputas/{id}/resolver con A_FAVOR_COMPRADOR, remueve la disputa y muestra éxito', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({}) });
    global.fetch = fetchMock;

    render(<PanelDisputas disputasIniciales={[disputaBase()]} rol="ADMIN" />);

    fireEvent.change(screen.getByLabelText(/motivo de la resolución/i), {
      target: { value: 'El producto nunca llegó y el vendedor no respondió' }
    });
    fireEvent.click(screen.getByRole('button', { name: /reembolsar/i }));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(`${BASE}/disputas/42/resolver`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({
          decision: 'A_FAVOR_COMPRADOR',
          motivo: 'El producto nunca llegó y el vendedor no respondió'
        })
      });
    });

    expect(
      await screen.findByText(/disputa resuelta a favor del comprador/i)
    ).toBeInTheDocument();
    await waitFor(() => {
      expect(
        screen.queryByRole('heading', { level: 2, name: /transacción en disputa #42/i })
      ).not.toBeInTheDocument();
    });
  });

  it.each([
    [
      400,
      'MotivoResolucionDisputaObligatorioException: El motivo de la resolución de disputa es obligatorio'
    ],
    [403, 'AccesoDenegadoException: Se requiere rol ADMIN'],
    [409, 'TransicionEstadoTransaccionInvalidaException: La transacción no se encuentra en disputa'],
    [404, 'TransaccionNoEncontradaException: No existe la transacción con id 42']
  ])(
    'muestra en el banner de error el mensaje del backend ante HTTP %i',
    async (status, mensajeBackend) => {
      const fetchMock = vi.fn().mockResolvedValue({
        ok: false,
        status,
        json: async () => ({ mensaje: mensajeBackend })
      });
      global.fetch = fetchMock;

      render(<PanelDisputas disputasIniciales={[disputaBase()]} rol="ADMIN" />);

      fireEvent.change(screen.getByLabelText(/motivo de la resolución/i), {
        target: { value: 'Motivo de prueba para el caso de error' }
      });
      fireEvent.click(screen.getByRole('button', { name: /liberar fondos/i }));

      // El banner muestra el mensaje literal del backend (p. ej. el fragmento canónico).
      expect(await screen.findByText(new RegExp(mensajeBackend, 'i'))).toBeInTheDocument();
      // La disputa NO se remueve de la lista ante un error.
      expect(
        screen.getByRole('heading', { level: 2, name: /transacción en disputa #42/i })
      ).toBeInTheDocument();
    }
  );
});