'use client';

import React, { useEffect, useState } from 'react';
import {
  cargarSaldo,
  ErrorApiSaldo,
  formatearFechaMovimiento,
  formatearMontoSoles,
  type SaldoUI
} from './saldo-utils';

// Re-export para que el consumidor (y el test) pueda importar el modelo completo
// desde el componente, igual que `PanelCentroNotificaciones` (`PanelDisputas`
// exporta `DisputaPanel` desde su propio archivo).
export type { SaldoUI, MovimientoSaldoUI } from './saldo-utils';
export { formatearMontoSoles, formatearFechaMovimiento, ErrorApiSaldo, cargarSaldo } from './saldo-utils';

/**
 * Props del componente {@link PanelSaldo}.
 */
export interface PanelSaldoProps {
  /**
   * Función de transporte inyectable (recibe la URL y retorna el saldo y su detalle).
   * Por defecto usa {@link cargarSaldo} con {@link fetch} global — patrón
   * {@code PanelCentroNotificaciones} (PHA04TSK20), que a su vez sigue a los paneles
   * PHA02TSK13/PHA04TSK18/PHA04TSK19. Permite aislar la red en tests sin mockear el
   * global.
   */
  obtenerSaldo?: (url: string) => Promise<SaldoUI>;
}

/**
 * Componente PanelSaldo (PHA04TSK21) — Panel de saldo del vendedor.
 *
 * <p>Panel de la Capa UI que traza a la story 12 de spec.md (Saldo y liquidación):
 * "Como vendedor, quiero ver mi saldo disponible acumulado por mis ventas, para
 * saber cuánto he recibido a través de la plataforma". El vendedor ve el
 * {@code saldo_disponible} actual y el detalle de movimientos que lo componen.</p>
 *
 * <p>A diferencia de {@code PanelTransaccion} (TSK18) y {@code PanelDisputas}
 * (TSK19) — cuyos datos llegan por props porque NO existía endpoint de consulta —
 * para el saldo SÍ existe {@code GET /usuarios/me/saldo} (PHA04TSK17, cerrado) y su
 * DTO fue creado «tal como lo consume el panel de saldo del vendedor». Por tanto
 * este componente consume el endpoint real con {@link fetch} (patrón
 * {@code PanelCentroNotificaciones}, PHA04TSK20), con transporte inyectable para
 * los tests, y NO recibe los datos por props.</p>
 *
 * <p>Consulta UNA vez al montar. A diferencia del centro de notificaciones
 * (plan.md:366 define polling por {@code focus} de window para ese panel), el plan
 * NO define refetch por foco para el saldo y la fila de PHA04TSK21 no lo exige —
 * agregarlo sería scope creep. Tampoco hay botón de refrescar manual: el plan no lo
 * define y la story no lo pide (decisión declarada en el Artifact PHA04TSK21-L01).</p>
 *
 * <p>La exclusión deliberada de la story 12 se respeta literalmente: NO existe
 * botón ni endpoint de retiro de saldo a dinero real (requeriría Stripe
 * Connect/KYC) — el saldo es demostración de la arquitectura de escrow. Nada de
 * este componente toca el backend fuera de {@code GET /usuarios/me/saldo}.</p>
 *
 * <p>Estados: cargando ({@code role="status"} con {@code aria-busy}), error (banner
 * con el {@code mensaje} del backend o fallback con código HTTP; error de red →
 * mensaje genérico), éxito con saldo y detalle, y detalle vacío legítimo (200 con
 * {@code movimientos: []} — el backend puede devolver saldo cacheado sin
 * movimientos, PHA04TSK11). El campo {@code transaccionId} es siempre no nulo (V9):
 * cada movimiento se muestra con su referencia «Transacción #N» en JetBrains Mono.
 * El detalle se renderiza en el orden exacto en que llega del backend (el contrato
 * NO declara orden).</p>
 *
 * @param props Props del componente {@link PanelSaldoProps}
 * @returns Elemento JSX con el panel de saldo completo
 */
