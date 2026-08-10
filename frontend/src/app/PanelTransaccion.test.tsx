import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import PanelTransaccion, { TransaccionPanel } from './PanelTransaccion';

/**
 * @file PanelTransaccion.test.tsx
 * @description Pruebas de componente para PanelTransaccion (PHA04TSK18).
 *
 * Cobertura TDD (test previo de tasks.md: "exige motivo antes de habilitar cancelación"):
 * 1. El botón "Cancelar" está deshabilitado cuando el campo motivo está vacío; se habilita
 *    al escribir texto no vacío; y NO llama a fetch cuando está deshabilitado.
 * 2. Vendedor en `reservada`: ve "Marcar enviado" y "Cancelar"; enviar hace
 *    PATCH {base}/transacciones/{id}/enviar SIN body.
 * 3. Vendedor en `enviado`: ve "Marcar entregado"; entregar con prueba vacía envía cuerpo
 *    sin `descripcionPruebaEntrega` y con texto la incluye.
 * 4. Comprador en `entregado`: ve "Confirmar recepción" y "Reclamar"; confirmar envía
 *    PATCH .../confirmar SIN body; reclamar envía {"motivo": ...} cuando hay texto.
 * 5. Comprador en `enviado`: NO ve confirmar/reclamar ni cancelar.
 * 6. Estados terminales (`recibido`, `cancelada`, `completada`, `recibido_sin_respuesta`)
 *    y estados sin acciones (`entregado` para vendedor, `disputa`): sin botones.
 * 7. Error 409 (transición inválida): muestra el `mensaje` del backend.
 * 8. Éxito de una transición: muestra mensaje de éxito, actualiza el estado local del
 *    componente y llama `onEstadoCambiado`.
 * 9. React binding: texto escrito con `fireEvent.change` refleja el estado del input.
 */

/** URL base fija del backend para las aserciones de fetch. */
const BASE = 'http://localhost:8080';

/**
 * Construye una transacción de prueba con valores por defecto deterministas.
 *
 * @param estado código de estado de la transacción
 * @param overrides campos opcionales a sobreescribir
 * @returns objeto {@link TransaccionPanel} listo para renderizar
 */
function transaccionBase(estado: string, overrides: Partial<TransaccionPanel> = {}): TransaccionPanel {
  return {
    id: 42,
    estado,
    precioSnapshot: 25000,
    ...overrides
  };
}

