'use client';

import React, { useState } from 'react';

/**
 * Rol del actor autenticado que visualiza el panel de disputas.
 *
 * <p>Se recibe por props (mismo patrón de plan.md, sección "Panel de transacción (PHA04TSK18)"):
 * no existe endpoint de identidad propio porque la cookie JWT es httpOnly e ilegible en el
 * frontend. El criterio del test previo de tasks.md exige que los botones de resolución solo
 * sean visibles cuando el rol es {@code ADMIN}; con {@code USUARIO} el panel se renderiza en
 * solo lectura, sin controles de resolución.</p>
 */
export type RolPanelDisputas = 'ADMIN' | 'USUARIO';

/**
 * Datos mínimos de una disputa que el panel necesita para renderizarse.
 *
 * <p>Una disputa ES una transacción en estado {@code disputa} (contrato de
 * {@code DisputaController}: no existe entidad separada, el {@code {id}} de la ruta
 * {@code /disputas/{id}/resolver} es el ID de la transacción disputada). El componente recibe
 * las disputas por props porque NO existe {@code GET /disputas} ni endpoint de listado y
 * agregarlo sería scope creep de una tarea de capa UI (mismo argumento que plan.md:380 para
 * omitir {@code GET /transacciones/{id}}).</p>
 */
export interface DisputaPanel {
  /** ID de la transacción disputada — es el {@code {id}} de la ruta PATCH de resolución */
  id: number;
  /** Snapshot inmutable del precio en centavos (constitución, principio 3: dinero como enteros) */
  precioSnapshot: number;
  /** Código de estado de la transacción; en una disputa válida es {@code 'disputa'} */
  estado: string;
}

/**
 * Props del componente {@link PanelDisputas}.
 */
export interface PanelDisputasProps {
  /** Lista opcional de disputas a mostrar (por defecto usa DISPUTAS_PENDIENTES_MOCK) */
  disputasIniciales?: DisputaPanel[];
  /** Rol del actor autenticado que visualiza el panel: solo {@code ADMIN} ve controles de resolución */
  rol: RolPanelDisputas;
}

/**
 * Datos estáticos temporales para la lista de disputas pendientes de resolución.
 * TODO: reemplazar con un endpoint real de listado de disputas (no existe GET /disputas —
 * verificado contra el backend) cuando se implemente; riesgo declarado en el Artifact
 * PHA04TSK19-L01, mismo patrón que PUBLICACIONES_PENDIENTES_MOCK de PHA02TSK13.
 */
const DISPUTAS_PENDIENTES_MOCK: DisputaPanel[] = [
  {
    id: 101,
    precioSnapshot: 25000, // S/ 250.00 en centavos
    estado: 'disputa'
  },
  {
    id: 102,
    precioSnapshot: 15000, // S/ 150.00 en centavos
    estado: 'disputa'
  }
];

/**
 * Devuelve el mensaje de éxito que se muestra tras resolver una disputa (Story 9, spec.md).
 *
 * @param decision decisión binaria tomada por el admin
 * @returns texto literal del mensaje de éxito del banner
 */
function mensajeExito(decision: 'A_FAVOR_VENDEDOR' | 'A_FAVOR_COMPRADOR'): string {
  return decision === 'A_FAVOR_VENDEDOR'
    ? 'Disputa resuelta a favor del vendedor; fondos liberados y transacción completada'
    : 'Disputa resuelta a favor del comprador; fondos revertidos y transacción cancelada';
}

/**
 * Devuelve las clases Tailwind del chip rectangular de estado de una disputa, siguiendo los
 * tokens de error de DESIGN.md (la disputa es un estado de conflicto, no de éxito).
 *
 * @returns clases Tailwind del chip (fondo, borde y texto)
 */
function chipDisputaClases(): string {
  // Error family: #ba1a1a (DESIGN.md), mismos valores que el chip de 'disputa' de PanelTransaccion.
  return 'bg-[#ffdad6] border-[#ba1a1a] text-[#93000a]';
}

