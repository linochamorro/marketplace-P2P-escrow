import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { act } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import CompraButton from './CompraButton';

/**
 * @file CompraButton.test.tsx
 * @description Pruebas de componente para el flujo reusable de compra (PHA03TSK11)
 * y para los estados visibles del pago aprobados por Lino (PHA15TSK07).
 *
 * Cobertura TDD requerida por tasks.md y Story 5:
 * 1. El doble clic sobre la misma instancia no dispara requests con distinta
 *    `Idempotency-Key`; la key se genera al montar y se reutiliza.
 * 2. Error 409 de auto-compra muestra el mensaje del backend y no carga Stripe.
 * 3. Error 422 por falta de stock muestra el mensaje del backend y no carga Stripe.
 * 4. Compra OK envía `POST /compras` con contrato real, monta PaymentElement y
 *    permite confirmar con `stripe.confirmPayment` usando `redirect: 'if_required'`.
 * 5. El PaymentElement montado se desmonta con `unmount()` durante el cleanup
 *    del componente para no dejar iframes/listeners vivos tras un unmount/remount.
 *
 * Cobertura TDD de PHA15TSK07 (copy 100% libre de jerga técnica, plan.md
 * "Estados visibles del pago (CompraButton)", decisión Lino 2026-08-31):
 * 6. Tras `confirmPayment` exitoso el PaymentElement y el botón "Confirmar pago"
 *    desaparecen (estado de pago confirmado).
 * 7. El mensaje final exacto aprobado es visible con role="status".
 * 8. Un enlace accesible (`next/link`) a `/compras` acompaña al mensaje final.
 * 9. Ningún texto visible menciona "webhook", "backend" ni expone el ID crudo
 *    del PaymentIntent.
 * 10. El texto fijo del panel usa el copy aprobado "Paga con seguridad...".
 */

const stripeMocks = vi.hoisted(() => {
  const mountMock = vi.fn();
  const unmountMock = vi.fn();
  const createMock = vi.fn(() => ({ mount: mountMock, unmount: unmountMock }));
  const elementsMock = vi.fn(() => ({ create: createMock }));
  const confirmPaymentMock = vi.fn(async () => ({}));
  const loadStripeMock = vi.fn(async () => ({
    elements: elementsMock,
    confirmPayment: confirmPaymentMock
  }));

  return { mountMock, unmountMock, createMock, elementsMock, confirmPaymentMock, loadStripeMock };
});

vi.mock('@stripe/stripe-js', () => ({
  loadStripe: stripeMocks.loadStripeMock
}));

