'use client';

import React, { useState } from 'react';

/**
 * Rol del actor autenticado que visualiza el panel de transacción.
 *
 * <p>Se recibe por props (decisión de plan.md, sección "Panel de transacción (PHA04TSK18)"):
 * no existe endpoint de identidad propio porque la cookie JWT es httpOnly e ilegible en
 * el frontend.</p>
 */
export type RolPanelTransaccion = 'COMPRADOR' | 'VENDEDOR';

/**
 * Datos mínimos de una transacción que el panel necesita para renderizarse.
 *
 * <p>El componente recibe la transacción completa por props (plan.md, sección "Panel de
 * transacción (PHA04TSK18)") — NO existe {@code GET /transacciones/{id}} ni endpoint de
 * identidad, y agregarlos sería scope creep de una tarea de capa UI.</p>
 */
export interface TransaccionPanel {
  /** ID numérico de la transacción, mostrado en JetBrains Mono (DESIGN.md) */
  id: number;
  /** Código de estado: reservada | enviado | entregado | recibido | recibido_sin_respuesta | disputa | cancelada | completada */
  estado: string;
  /** Snapshot inmutable del precio en centavos (constitución, principio 3: dinero como enteros) */
  precioSnapshot: number;
  /** Fecha de creación de la reserva (opcional, solo informativa) */
  fechaReservada?: string;
  /** Fecha en que el vendedor marcó el envío (opcional, solo informativa) */
  fechaEnviado?: string;
  /** Fecha en que el vendedor marcó la entrega (opcional, solo informativa) */
  fechaEntregado?: string;
}

/**
 * Props del componente {@link PanelTransaccion}.
 */
export interface PanelTransaccionProps {
  /** Transacción a mostrar, recibida por props */
  transaccion: TransaccionPanel;
  /** Rol del actor autenticado que opera el panel */
  rol: RolPanelTransaccion;
  /**
   * Callback opcional invocado con el nuevo estado tras una transición exitosa, para que la
   * página contenedora pueda refrescar su copia local (el proyecto no expone
   * {@code GET /transacciones/{id}}).
   */
  onEstadoCambiado?: (nuevoEstado: string) => void;
}

/** Acciones de transición que el panel puede disparar sobre el contrato PATCH de PHA04 */
type AccionPanel = 'enviar' | 'entregar' | 'confirmar' | 'reclamar' | 'cancelar';

/**
 * Mapeo acción → estado resultante, inferido del contrato HTTP verificado en el backend
 * (no existe GET que devuelva el estado tras la transición; el response de cada PATCH es
 * 200 sin body).
 */
const ESTADO_TRAS_ACCION: Record<AccionPanel, string> = {
  enviar: 'enviado',
  entregar: 'entregado',
  confirmar: 'recibido',
  reclamar: 'disputa',
  cancelar: 'cancelada'
};

/**
 * Devuelve el mensaje de éxito que se muestra tras una transición HTTP 200.
 *
 * @param accion acción completada exitosamente
 * @returns texto literal del mensaje de éxito del banner
 */
function mensajeExito(accion: AccionPanel): string {
  switch (accion) {
    case 'enviar':
      return 'Transacción marcada como enviada';
    case 'entregar':
      return 'Transacción marcada como entregada';
    case 'confirmar':
      return 'Recepción confirmada exitosamente';
    case 'reclamar':
      return 'Reclamo registrado; la transacción pasó a disputa';
    case 'cancelar':
      return 'Transacción cancelada exitosamente';
  }
}

/**
 * Devuelve las clases Tailwind del chip de estado según el código, siguiendo los tokens de
 * DESIGN.md: chips rectangulares (4px), emerald solo para estados de éxito/confirmación,
 * tinte de error para disputa.
 *
 * @param estado código de estado de la transacción
 * @returns clases Tailwind del chip (fondo, borde y texto)
 */
function chipClases(estado: string): string {
  switch (estado) {
    case 'recibido':
    case 'recibido_sin_respuesta':
    case 'completada':
      // Success family: Secure Emerald #10B981 (reservado para éxito/confirmación, DESIGN.md)
      return 'bg-[#d1fae5] border-[#10B981] text-[#065f46]';
    case 'disputa':
      // Error family: #ba1a1a
      return 'bg-[#ffdad6] border-[#ba1a1a] text-[#93000a]';
    case 'cancelada':
      return 'bg-slate-200 border-slate-400 text-slate-700';
    default:
      return 'bg-slate-100 border-slate-300 text-slate-800';
  }
}

