'use client';

import React, { useEffect, useRef, useState } from 'react';
import {
  cargarNotificaciones,
  ErrorApiNotificaciones,
  etiquetaTipo,
  formatearFechaNotificacion,
  type NotificacionUI
} from './notificaciones-utils';

// Re-export para que el consumidor (y el test) pueda importar el modelo completo desde el
// componente, igual que `PanelDisputas` exporta `DisputaPanel` desde su propio archivo.
export type { NotificacionUI };
export { ETIQUETAS_TIPO, etiquetaTipo, formatearFechaNotificacion, ErrorApiNotificaciones, cargarNotificaciones } from './notificaciones-utils';

/**
 * Props del componente {@link PanelCentroNotificaciones}.
 */
export interface PanelCentroNotificacionesProps {
  /**
   * Función de transporte inyectable (recibe la URL y retorna la lista de notificaciones).
   * Por defecto usa {@link cargarNotificaciones} con {@link fetch} global — el patrón de los
   * paneles existentes (PHA02TSK13, PHA04TSK18, PHA04TSK19). Permite aislar la red en tests
   * de integración sin mockear el global.
   */
  obtenerNotificaciones?: (url: string) => Promise<NotificacionUI[]>;
}

/**
 * Componente PanelCentroNotificaciones (PHA04TSK20) — Centro de notificaciones.
 *
 * <p>Panel de la Capa UI que traza a la story 7b de spec.md (avisos diarios de transacción
 * abierta) y cubre también los avisos puntuales de la story 7 (ENVIO_PENDIENTE_48H). Consume
 * únicamente {@code GET {NEXT_PUBLIC_API_URL}/notificaciones} con {@code credentials:
 * 'include'} (cookie httpOnly) — el endpoint ya existe y está cerrado (PHA04TSK16); nada de
 * este componente toca el backend.</p>
 *
 * <p>Decisión de Lino 2026-08-10 (extiende el test previo): como NO existe endpoint de
 * marcar como leída, el panel LISTA TODAS las notificaciones y RESALTA VISUALMENTE las no
 * leídas — badge "No leída" con punto en Emerald {@code #10B981} (secondary de DESIGN.md,
 * reservado a estados de éxito/confirmación) + mensaje en semibold. Las leídas se muestran
 * sin ese resalte.</p>
 *
 * <p>Entrega (plan.md:366): el frontend consulta al cargar la página Y al recuperar el foco
 * de la ventana (evento {@code focus} de {@code window}) — polling manual del usuario, no
 * push automático. El fetch se remonta en el montaje, el listener de foco se registra una
 * sola vez en el primer efecto y se limpia en unmount para no disparar peticiones sobre un
 * componente desmontado.</p>
 *
 * <p>El campo {@code transaccionId} es nullable (V14): si es null no se muestra referencia
 * a transacción; si existe se muestra {@code Transacción #N} en JetBrains Mono (decisión de
 * presentación declarada en el Artifact PHA04TSK20-L01).</p>
 *
 * @param props Props del componente {@link PanelCentroNotificacionesProps}
 * @returns Elemento JSX con el centro de notificaciones completo
 */