/**
 * Componente PanelDisputas (PHA04TSK19).
 *
 * <p>Panel de disputas de la Capa UI (Story 9 de spec.md): muestra la lista de transacciones
 * en estado {@code disputa} (ID de la transacción en JetBrains Mono, monto en centavos como
 * {@code S/} y chip rectangular de estado con colores de error) y permite al admin resolver
 * cada una de forma binaria estricta contra el contrato real:</p>
 * <ul>
 *   <li>"Liberar fondos (vendedor)" → {@code PATCH /disputas/{id}/resolver} con
 *       {@code decision: "A_FAVOR_VENDEDOR"} (Story 9: fondos liberados y transacción a
 *       {@code completada}).</li>
 *   <li>"Reembolsar al comprador" → ídem con {@code decision: "A_FAVOR_COMPRADOR"} (fondos
 *       revertidos y transacción a {@code cancelada}).</li>
 * </ul>
 *
 * <p>El motivo de la resolución es obligatorio para AMBAS decisiones (Story 9: toda resolución
 * se audita con motivo; el contrato backend rechaza 400 sin motivo): los botones permanecen
 * deshabilitados hasta que el motivo tenga texto no vacío ({@code trim().length > 0}, patrón
 * exacto de la cancelación de PHA04TSK18) y existe además una validación defensiva local. Cuando
 * {@code rol === 'USUARIO'} el panel se renderiza en solo lectura: la lista se ve pero NO se
 * muestran los controles de resolución (criterio literal del test previo de tasks.md). Los
 * errores HTTP 400/403/404/409 muestran el {@code mensaje} del backend en un banner de error;
 * tras cada 200 la disputa resuelta se remueve de la lista local y se muestra el mensaje de
 * éxito.</p>
 *
 * @param props Props del componente {@link PanelDisputasProps}
 * @returns Elemento JSX con el panel de disputas completo
 */