/**
 * Componente PanelTransaccion (PHA04TSK18).
 *
 * <p>Panel de transacción de la Capa UI (Stories 6a-6d y 7 de spec.md): muestra la info
 * básica de la transacción (ID y monto en JetBrains Mono, chip rectangular de estado) y,
 * según rol × estado, las acciones de negocio:</p>
 * <ul>
 *   <li>Vendedor en {@code reservada}: "Marcar enviado" ({@code PATCH /enviar}, sin body) y
 *       "Cancelar" con motivo obligatorio (Story 7).</li>
 *   <li>Vendedor en {@code enviado}: "Marcar entregado" con prueba de entrega opcional
 *       ({@code PATCH /entregar}) y "Cancelar" con motivo obligatorio (Stories 6b y 7).</li>
 *   <li>Comprador en {@code entregado}: "Confirmar recepción" ({@code PATCH /confirmar}, sin
 *       body) y "Reclamar" con motivo opcional ({@code PATCH /reclamar}, Stories 6c y 6d).</li>
 *   <li>Comprador en {@code reservada}: "Cancelar" con motivo obligatorio (Story 7).</li>
 * </ul>
 *
 * <p>El botón "Cancelar" permanece deshabilitado hasta que el motivo de cancelación tenga
 * texto no vacío (criterio literal del test previo de tasks.md). Estados sin acciones
 * (terminales, {@code entregado} para vendedor y {@code disputa}) renderizan solo la info.
 * Los errores HTTP 400/403/409/404 muestran {@code mensaje} del backend en un banner de
 * error; tras cada 200 el estado local se actualiza con la transición inferida del contrato
 * y se invoca {@code onEstadoCambiado} si existe.</p>
 *
 * @param props Props del componente {@link PanelTransaccionProps}
 * @returns Elemento JSX con el panel de transacción completo
 */