describe('CompraButton (PHA03TSK11)', () => {
  const originalApiUrl = process.env.NEXT_PUBLIC_API_URL;
  const originalStripeKey = process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY;
  const originalLocation = window.location;

  beforeEach(() => {
    vi.restoreAllMocks();
    stripeMocks.mountMock.mockClear();
    stripeMocks.unmountMock.mockClear();
    stripeMocks.createMock.mockClear();
    stripeMocks.elementsMock.mockClear();
    stripeMocks.confirmPaymentMock.mockClear();
    stripeMocks.loadStripeMock.mockClear();
    process.env.NEXT_PUBLIC_API_URL = 'http://localhost:8080';
    process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY = 'pk_test_deterministic';
    vi.spyOn(globalThis.crypto, 'randomUUID').mockReturnValue('uuid-compra-1' as `${string}-${string}-${string}-${string}-${string}`);
    Object.defineProperty(window, 'location', {
      configurable: true,
      value: { origin: 'http://localhost:3000' }
    });
  });

  afterEach(() => {
    process.env.NEXT_PUBLIC_API_URL = originalApiUrl;
    process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY = originalStripeKey;
    Object.defineProperty(window, 'location', {
      configurable: true,
      value: originalLocation
    });
  });

  it('doble clic no dispara dos requests con distinta Idempotency-Key y bloquea reentradas mientras la petición está en curso', async () => {
    let resolveFetch!: (value: Response) => void;
    const pendingFetch = new Promise<Response>((resolve) => {
      resolveFetch = resolve;
    });
    const fetchMock = vi.fn().mockReturnValueOnce(pendingFetch);
    global.fetch = fetchMock;

    render(<CompraButton publicacionId={42} />);

    expect(globalThis.crypto.randomUUID).toHaveBeenCalledTimes(1);

    const comprarButton = screen.getByRole('button', { name: /comprar/i });
    fireEvent.click(comprarButton);
    fireEvent.click(comprarButton);

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledTimes(1);
    });

    const idempotencyKeys = fetchMock.mock.calls.map(([, init]) => {
      const headers = (init as RequestInit).headers as Record<string, string>;
      return headers['Idempotency-Key'];
    });
    expect(new Set(idempotencyKeys)).toEqual(new Set(['uuid-compra-1']));
    expect(screen.getByRole('button', { name: /preparando pago/i })).toBeDisabled();

    await act(async () => {
      resolveFetch({
        ok: false,
        status: 409,
        json: async () => ({ mensaje: 'No puedes comprar tu propia publicación' })
      } as Response);
      await pendingFetch;
    });
  });

  it('409 auto-compra muestra mensaje visible y no llama a Stripe', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 409,
      json: async () => ({ mensaje: 'No puedes comprar tu propia publicación' })
    });
    global.fetch = fetchMock;

    render(<CompraButton publicacionId={7} />);
    fireEvent.click(screen.getByRole('button', { name: /comprar/i }));

    expect(await screen.findByText(/no puedes comprar tu propia publicación/i)).toBeInTheDocument();
    expect(stripeMocks.loadStripeMock).not.toHaveBeenCalled();
    expect(stripeMocks.confirmPaymentMock).not.toHaveBeenCalled();
  });

  it('422 sin stock muestra mensaje visible y no llama a Stripe', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 422,
      json: async () => ({ mensaje: 'La publicación no tiene stock disponible' })
    });
    global.fetch = fetchMock;

    render(<CompraButton publicacionId={8} />);
    fireEvent.click(screen.getByRole('button', { name: /comprar/i }));

    expect(await screen.findByText(/no tiene stock disponible/i)).toBeInTheDocument();
    expect(stripeMocks.loadStripeMock).not.toHaveBeenCalled();
    expect(stripeMocks.confirmPaymentMock).not.toHaveBeenCalled();
  });

  it('compra OK envía POST /compras, monta PaymentElement y confirma con clientSecret', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 201,
      json: async () => ({
        clientSecret: 'pi_123_secret_abc',
        paymentIntentId: 'pi_123'
      })
    });
    global.fetch = fetchMock;

    render(<CompraButton publicacionId={99} />);
    fireEvent.click(screen.getByRole('button', { name: /comprar/i }));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/compras', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': 'uuid-compra-1'
        },
        credentials: 'include',
        body: JSON.stringify({ publicacionId: 99 })
      });
    });

    await waitFor(() => {
      expect(stripeMocks.loadStripeMock).toHaveBeenCalledWith('pk_test_deterministic');
      expect(stripeMocks.elementsMock).toHaveBeenCalledWith({ clientSecret: 'pi_123_secret_abc' });
      expect(stripeMocks.createMock).toHaveBeenCalledWith('payment');
      expect(stripeMocks.mountMock).toHaveBeenCalledTimes(1);
    });

    fireEvent.click(screen.getByRole('button', { name: /confirmar pago/i }));

    await waitFor(() => {
      expect(stripeMocks.confirmPaymentMock).toHaveBeenCalledWith({
        elements: expect.any(Object),
        confirmParams: { return_url: 'http://localhost:3000/publicaciones' },
        redirect: 'if_required'
      });
    });
  });

  it('desmonta PaymentElement con unmount al desmontar el componente', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 201,
      json: async () => ({
        clientSecret: 'pi_123_secret_cleanup',
        paymentIntentId: 'pi_123_cleanup'
      })
    });
    global.fetch = fetchMock;

    const { unmount } = render(<CompraButton publicacionId={100} />);
    fireEvent.click(screen.getByRole('button', { name: /comprar/i }));

    await waitFor(() => {
      expect(stripeMocks.mountMock).toHaveBeenCalledTimes(1);
    });

    unmount();

    expect(stripeMocks.unmountMock).toHaveBeenCalledTimes(1);
  });

  describe('estados visibles del pago (PHA15TSK07)', () => {
    /** Mensaje final EXACTO aprobado por Lino (plan.md, decisión 2026-08-31). */
    const MENSAJE_FINAL = '¡Pago recibido! Tu compra quedó registrada. En unos segundos verás su estado en Mis compras.';

    /** Copy fijo EXACTO aprobado por Lino para la intro del panel. */
    const TEXTO_PANEL = 'Paga con seguridad: tus fondos quedan protegidos hasta que confirmes la entrega del producto.';

    /**
     * Recorre el flujo feliz completo: compra OK, montaje del PaymentElement y
     * confirmación de pago exitosa con el shape de éxito real que consume el
     * componente (respuesta sin `error` de `stripe.confirmPayment`).
     */
    async function comprarYConfirmar(): Promise<void> {
      const fetchMock = vi.fn().mockResolvedValue({
        ok: true,
        status: 201,
        json: async () => ({
          clientSecret: 'pi_123_secret_ok',
          paymentIntentId: 'pi_123'
        })
      });
      global.fetch = fetchMock;

      render(<CompraButton publicacionId={55} />);
      fireEvent.click(screen.getByRole('button', { name: /comprar/i }));

      await waitFor(() => {
        expect(stripeMocks.mountMock).toHaveBeenCalledTimes(1);
      });

      const confirmarButton = await screen.findByRole('button', { name: /confirmar pago/i });
      await waitFor(() => {
        expect(confirmarButton).toBeEnabled();
      });
      fireEvent.click(confirmarButton);

      await waitFor(() => {
        expect(stripeMocks.confirmPaymentMock).toHaveBeenCalledTimes(1);
      });
    }

    it('tras confirmación exitosa oculta el formulario de pago y el botón "Confirmar pago"', async () => {
      await comprarYConfirmar();

      await screen.findByRole('status');
      expect(screen.queryByRole('button', { name: /confirmar pago/i })).not.toBeInTheDocument();
      expect(screen.queryByLabelText('Formulario de pago Stripe')).not.toBeInTheDocument();
    });

    it('muestra el mensaje final exacto aprobado con role status', async () => {
      await comprarYConfirmar();

      const estado = await screen.findByRole('status');
      expect(estado).toHaveTextContent(MENSAJE_FINAL);
    });

    it('ofrece un enlace accesible a /compras junto al mensaje final', async () => {
      await comprarYConfirmar();

      const enlace = await screen.findByRole('link', { name: /ver mis compras/i });
      expect(enlace).toHaveAttribute('href', '/compras');
    });

    it('no menciona webhook, backend ni expone el ID crudo del PaymentIntent', async () => {
      await comprarYConfirmar();

      expect(screen.queryByText(/webhook/i)).not.toBeInTheDocument();
      expect(screen.queryByText(/backend/i)).not.toBeInTheDocument();
      expect(screen.queryByText(/PaymentIntent:/i)).not.toBeInTheDocument();
    });

    it('el texto fijo del panel usa el copy aprobado sin jerga técnica', () => {
      render(<CompraButton publicacionId={1} />);

      expect(screen.getByText(TEXTO_PANEL)).toBeInTheDocument();
      expect(screen.queryByText(/backend confirme el evento/i)).not.toBeInTheDocument();
    });
  });
});