export default function PanelCentroNotificaciones({
  obtenerNotificaciones
}: PanelCentroNotificacionesProps) {
  const [notificaciones, setNotificaciones] = useState<NotificacionUI[]>([]);
  const [cargando, setCargando] = useState<boolean>(true);
  const [errorMensaje, setErrorMensaje] = useState<string | null>(null);
  // Distingue "el backend respondió 200 con cero notificaciones" (estado vacío legítimo) de
  // "aún no hay carga exitosa" (tras un error no debe mostrarse "No tienes notificaciones").
  const [cargado, setCargado] = useState<boolean>(false);

  // Ref con la función de carga vigente, para que el listener de `focus` (registrado una
  // sola vez en el primer render) invoque SIEMPRE la última versión sin re-registrarse.
  // Alternativa a `useCallback` puro: evita depender de un `useCallback` en deps y comparte
  // la misma guarda anti-concurrencia con el efecto de montaje.
  const cargarRef = useRef<() => Promise<void>>(async () => {});
  // Evita solapamiento entre el fetch de montaje y un focus inmediato (p. ej. al reabrir la
  // pestaña justo mientras la primera petición está en vuelo).
  const enVueloRef = useRef<boolean>(false);

  useEffect(() => {
    const baseUrl = process.env.NEXT_PUBLIC_API_URL || '';
    const endpoint = `${baseUrl}/notificaciones`;
    const transporte = obtenerNotificaciones || cargarNotificaciones;

    /**
     * Consulta el backend y actualiza el estado del panel. Anti-concurrencia: si otra
     * petición está en vuelo (fetch de montaje o focus previo sin terminar) se ignora la
     * llamada — el polling manual de plan.md:366 no necesita corridas solapadas.
     *
     * @returns promesa que resuelve al terminar (para poder esperarla en tests)
     */
    cargarRef.current = async () => {
      if (enVueloRef.current) {
        return;
      }
      enVueloRef.current = true;
      setCargando(true);
      setErrorMensaje(null);
      try {
        const lista = await transporte(endpoint);
        setNotificaciones(lista);
        setCargado(true);
      } catch (err) {
        if (err instanceof ErrorApiNotificaciones) {
          // Respuesta HTTP no-ok: se muestra el `mensaje` literal del backend (p. ej. 403
          // sin sesión) o el fallback con código HTTP ya construido por la capa de transporte.
          setErrorMensaje(err.message);
        } else {
          // fetch rechazó: no hubo respuesta HTTP (sin red, servidor caído, CORS bloqueado).
          setErrorMensaje('Error de red al conectar con el servidor');
        }
      } finally {
        setCargando(false);
        enVueloRef.current = false;
      }
    };

    // First render: consulta al montar la página (plan.md:366).
    void cargarRef.current();

    // Listener único de foco (plan.md:366): el evento `focus` de window dispara un nuevo
    // fetch cada vez que el usuario vuelve a la pestaña. Se remueve en el cleanup del efecto
    // para no dejar fugas que disparen peticiones con el componente desmontado.
    const handlerFocus = () => {
      void cargarRef.current();
    };
    window.addEventListener('focus', handlerFocus);
    return () => {
      window.removeEventListener('focus', handlerFocus);
    };
  }, [obtenerNotificaciones]);

  return (
    <div className="max-w-4xl mx-auto p-6 space-y-6">
      <div className="border-b border-slate-200 pb-4">
        <h1 className="text-3xl font-bold text-[#0F172A] tracking-tight">Centro de Notificaciones</h1>
        <p className="text-sm text-slate-600 mt-1">
          Avisos sobre tus transacciones abiertas. El listado se refresca al cargar la página y
          cada vez que vuelves a esta ventana.
        </p>
      </div>

      {errorMensaje && (
        <div className="p-3 bg-[#ffdad6] border border-[#ba1a1a] text-[#93000a] rounded text-sm font-medium">
          {errorMensaje}
        </div>
      )}

      {cargando ? (
        <div role="status" aria-busy="true" className="p-8 text-center bg-white border border-slate-200 rounded-lg text-slate-600">
          Cargando notificaciones...
        </div>
      ) : notificaciones.length === 0 && cargado && !errorMensaje ? (
        <div className="p-8 text-center bg-white border border-slate-200 rounded-lg text-slate-600">
          No tienes notificaciones.
        </div>
      ) : (
        <ul className="space-y-3">
          {notificaciones.map((notificacion) => {
            const noLeida = !notificacion.leida;
            return (
              <li
                key={notificacion.id}
                className="bg-white border border-slate-200 rounded-lg p-5 shadow-none space-y-2"
              >
                <div className="flex justify-between items-start gap-3">
                  <div className="space-y-1 min-w-0">
                    <p
                      className={`text-sm text-[#0F172A] ${noLeida ? 'font-semibold' : 'font-normal'}`}
                    >
                      {notificacion.mensaje}
                    </p>
                    <p className="text-xs label-caps font-semibold uppercase tracking-wider text-slate-700">
                      {etiquetaTipo(notificacion.tipo)}
                    </p>
                    <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-slate-500">
                      <span className="font-mono">{formatearFechaNotificacion(notificacion.createdAt)}</span>
                      {notificacion.transaccionId !== null && (
                        <span className="font-mono">Transacción #{notificacion.transaccionId}</span>
                      )}
                    </div>
                  </div>
                  {noLeida && (
                    <span className="shrink-0 inline-flex items-center gap-1.5 px-2.5 py-0.5 text-xs font-semibold rounded border bg-[#d1fae5] border-[#10B981] text-[#065f46]">
                      <span
                        aria-hidden="true"
                        className="inline-block w-2 h-2 rounded-full bg-[#10B981]"
                      />
                      No leída
                    </span>
                  )}
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}