export default function PanelTransaccion({ transaccion, rol, onEstadoCambiado }: PanelTransaccionProps) {
  const [estadoActual, setEstadoActual] = useState<string>(transaccion.estado);
  const [motivoCancelacion, setMotivoCancelacion] = useState<string>('');
  const [pruebaEntrega, setPruebaEntrega] = useState<string>('');
  const [motivoReclamo, setMotivoReclamo] = useState<string>('');
  const [errorMensaje, setErrorMensaje] = useState<string | null>(null);
  const [exitoMensaje, setExitoMensaje] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState<boolean>(false);

  const esVendedor = rol === 'VENDEDOR';
  const esComprador = rol === 'COMPRADOR';

  // Autorización de cancelación (CancelacionTransaccionService, Story 7): reservada por
  // comprador o vendedor; enviado solo por vendedor.
  const puedeCancelar = estadoActual === 'reservada' || (estadoActual === 'enviado' && esVendedor);

  const verEnviar = esVendedor && estadoActual === 'reservada';
  const verEntregar = esVendedor && estadoActual === 'enviado';
  const verConfirmar = esComprador && estadoActual === 'entregado';
  const verReclamar = esComprador && estadoActual === 'entregado';

  // Criterio del test previo: deshabilitado hasta que exista motivo de texto no vacío.
  const cancelacionHabilitada = !isSubmitting && motivoCancelacion.trim().length > 0;

  /**
   * Ejecuta una acción de transición contra el contrato PATCH de PHA04, mostrando el
   * resultado en los banners y bloqueando el panel mientras la petición está en vuelo.
   *
   * @param accion acción a ejecutar: enviar | entregar | confirmar | reclamar | cancelar
   */
  const ejecutarAccion = async (accion: AccionPanel) => {
    // Guarda de reentrada: un doble clic rápido no dispara dos peticiones simultáneas.
    if (isSubmitting) {
      return;
    }

    setErrorMensaje(null);
    setExitoMensaje(null);

    // Defensa local de Story 7 (el botón ya está deshabilitado cuando no hay motivo).
    if (accion === 'cancelar' && motivoCancelacion.trim().length === 0) {
      setErrorMensaje('El motivo de cancelación es obligatorio');
      return;
    }

    setIsSubmitting(true);

    const baseUrl = process.env.NEXT_PUBLIC_API_URL || '';
    const endpoint = `${baseUrl}/transacciones/${transaccion.id}/${accion}`;

    let body: string | undefined;
    if (accion === 'entregar') {
      // Story 6b: prueba de entrega opcional; cuerpo vacío delega null al servicio.
      const texto = pruebaEntrega.trim();
      body = JSON.stringify(texto ? { descripcionPruebaEntrega: texto } : {});
    } else if (accion === 'reclamar') {
      // Story 6d: motivo de texto libre NO obligatorio; cuerpo vacío delega null.
      const texto = motivoReclamo.trim();
      body = JSON.stringify(texto ? { motivo: texto } : {});
    } else if (accion === 'cancelar') {
      // Story 7: motivo obligatorio (validado además en el cliente y en el dominio).
      body = JSON.stringify({ motivo: motivoCancelacion.trim() });
    }
    // enviar y confirmar: sin body (contrato verificado en TransaccionEnvioEntregaController).

    const opciones: RequestInit = {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'include'
    };
    if (body !== undefined) {
      opciones.body = body;
    }

    try {
      const response = await fetch(endpoint, opciones);

      if (!response.ok) {
        const errorData = await response.json().catch(() => null);
        setErrorMensaje(errorData?.mensaje || `Error al ejecutar la acción (código ${response.status})`);
      } else {
        const nuevoEstado = ESTADO_TRAS_ACCION[accion];
        setEstadoActual(nuevoEstado);
        setExitoMensaje(mensajeExito(accion));
        if (onEstadoCambiado) {
          onEstadoCambiado(nuevoEstado);
        }
      }
    } catch (err) {
      setErrorMensaje('Error de red al conectar con el servidor');
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="bg-white border border-slate-200 rounded-lg p-6 shadow-none space-y-4">
      {/* Encabezado: ID (font-mono), monto (font-mono), chip rectangular de estado */}
      <div className="flex justify-between items-start border-b border-slate-200 pb-3">
        <div>
          <h2 className="text-lg font-semibold text-[#0F172A] tracking-tight">
            Transacción <span className="font-mono">#{transaccion.id}</span>
          </h2>
          <p className="text-sm text-slate-600 mt-1">
            Monto:{' '}
            <span className="font-mono font-medium text-[#0F172A]">
              S/ {(transaccion.precioSnapshot / 100).toFixed(2)}
            </span>
          </p>
          {(transaccion.fechaReservada || transaccion.fechaEnviado || transaccion.fechaEntregado) && (
            <div className="text-xs text-slate-500 mt-1 space-y-0.5">
              {transaccion.fechaReservada && (
                <p className="font-mono">Reservada: {transaccion.fechaReservada}</p>
              )}
              {transaccion.fechaEnviado && <p className="font-mono">Enviado: {transaccion.fechaEnviado}</p>}
              {transaccion.fechaEntregado && (
                <p className="font-mono">Entregado: {transaccion.fechaEntregado}</p>
              )}
            </div>
          )}
        </div>
        <span
          className={`inline-block px-2.5 py-0.5 text-xs font-semibold uppercase tracking-wider rounded border ${chipClases(
            estadoActual
          )}`}
        >
          {estadoActual}
        </span>
      </div>

      {errorMensaje && (
        <div className="p-3 bg-[#ffdad6] border border-[#ba1a1a] text-[#93000a] rounded text-sm font-medium">
          {errorMensaje}
        </div>
      )}

      {exitoMensaje && (
        <div className="p-3 bg-[#d1fae5] border border-[#10B981] text-[#065f46] rounded text-sm font-medium">
          {exitoMensaje}
        </div>
      )}

      {/* Acciones del vendedor (Stories 6a y 6b) */}
      {(verEnviar || verEntregar) && (
        <div className="space-y-3">
          <div className="flex flex-wrap gap-3">
            {verEnviar && (
              <button
                type="button"
                disabled={isSubmitting}
                onClick={() => ejecutarAccion('enviar')}
                className="px-4 py-2 bg-[#0F172A] hover:bg-slate-800 text-white font-medium rounded text-sm border border-slate-900 transition-colors disabled:opacity-50"
              >
                Marcar enviado
              </button>
            )}
            {verEntregar && (
              <button
                type="button"
                disabled={isSubmitting}
                onClick={() => ejecutarAccion('entregar')}
                className="px-4 py-2 bg-[#0F172A] hover:bg-slate-800 text-white font-medium rounded text-sm border border-slate-900 transition-colors disabled:opacity-50"
              >
                Marcar entregado
              </button>
            )}
          </div>
          {verEntregar && (
            <div>
              <label
                htmlFor={`prueba-entrega-${transaccion.id}`}
                className="block text-xs font-semibold uppercase tracking-wider text-slate-700 mb-1"
              >
                Prueba de entrega (opcional)
              </label>
              <input
                id={`prueba-entrega-${transaccion.id}`}
                type="text"
                placeholder="Descripción textual de la entrega..."
                value={pruebaEntrega}
                onChange={(e) => setPruebaEntrega(e.target.value)}
                className="w-full px-3 py-2 bg-white border border-slate-300 rounded text-sm text-[#0F172A] focus:outline-none focus:ring-2 focus:ring-slate-200 focus:border-[#0F172A]"
              />
            </div>
          )}
        </div>
      )}

      {/* Acciones del comprador (Stories 6c y 6d) */}
      {(verConfirmar || verReclamar) && (
        <div className="space-y-3">
          <div className="flex flex-wrap gap-3">
            <button
              type="button"
              disabled={isSubmitting}
              onClick={() => ejecutarAccion('confirmar')}
              className="px-4 py-2 bg-[#10B981] hover:bg-emerald-600 text-white font-medium rounded text-sm border border-emerald-700 transition-colors disabled:opacity-50"
            >
              Confirmar recepción
            </button>
            <button
              type="button"
              disabled={isSubmitting}
              onClick={() => ejecutarAccion('reclamar')}
              className="px-4 py-2 bg-[#ba1a1a] hover:bg-red-800 text-white font-medium rounded text-sm border border-red-900 transition-colors disabled:opacity-50"
            >
              Reclamar
            </button>
          </div>
          {verReclamar && (
            <div>
              <label
                htmlFor={`motivo-reclamo-${transaccion.id}`}
                className="block text-xs font-semibold uppercase tracking-wider text-slate-700 mb-1"
              >
                Motivo del reclamo (opcional)
              </label>
              <input
                id={`motivo-reclamo-${transaccion.id}`}
                type="text"
                placeholder="Describe el problema con tu compra..."
                value={motivoReclamo}
                onChange={(e) => setMotivoReclamo(e.target.value)}
                className="w-full px-3 py-2 bg-white border border-slate-300 rounded text-sm text-[#0F172A] focus:outline-none focus:ring-2 focus:ring-slate-200 focus:border-[#0F172A]"
              />
            </div>
          )}
        </div>
      )}

      {/* Cancelación (Story 7): motivo obligatorio; botón deshabilitado hasta tener texto */}
      {puedeCancelar && (
        <div className="border-t border-slate-200 pt-4 space-y-3">
          <div>
            <label
              htmlFor={`motivo-cancelacion-${transaccion.id}`}
              className="block text-xs font-semibold uppercase tracking-wider text-slate-700 mb-1"
            >
              Motivo de cancelación
            </label>
            <input
              id={`motivo-cancelacion-${transaccion.id}`}
              type="text"
              placeholder="Motivo obligatorio de la cancelación..."
              value={motivoCancelacion}
              onChange={(e) => setMotivoCancelacion(e.target.value)}
              className="w-full px-3 py-2 bg-white border border-slate-300 rounded text-sm text-[#0F172A] focus:outline-none focus:ring-2 focus:ring-slate-200 focus:border-[#0F172A]"
            />
          </div>
          <button
            type="button"
            disabled={!cancelacionHabilitada}
            onClick={() => ejecutarAccion('cancelar')}
            className="px-4 py-2 bg-[#ba1a1a] hover:bg-red-800 text-white font-medium rounded text-sm border border-red-900 transition-colors disabled:opacity-50"
          >
            Cancelar
          </button>
        </div>
      )}
    </div>
  );
}