export default function PanelSaldo({ obtenerSaldo }: PanelSaldoProps) {
  const [saldo, setSaldo] = useState<SaldoUI | null>(null);
  const [cargando, setCargando] = useState<boolean>(true);
  const [errorMensaje, setErrorMensaje] = useState<string | null>(null);
  // Distingue "el backend respondió 200 con saldo" (estado de datos legítimo) de
  // "aún no hay carga exitosa" (tras un error no debe mostrarse el panel de saldo).
  const [cargado, setCargado] = useState<boolean>(false);

  useEffect(() => {
    const baseUrl = process.env.NEXT_PUBLIC_API_URL || '';
    const endpoint = `${baseUrl}/usuarios/me/saldo`;
    const transporte = obtenerSaldo || cargarSaldo;

    // Bandera de cancelación: con una sola consulta al montar (sin refetch por
    // focus ni botón de refrescar) no hay concurrencia que deduplicar como en TSK20
    // (enVueloRef/cargarRef); la bandera evita setState sobre un componente
    // desmontado si la petición resuelve tras el unmount.
    let activo = true;

    /**
     * Consulta el backend y actualiza el estado del panel. Si la petición resuelve
     * tras el desmontaje del componente, los setState se descartan (bandera activo).
     *
     * @returns promesa que resuelve al terminar (para poder esperarla en tests)
     */
    const consultar = async (): Promise<void> => {
      setCargando(true);
      setErrorMensaje(null);
      try {
        const datos = await transporte(endpoint);
        if (activo) {
          setSaldo(datos);
          setCargado(true);
        }
      } catch (err) {
        if (activo) {
          if (err instanceof ErrorApiSaldo) {
            // Respuesta HTTP no-ok o forma de cuerpo inválida: se muestra el
            // `mensaje` literal del backend o el error de formato ya construido por
            // la capa de transporte.
            setErrorMensaje(err.message);
          } else {
            // fetch rechazó: no hubo respuesta HTTP (sin red, servidor caído, CORS
            // bloqueado).
            setErrorMensaje('Error de red al conectar con el servidor');
          }
        }
      } finally {
        if (activo) {
          setCargando(false);
        }
      }
    };

    void consultar();

    return () => {
      activo = false;
    };
  }, [obtenerSaldo]);

  return (
    <div className="max-w-4xl mx-auto p-6 space-y-6">
      <div className="border-b border-slate-200 pb-4">
        <h1 className="text-3xl font-bold text-[#0F172A] tracking-tight">Panel de Saldo</h1>
        <p className="text-sm text-slate-600 mt-1">
          Tu saldo disponible acumulado por tus ventas y el detalle de movimientos que lo componen.
        </p>
      </div>

      {errorMensaje && (
        <div className="p-3 bg-[#ffdad6] border border-[#ba1a1a] text-[#93000a] rounded text-sm font-medium">
          {errorMensaje}
        </div>
      )}

      {cargando ? (
        <div role="status" aria-busy="true" className="p-8 text-center bg-white border border-slate-200 rounded-lg text-slate-600">
          Cargando saldo...
        </div>
      ) : saldo !== null && cargado && !errorMensaje ? (
        <>
          {/* Tarjeta de saldo: valor cacheado en centavos, presentado en soles (S/) */}
          <div className="bg-white border border-slate-200 rounded-lg p-6 shadow-none space-y-2">
            <p className="text-xs label-caps font-semibold uppercase tracking-wider text-slate-700">
              Saldo disponible
            </p>
            <p className="font-mono text-3xl font-semibold text-[#0F172A] tracking-tight">
              {formatearMontoSoles(saldo.saldoDisponible)}
            </p>
          </div>

          {/* Detalle de movimientos append-only que componen el saldo (story 12) */}
          <div className="space-y-3">
            <h2 className="text-lg font-semibold text-[#0F172A] tracking-tight">Movimientos</h2>
            {saldo.movimientos.length === 0 ? (
              <div className="p-8 text-center bg-white border border-slate-200 rounded-lg text-slate-600">
                Todavía no tienes movimientos de saldo.
              </div>
            ) : (
              <ul className="space-y-3">
                {saldo.movimientos.map((movimiento) => (
                  <li
                    key={movimiento.id}
                    className="bg-white border border-slate-200 rounded-lg p-5 shadow-none space-y-2"
                  >
                    <div className="flex justify-between items-start gap-3">
                      <div className="space-y-1 min-w-0">
                        <p className="font-mono text-lg font-medium text-[#0F172A]">
                          {formatearMontoSoles(movimiento.monto)}
                        </p>
                        <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-slate-500">
                          <span className="font-mono">{formatearFechaMovimiento(movimiento.createdAt)}</span>
                          {/* transaccionId siempre no nulo (V9): cada movimiento tiene
                              su transacción de origen */}
                          <span className="font-mono">Transacción #{movimiento.transaccionId}</span>
                        </div>
                      </div>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </>
      ) : null}
    </div>
  );
}