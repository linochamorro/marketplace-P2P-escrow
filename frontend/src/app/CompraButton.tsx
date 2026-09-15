'use client';

import { loadStripe, type Stripe, type StripeElement, type StripeElements } from '@stripe/stripe-js';
import Link from 'next/link';
import { useEffect, useRef, useState } from 'react';

/**
 * Props públicas del componente reusable de compra.
 */
export interface CompraButtonProps {
  /** ID entero de la publicación aprobada que se intentará comprar. */
  publicacionId: number;
  /** Callback opcional ejecutado cuando `POST /compras` devuelve un PaymentIntent. */
  onCompraCreada?: (response: CompraResponseDto) => void;
  /** Callback opcional ejecutado cuando Stripe confirma el pago sin devolver error. */
  onPagoConfirmado?: () => void;
}

/**
 * Respuesta real de `POST /compras` usada por el frontend para montar Stripe.js.
 */
interface CompraResponseDto {
  /** Client secret del PaymentIntent creado o reutilizado por el backend. */
  clientSecret: string;
  /** ID público del PaymentIntent asociado a la compra en vuelo. */
  paymentIntentId: string;
}

/**
 * Forma estándar de error enviada por `GlobalExceptionHandler` del backend.
 */
interface BackendErrorResponse {
  /** Mensaje legible de error de negocio o autenticación. */
  mensaje?: string;
}

/**
 * Genera la key de idempotencia estable para una instancia montada del botón.
 *
 * Usa `crypto.randomUUID()` cuando está disponible, según plan.md. El fallback
 * existe solo para entornos de prueba/jsdom sin Web Crypto completo; conserva
 * unicidad práctica por timestamp y aleatoriedad local sin introducir lógica de
 * negocio nueva.
 *
 * @returns UUID o identificador local estable para enviar en `Idempotency-Key`.
 */
function generateIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }

  return `fallback-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

/**
 * Extrae el mensaje del backend o devuelve un fallback explícito por estado HTTP.
 *
 * @param response Respuesta HTTP no exitosa de `POST /compras`.
 * @returns Mensaje visible para el comprador.
 */
async function readBackendError(response: Response): Promise<string> {
  const data = (await response.json().catch(() => ({}))) as BackendErrorResponse;
  if (data.mensaje) return data.mensaje;

  if (response.status === 409) {
    return 'No puedes comprar tu propia publicación.';
  }

  if (response.status === 422) {
    return 'La publicación no tiene stock disponible.';
  }

  return `Error del servidor (${response.status})`;
}

/**
 * Construye la URL de retorno local usada por Stripe cuando un método de pago
 * exige redirección externa.
 *
 * @returns URL absoluta hacia `/publicaciones`, ruta ya existente en frontend.
 */
function buildReturnUrl(): string {
  const origin = typeof window !== 'undefined' ? window.location.origin : '';
  return `${origin}/publicaciones`;
}

/**
 * CompraButton — Flujo reusable de compra con idempotencia frontend y Stripe.js.
 *
 * Alcance PHA03TSK11 + PHA15TSK07: genera una única `Idempotency-Key` al montar
 * la instancia, llama `POST /compras` con credenciales incluidas, muestra errores
 * de auto-compra o stock agotado usando el mensaje del backend y, solo ante
 * respuesta OK, monta un PaymentElement embebido con `@stripe/stripe-js` para
 * confirmar el pago sin salir del sitio (`redirect: 'if_required'`).
 *
 * Estado de pago confirmado (PHA15TSK07, plan.md "Estados visibles del pago
 * (CompraButton)", decisión Lino 2026-08-31): tras `confirmPayment` exitoso el
 * PaymentElement y el botón "Confirmar pago" desaparecen, se muestra el mensaje
 * final aprobado con `role="status"` y un enlace `next/link` a `/compras`. El
 * copy es 100% libre de jerga técnica: sin "webhook", sin "backend" y sin
 * exposición del ID crudo del PaymentIntent.
 *
 * @param props Propiedades mínimas de compra, incluyendo `publicacionId` entero.
 * @returns Elemento JSX con card institucional, botón Comprar, PaymentElement y
 *          estado final de pago confirmado con enlace a Mis compras.
 */
export default function CompraButton({
  publicacionId,
  onCompraCreada,
  onPagoConfirmado
}: CompraButtonProps) {
  const idempotencyKeyRef = useRef<string | null>(null);
  const requestInFlightRef = useRef<boolean>(false);
  const paymentContainerRef = useRef<HTMLDivElement | null>(null);
  const stripeRef = useRef<Stripe | null>(null);
  const elementsRef = useRef<StripeElements | null>(null);

  const [isSubmitting, setIsSubmitting] = useState<boolean>(false);
  const [isStripeLoading, setIsStripeLoading] = useState<boolean>(false);
  const [isConfirming, setIsConfirming] = useState<boolean>(false);
  const [clientSecret, setClientSecret] = useState<string | null>(null);
  /**
   * true tras `confirmPayment` exitoso (PHA15TSK07): oculta el PaymentElement y
   * el botón "Confirmar pago" para mostrar el estado final con enlace a /compras.
   */
  const [pagoConfirmado, setPagoConfirmado] = useState<boolean>(false);
  const [paymentReady, setPaymentReady] = useState<boolean>(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  if (idempotencyKeyRef.current === null) {
    idempotencyKeyRef.current = generateIdempotencyKey();
  }

  /**
   * Monta el PaymentElement cuando existe un `clientSecret` válido.
   */
  useEffect(() => {
    if (!clientSecret || !paymentContainerRef.current) return;

    const currentClientSecret = clientSecret;
    const currentPaymentContainer = paymentContainerRef.current;

    let cancelled = false;
    let mountedPaymentElement: StripeElement | null = null;

    /**
     * Carga Stripe.js, crea Elements con el client secret y monta PaymentElement.
     *
     * @returns Promesa resuelta cuando el montaje finaliza o registra error visible.
     */
    async function mountPaymentElement(): Promise<void> {
      setIsStripeLoading(true);
      setPaymentReady(false);
      setErrorMessage(null);

      const stripe = await loadStripe(process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY || '');
      if (cancelled) return;

      if (!stripe) {
        setErrorMessage('No se pudo inicializar Stripe para confirmar el pago.');
        setIsStripeLoading(false);
        return;
      }

      const elements = stripe.elements({ clientSecret: currentClientSecret });
      const paymentElement = elements.create('payment');
      paymentElement.mount(currentPaymentContainer);
      mountedPaymentElement = paymentElement;

      stripeRef.current = stripe;
      elementsRef.current = elements;
      setPaymentReady(true);
      setIsStripeLoading(false);
    }

    void mountPaymentElement();

    return () => {
      cancelled = true;
      mountedPaymentElement?.unmount();
      mountedPaymentElement = null;
      stripeRef.current = null;
      elementsRef.current = null;
    };
  }, [clientSecret]);

  /**
   * Inicia la compra contra el backend con la key estable de la instancia.
   *
   * Bloquea reentradas de forma síncrona con `requestInFlightRef` para que un
   * doble clic no emita dos requests antes de que React deshabilite el botón.
   *
   * @returns Promesa resuelta cuando termina la preparación del PaymentIntent.
   */
  const handleComprar = async (): Promise<void> => {
    if (requestInFlightRef.current) return;
    requestInFlightRef.current = true;
    setIsSubmitting(true);
    setErrorMessage(null);
    setSuccessMessage(null);

    const baseUrl = process.env.NEXT_PUBLIC_API_URL || '';
    const idempotencyKey = idempotencyKeyRef.current;

    if (idempotencyKey === null) {
      setErrorMessage('No se pudo generar la clave de idempotencia de compra.');
      requestInFlightRef.current = false;
      setIsSubmitting(false);
      return;
    }

    try {
      const response = await fetch(`${baseUrl}/compras`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': idempotencyKey
        },
        credentials: 'include',
        body: JSON.stringify({ publicacionId })
      });

      if (!response.ok) {
        setErrorMessage(await readBackendError(response));
        return;
      }

      const data = (await response.json()) as CompraResponseDto;
      setClientSecret(data.clientSecret);
      onCompraCreada?.(data);
    } catch {
      setErrorMessage('Error de red al preparar la compra.');
    } finally {
      requestInFlightRef.current = false;
      setIsSubmitting(false);
    }
  };

  /**
   * Confirma el pago con Stripe.js y muestra cualquier error devuelto por Stripe.
   *
   * En éxito activa el estado de pago confirmado (`pagoConfirmado`): oculta el
   * formulario de pago, muestra el mensaje final aprobado y conserva el llamado
   * a `onPagoConfirmado` intacto (PHA15TSK07). Ante error, el formulario
   * permanece visible para permitir el reintento del comprador.
   *
   * @returns Promesa resuelta cuando Stripe responde a `confirmPayment`.
   */
  const handleConfirmarPago = async (): Promise<void> => {
    if (!stripeRef.current || !elementsRef.current) {
      setErrorMessage('El formulario de pago aún no está listo.');
      return;
    }

    setIsConfirming(true);
    setErrorMessage(null);
    setSuccessMessage(null);

    try {
      const result = await stripeRef.current.confirmPayment({
        elements: elementsRef.current,
        confirmParams: { return_url: buildReturnUrl() },
        redirect: 'if_required'
      });

      if (result.error) {
        setErrorMessage(result.error.message || 'Stripe no pudo confirmar el pago.');
        return;
      }

      setSuccessMessage('¡Pago recibido! Tu compra quedó registrada. En unos segundos verás su estado en Mis compras.');
      setPagoConfirmado(true);
      onPagoConfirmado?.();
    } catch {
      setErrorMessage('Error al confirmar el pago con Stripe.');
    } finally {
      setIsConfirming(false);
    }
  };

  return (
    <section className="w-full max-w-md bg-white border border-slate-200 rounded-lg p-6 space-y-4">
      <div className="space-y-1">
        <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">Compra segura</p>
        <h2 className="text-xl font-semibold text-[#0F172A] tracking-tight">Escrow EasyMarket</h2>
        <p className="text-sm text-slate-600">
          Paga con seguridad: tus fondos quedan protegidos hasta que confirmes la entrega del producto.
        </p>
      </div>

      {errorMessage && (
        <div className="p-3 bg-[#ffdad6] border border-[#ba1a1a] text-[#93000a] rounded text-sm font-medium" role="alert">
          {errorMessage}
        </div>
      )}

      {successMessage && (
        <div className="p-3 bg-emerald-50 border border-[#10B981] text-emerald-800 rounded text-sm font-medium" role="status">
          {successMessage}
        </div>
      )}

      {!clientSecret && (
        <button
          type="button"
          onClick={() => void handleComprar()}
          disabled={isSubmitting}
          className="w-full py-2.5 px-4 bg-[#0F172A] hover:bg-slate-800 text-white font-medium rounded text-sm transition-colors border border-slate-900 disabled:opacity-50 disabled:cursor-not-allowed"
        >
          {isSubmitting ? 'Preparando pago...' : 'Comprar'}
        </button>
      )}

      {clientSecret && !pagoConfirmado && (
        <div className="space-y-4">
          <div
            ref={paymentContainerRef}
            className="min-h-24 rounded border border-slate-200 bg-slate-50 p-3"
            aria-label="Formulario de pago Stripe"
          />
          <button
            type="button"
            onClick={() => void handleConfirmarPago()}
            disabled={!paymentReady || isStripeLoading || isConfirming}
            className="w-full py-2.5 px-4 bg-[#10B981] hover:bg-emerald-600 text-white font-medium rounded text-sm transition-colors border border-emerald-700 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {isConfirming ? 'Confirmando pago...' : isStripeLoading ? 'Cargando pago...' : 'Confirmar pago'}
          </button>
        </div>
      )}

      {pagoConfirmado && (
        <Link
          href="/compras"
          className="inline-flex w-full items-center justify-center py-2.5 px-4 bg-[#0F172A] hover:bg-slate-800 text-white font-medium rounded text-sm transition-colors border border-slate-900"
        >
          Ver mis compras
        </Link>
      )}
    </section>
  );
}