describe('PanelTransaccion (PHA04TSK18)', () => {
  const originalApiUrl = process.env.NEXT_PUBLIC_API_URL;

  beforeEach(() => {
    vi.restoreAllMocks();
    process.env.NEXT_PUBLIC_API_URL = BASE;
  });

  afterEach(() => {
    process.env.NEXT_PUBLIC_API_URL = originalApiUrl;
  });

  it('exige motivo antes de habilitar cancelación: botón Cancelar deshabilitado con motivo vacío y sin llamada a fetch', async () => {
    const fetchMock = vi.fn();
    global.fetch = fetchMock;

    render(<PanelTransaccion transaccion={transaccionBase('reservada')} rol="VENDEDOR" />);

    const cancelarBtn = screen.getByRole('button', { name: /cancelar/i });
    expect(cancelarBtn).toBeDisabled();

    // Un clic sobre el botón deshabilitado no puede disparar la petición.
    fireEvent.click(cancelarBtn);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('React binding: el texto escrito con fireEvent.change se refleja en el input y habilita el botón Cancelar', () => {
    const fetchMock = vi.fn();
    global.fetch = fetchMock;

    render(<PanelTransaccion transaccion={transaccionBase('reservada')} rol="VENDEDOR" />);

    const motivoInput = screen.getByLabelText(/motivo de cancelaci/i) as HTMLInputElement;
    expect(motivoInput.value).toBe('');

    fireEvent.change(motivoInput, { target: { value: 'Ya no lo necesito' } });

    expect(motivoInput.value).toBe('Ya no lo necesito');
    expect(screen.getByRole('button', { name: /cancelar/i })).toBeEnabled();
  });

  it('cancelar con motivo envía PATCH /transacciones/{id}/cancelar con el motivo, muestra éxito y avisa a onEstadoCambiado', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({}) });
    global.fetch = fetchMock;
    const onEstadoCambiado = vi.fn();

    render(
      <PanelTransaccion
        transaccion={transaccionBase('reservada')}
        rol="VENDEDOR"
        onEstadoCambiado={onEstadoCambiado}
      />
    );

    fireEvent.change(screen.getByLabelText(/motivo de cancelaci/i), { target: { value: 'Ya no lo necesito' } });
    fireEvent.click(screen.getByRole('button', { name: /cancelar/i }));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(`${BASE}/transacciones/42/cancelar`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ motivo: 'Ya no lo necesito' })
      });
    });

    expect(await screen.findByText(/transacción cancelada exitosamente/i)).toBeInTheDocument();
    expect(onEstadoCambiado).toHaveBeenCalledWith('cancelada');
    expect(screen.getByText('cancelada')).toBeInTheDocument();
  });

  it('vendedor en reservada ve "Marcar enviado" y "Cancelar"; enviar hace PATCH /enviar SIN body', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({}) });
    global.fetch = fetchMock;

    render(<PanelTransaccion transaccion={transaccionBase('reservada')} rol="VENDEDOR" />);

    const enviarBtn = screen.getByRole('button', { name: /marcar enviado/i });
    const cancelarBtn = screen.getByRole('button', { name: /cancelar/i });
    expect(enviarBtn).toBeInTheDocument();
    expect(cancelarBtn).toBeInTheDocument();

    fireEvent.click(enviarBtn);

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(`${BASE}/transacciones/42/enviar`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include'
      });
    });

    expect(await screen.findByText(/transacción marcada como enviada/i)).toBeInTheDocument();
    expect(screen.getByText('enviado')).toBeInTheDocument();
  });

  it('vendedor en enviado ve "Marcar entregado" y "Cancelar"; entregar sin prueba envía cuerpo vacío', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({}) });
    global.fetch = fetchMock;

    render(<PanelTransaccion transaccion={transaccionBase('enviado')} rol="VENDEDOR" />);

    const entregarBtn = screen.getByRole('button', { name: /marcar entregado/i });
    const cancelarBtn = screen.getByRole('button', { name: /cancelar/i });
    expect(entregarBtn).toBeInTheDocument();
    expect(cancelarBtn).toBeInTheDocument();

    // El campo de prueba de entrega está vacío por defecto (Story 6b: campo opcional).
    fireEvent.click(entregarBtn);

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(`${BASE}/transacciones/42/entregar`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({})
      });
    });

    expect(await screen.findByText(/transacción marcada como entregada/i)).toBeInTheDocument();
  });

  it('vendedor en enviado: entregar con prueba de entrega incluye descripcionPruebaEntrega en el cuerpo', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({}) });
    global.fetch = fetchMock;

    render(<PanelTransaccion transaccion={transaccionBase('enviado')} rol="VENDEDOR" />);

    fireEvent.change(screen.getByLabelText(/prueba de entrega/i), { target: { value: 'Entregado en puerta, código 8821' } });
    fireEvent.click(screen.getByRole('button', { name: /marcar entregado/i }));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(`${BASE}/transacciones/42/entregar`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ descripcionPruebaEntrega: 'Entregado en puerta, código 8821' })
      });
    });

    expect(await screen.findByText(/transacción marcada como entregada/i)).toBeInTheDocument();
  });

  it('comprador en entregado ve "Confirmar recepción" y "Reclamar"; confirmar hace PATCH /confirmar SIN body', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({}) });
    global.fetch = fetchMock;

    render(<PanelTransaccion transaccion={transaccionBase('entregado')} rol="COMPRADOR" />);

    const confirmarBtn = screen.getByRole('button', { name: /confirmar recepci/i });
    const reclamarBtn = screen.getByRole('button', { name: /^reclamar$/i });
    expect(confirmarBtn).toBeInTheDocument();
    expect(reclamarBtn).toBeInTheDocument();

    fireEvent.click(confirmarBtn);

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(`${BASE}/transacciones/42/confirmar`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include'
      });
    });

    expect(await screen.findByText(/recepción confirmada exitosamente/i)).toBeInTheDocument();
  });

  it('comprador en entregado: reclamar con motivo envía {"motivo": ...}', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({}) });
    global.fetch = fetchMock;

    render(<PanelTransaccion transaccion={transaccionBase('entregado')} rol="COMPRADOR" />);

    fireEvent.change(screen.getByLabelText(/motivo del reclamo/i), { target: { value: 'El producto llegó dañado' } });
    fireEvent.click(screen.getByRole('button', { name: /^reclamar$/i }));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(`${BASE}/transacciones/42/reclamar`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ motivo: 'El producto llegó dañado' })
      });
    });

    expect(await screen.findByText(/reclamo registrado/i)).toBeInTheDocument();
  });

  it('comprador en entregado: reclamar sin motivo envía cuerpo vacío (Story 6d, motivo opcional)', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({}) });
    global.fetch = fetchMock;

    render(<PanelTransaccion transaccion={transaccionBase('entregado')} rol="COMPRADOR" />);

    fireEvent.click(screen.getByRole('button', { name: /^reclamar$/i }));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(`${BASE}/transacciones/42/reclamar`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({})
      });
    });

    expect(await screen.findByText(/reclamo registrado/i)).toBeInTheDocument();
  });

  it('comprador en enviado NO ve confirmar, reclamar ni cancelar', () => {
    const fetchMock = vi.fn();
    global.fetch = fetchMock;

    render(<PanelTransaccion transaccion={transaccionBase('enviado')} rol="COMPRADOR" />);

    expect(screen.queryByRole('button', { name: /confirmar recepci/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^reclamar$/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /cancelar/i })).not.toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('estados sin acciones del panel no muestran botones (terminales, entregado de vendedor y disputa)', () => {
    const fetchMock = vi.fn();
    global.fetch = fetchMock;

    const estadosSinAcciones = ['entregado', 'recibido', 'recibido_sin_respuesta', 'cancelada', 'completada', 'disputa'];

    estadosSinAcciones.forEach((estado) => {
      const { unmount } = render(<PanelTransaccion transaccion={transaccionBase(estado)} rol="VENDEDOR" />);
      expect(screen.queryAllByRole('button')).toHaveLength(0);
      unmount();
    });

    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('manejo de error 409 (transición inválida): muestra el mensaje del backend', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 409,
      json: async () => ({
        mensaje: 'TransicionEstadoTransaccionInvalidaException: Transición no permitida desde entregado'
      })
    });
    global.fetch = fetchMock;

    render(<PanelTransaccion transaccion={transaccionBase('reservada')} rol="VENDEDOR" />);

    fireEvent.click(screen.getByRole('button', { name: /marcar enviado/i }));

    expect(await screen.findByText(/transición no permitida desde entregado/i)).toBeInTheDocument();
  });

  it('éxito de confirmar muestra mensaje, actualiza el estado local del componente y llama onEstadoCambiado', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({}) });
    global.fetch = fetchMock;
    const onEstadoCambiado = vi.fn();

    render(
      <PanelTransaccion
        transaccion={transaccionBase('entregado')}
        rol="COMPRADOR"
        onEstadoCambiado={onEstadoCambiado}
      />
    );

    fireEvent.click(screen.getByRole('button', { name: /confirmar recepci/i }));

    await waitFor(() => {
      expect(onEstadoCambiado).toHaveBeenCalledWith('recibido');
    });

    expect(await screen.findByText(/recepción confirmada exitosamente/i)).toBeInTheDocument();
    // El chip de estado refleja el nuevo estado local inferido del contrato (sin GET).
    expect(screen.getByText('recibido')).toBeInTheDocument();
  });

  it('comprador en reservada puede cancelar (Story 7: reservada cancelable por comprador o vendedor)', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({}) });
    global.fetch = fetchMock;

    render(<PanelTransaccion transaccion={transaccionBase('reservada')} rol="COMPRADOR" />);

    expect(screen.getByRole('button', { name: /cancelar/i })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /marcar enviado/i })).not.toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(/motivo de cancelaci/i), { target: { value: 'Me arrepentí de la compra' } });
    fireEvent.click(screen.getByRole('button', { name: /cancelar/i }));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(`${BASE}/transacciones/42/cancelar`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ motivo: 'Me arrepentí de la compra' })
      });
    });
  });
});