export default function PanelDisputas({ disputasIniciales, rol }: PanelDisputasProps) {
  const [disputas, setDisputas] = useState<DisputaPanel[]>(
    disputasIniciales || DISPUTAS_PENDIENTES_MOCK
  );
  const [motivos, setMotivos] = useState<{ [disputaId: number]: string }>({});
  const [errorMensaje, setErrorMensaje] = useState<string | null>(null);
  const [exitoMensaje, setExitoMensaje] = useState<string | null>(null);
  const [resolviendoId, setResolviendoId] = useState<number | null>(null);

  const esAdmin = rol === 'ADMIN';

  /**
   * Actualiza el motivo de resolución ingresado por el admin para una disputa específica.
   *
   * @param disputaId ID de la transacción disputada
   * @param value texto del motivo
   */
  const handleMotivoChange = (disputaId: number, value: string) => {
    setMotivos((prev) => ({ ...prev, [disputaId]: value }));
  };

  /**
   * Resuelve una disputa a favor del vendedor o del comprador, enviando el PATCH contra el
   * contrato real de {@code DisputaController}. Exige motivo no vacío para ambas decisiones
   * (validación de cliente además del deshabilitado previo) y bloquea el panel de esa disputa
   * mientras la petición está en vuelo.
   *
   * @param disputaId ID de la transacción disputada (el {@code {id}} de la ruta)
   * @param decision decisión binaria: {@code A_FAVOR_VENDEDOR} o {@code A_FAVOR_COMPRADOR}
   */
  const resolverDisputa = async (
    disputaId: number,
    decision: 'A_FAVOR_VENDEDOR' | 'A_FAVOR_COMPRADOR'
  ) => {
    // Guarda de reentrada: un doble clic rápido no dispara dos peticiones simultáneas.
    if (resolviendoId !== null) {
      return;
    }

    setErrorMensaje(null);
    setExitoMensaje(null);

    // Defensa local de Story 9 (los botones ya están deshabilitados cuando no hay motivo).
    const motivoText = (motivos[disputaId] || '').trim();
    if (motivoText.length === 0) {
      setErrorMensaje('El motivo de la resolución es obligatorio');
      return;
    }

    setResolviendoId(disputaId);

    const baseUrl = process.env.NEXT_PUBLIC_API_URL || '';
    const endpoint = `${baseUrl}/disputas/${disputaId}/resolver`;

    try {
      const response = await fetch(endpoint, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ decision, motivo: motivoText })
      });

      if (!response.ok) {
        const errorData = await response.json().catch(() => null);
        setErrorMensaje(
          errorData?.mensaje || `Error al resolver la disputa (código ${response.status})`
        );
      } else {
        // Story 9 + patrón PanelModeracion: tras el 200 la disputa resuelta se remueve de la lista.
        setDisputas((prev) => prev.filter((d) => d.id !== disputaId));
        setMotivos((prev) => {
          const { [disputaId]: _omitido, ...restantes } = prev;
          return restantes;
        });
        setExitoMensaje(mensajeExito(decision));
      }
    } catch (err) {
      setErrorMensaje('Error de red al conectar con el servidor');
    } finally {
      setResolviendoId(null);
    }
  };

  // Criterio del test previo análogo a la cancelación de TSK18: deshabilitado hasta que exista
  // motivo de texto no vacío, y bloqueado mientras otra disputa está en vuelo.
  const resolucionHabilitada = (disputaId: number): boolean =>
    resolviendoId === null && (motivos[disputaId] || '').trim().length > 0;

  return (
    <div className="max-w-4xl mx-auto p-6 space-y-6">
      <div className="border-b border-slate-200 pb-4">
        <h1 className="text-3xl font-bold text-[#0F172A] tracking-tight">Panel de Disputas (Admin)</h1>
        <p className="text-sm text-slate-600 mt-1">
          Resuelve cada disputa liberando fondos al vendedor o reembolsando al comprador, con
          motivo obligatorio que queda registrado en el log append-only.
        </p>
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

      {disputas.length === 0 ? (
        <div className="p-8 text-center bg-white border border-slate-200 rounded-lg text-slate-600">
          No hay disputas pendientes de resolución.
        </div>
      ) : (
        <div className="space-y-6">
          {disputas.map((disputa) => (
            <div key={disputa.id} className="bg-white border border-slate-200 rounded-lg p-6 space-y-4 shadow-none">
              {/* Encabezado: ID de la transacción disputada (font-mono), monto S/ (font-mono), chip rectangular de estado */}
              <div className="flex justify-between items-start border-b border-slate-200 pb-3">
                <div>
                  <h2 className="text-lg font-semibold text-[#0F172A] tracking-tight">
                    Transacción en disputa <span className="font-mono">#{disputa.id}</span>
                  </h2>
                  <p className="text-sm text-slate-600 mt-1">
                    Monto:{' '}
                    <span className="font-mono font-medium text-[#0F172A]">
                      S/ {(disputa.precioSnapshot / 100).toFixed(2)}
                    </span>
                  </p>
                </div>
                <span
                  className={`inline-block px-2.5 py-0.5 text-xs font-semibold uppercase tracking-wider rounded border ${chipDisputaClases()}`}
                >
                  {disputa.estado}
                </span>
              </div>

              {/* Controles de resolución: solo visibles para ADMIN (test previo de tasks.md) */}
              {esAdmin && (
                <div className="space-y-3">
                  <div>
                    <label
                      htmlFor={`motivo-resolucion-${disputa.id}`}
                      className="block text-xs font-semibold uppercase tracking-wider text-slate-700 mb-1"
                    >
                      Motivo de la resolución
                    </label>
                    <input
                      id={`motivo-resolucion-${disputa.id}`}
                      type="text"
                      placeholder="Motivo obligatorio que quedará registrado en el log append-only..."
                      value={motivos[disputa.id] || ''}
                      onChange={(e) => handleMotivoChange(disputa.id, e.target.value)}
                      className="w-full px-3 py-2 bg-white border border-slate-300 rounded text-sm text-[#0F172A] focus:outline-none focus:ring-2 focus:ring-slate-200 focus:border-[#0F172A]"
                    />
                  </div>
                  <div className="flex flex-wrap gap-3 pt-1">
                    {/* Favor vendedor: libera fondos — acción de éxito/commit, Emerald exclusivo (DESIGN.md) */}
                    <button
                      type="button"
                      disabled={!resolucionHabilitada(disputa.id)}
                      onClick={() => resolverDisputa(disputa.id, 'A_FAVOR_VENDEDOR')}
                      className="px-4 py-2 bg-[#10B981] hover:bg-emerald-600 text-white font-medium rounded text-sm border border-emerald-700 transition-colors disabled:opacity-50"
                    >
                      Liberar fondos (vendedor)
                    </button>
                    {/* Favor comprador: revierte fondos y cancela — familia de error (DESIGN.md) */}
                    <button
                      type="button"
                      disabled={!resolucionHabilitada(disputa.id)}
                      onClick={() => resolverDisputa(disputa.id, 'A_FAVOR_COMPRADOR')}
                      className="px-4 py-2 bg-[#ba1a1a] hover:bg-red-800 text-white font-medium rounded text-sm border border-red-900 transition-colors disabled:opacity-50"
                    >
                      Reembolsar al comprador
                    </button>
                  </div>
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}