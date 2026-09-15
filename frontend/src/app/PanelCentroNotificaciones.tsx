'use client';

import React, { useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import {
  categoriaNotificacion,
  cargarNotificaciones,
  ErrorApiNotificaciones,
  etiquetaTipo,
  EVENTO_NOTIFICACION_LEIDA,
  formatearFechaNotificacion,
  marcarComoLeida,
  rutaDestino,
  type NotificacionUI
} from './notificaciones-utils';

// Re-export para que el consumidor (y el test) pueda importar el modelo completo desde el
// componente, igual que `PanelDisputas` exporta `DisputaPanel` desde su propio archivo.
export type { NotificacionUI };
export {
  ETIQUETAS_TIPO,
  categoriaNotificacion,
  etiquetaTipo,
  formatearFechaNotificacion,
  ErrorApiNotificaciones,
  cargarNotificaciones,
  marcarComoLeida,
  rutaDestino
} from './notificaciones-utils';

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
 * Componente PanelCentroNotificaciones (PHA04TSK20; rediseño accionable de PHA09TSK05
 * recuperado en PHA12TSK05 tras el incidente `git checkout -- .` del 2026-08-23).
 *
 * <p>Panel de la Capa UI que traza a la story 7b de spec.md (avisos diarios de transacción
 * abierta) y cubre también los avisos puntuales de la story 7 (ENVIO_PENDIENTE_48H).
 * Consume {@code GET {NEXT_PUBLIC_API_URL}/notificaciones} con {@code credentials: 'include'}
 * (cookie httpOnly); desde PHA12TSK04 ese endpoint devuelve SOLO las notificaciones
 * accionables del rol del JWT (2 tipos ADMIN o 7 tipos USER).</p>
 *
 * <p>Rediseño accionable (criterio original de PHA09TSK05, decisiones 4-7 de su Artifact
 * L01): cada notificación CON destino determinable por {@link rutaDestino} se renderiza como
 * {@code div[role="link"]} navegable — activarla con click o teclado (Enter/Espacio) ejecuta
 * {@code await PATCH /notificaciones/{id}/leer}, actualiza el estado local (retiro del badge
 * "No leída" SIN refetch) y solo entonces navega con {@code router.push(destino)}. El orden
 * PATCH→push es deliberado (decisión 4): se usa {@code div[role="link"]} con handlers y NO
 * {@code <Link>} porque el {@code <Link>} navegaba inmediatamente sin esperar el PATCH.</p>
 *
 * <p>Cada fila sin leer ofrece además el botón "Marcar como leída", que hace
 * {@code event.stopPropagation()} (decisión 5: evita que el click burbujee al contenedor
 * navegable) y ejecuta únicamente el PATCH con actualización local, sin navegación. Tras un
 * PATCH exitoso — por fila navegable o por botón — el panel emite una sola vez el evento de
 * ventana {@code easymarket:notificacion-leida} (contracto compartido en
 * {@code EVENTO_NOTIFICACION_LEIDA}) para que la campana del {@code Shell} refresque su
 * contador de no leídas (PHA15TSK06); ante un PATCH fallido el evento NO se emite.</p>
 *
 * <p>Identificación visual por categoría (PHA15TSK06): cada fila renderiza una etiqueta
 * discreta con la categoría legible derivada del tipo estable mediante
 * {@link categoriaNotificacion} — compra nueva, estado de compra/envío, recordatorio periódico,
 * moderación, disputa o advertencia, con fallback "Otro" para tipos desconocidos. El estilo es
 * {@code label-caps} con borde y fondo slate de DESIGN.md (status badges de 4px, no pill),
 * coherente con la estética data-centric, y no altera la navegación ni el botón existentes.</p>
 *
 * <p>Errores del PATCH (403/404/red): se reportan en el MISMO banner de error que ya usa la
 * carga inicial ({@code errorMensaje}), con el {@code mensaje} literal del backend cuando lo
 * trae — coherente con la casa: un solo mecanismo de feedback de errores HTTP en el panel.
 * Ante fallo del PATCH no se actualiza el estado local ni se navega. Un segundo click sobre
 * una fila con PATCH aún en vuelo se ignora (guarda anti-doble-activación declarada en el
 * Artifact PHA12TSK05-L01).</p>
 *
 * <p>Entrega (plan.md:366): el frontend consulta al cargar la página Y al recuperar el foco
 * de la ventana (evento {@code focus} de {@code window}) — polling manual del usuario, no
 * push automático. El fetch se remonta en el montaje, el listener de foco se registra una
 * sola vez en el primer efecto y se limpia en unmount para no disparar peticiones sobre un
 * componente desmontado.</p>
 *
 * <p>El campo {@code transaccionId} es nullable (V14): si es null no se muestra referencia
 * a transacción; si existe se muestra {@code Transacción #N} en JetBrains Mono. Las filas
 * sin destino determinable (tipo desconocido, mensaje sin "tu compra"/"tu venta") se
 * muestran como texto plano, nunca como links rotos (decisión defensiva declarada).</p>
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

  // Router del App Router: SOLO se invoca tras un PATCH exitoso (decisión 4 de
  // PHA09TSK05-L01 — el PATCH va antes que la navegación).
  const router = useRouter();

  // Ref con la función de carga vigente, para que el listener de `focus` (registrado una
  // sola vez en el primer render) invoque SIEMPRE la última versión sin re-registrarse.
  // Alternativa a `useCallback` puro: evita depender de un `useCallback` en deps y comparte
  // la misma guarda anti-concurrencia con el efecto de montaje.
  const cargarRef = useRef<() => Promise<void>>(async () => {});
  // Evita solapamiento entre el fetch de montaje y un focus inmediato (p. ej. al reabrir la
  // pestaña justo mientras la primera petición está en vuelo).
  const enVueloRef = useRef<boolean>(false);
  // IDs con un PATCH de marcado como leída actualmente en vuelo: ignora dobles clicks o
  // dobles activaciones de teclado sobre la misma fila hasta resolver la primera.
  const marcadoEnVueloRef = useRef<Set<number>>(new Set());

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

  /**
   * Ejecuta el PATCH de marcado como leída y, solo si fue exitoso, actualiza el estado
   * local de ESA fila (retiro del badge "No leída" sin refetch). Comparte el flujo entre el
   * click en la fila (que además navega) y el botón "Marcar como leída" (que no navega).
   *
   * @param notificación fila objetivo del PATCH
   * @returns {@code true} si el PATCH fue 200 y el estado local quedó actualizado;
   *          {@code false} si había otro PATCH en vuelo para la fila o si falló (en cuyo
   *          caso el error queda reportado en el banner {@code errorMensaje})
   */
  async function marcarYActualizar(notificacion: NotificacionUI): Promise<boolean> {
    if (marcadoEnVueloRef.current.has(notificacion.id)) {
      // Doble activación durante el PATCH pendiente: se ignora (guarda anti-doble-click).
      return false;
    }
    marcadoEnVueloRef.current.add(notificacion.id);
    const baseUrl = process.env.NEXT_PUBLIC_API_URL || '';
    try {
      await marcarComoLeida(`${baseUrl}/notificaciones/${notificacion.id}/leer`);
    } catch (err) {
      setErrorMensaje(
        err instanceof ErrorApiNotificaciones
          ? err.message
          : 'Error de red al conectar con el servidor'
      );
      return false;
    } finally {
      marcadoEnVueloRef.current.delete(notificacion.id);
    }
    setNotificaciones((previas) =>
      previas.map((una) =>
        una.id === notificacion.id ? { ...una, leida: true } : una
      )
    );
    // Sincronización panel → header (PHA15TSK06): el PATCH fue 200 y el estado local quedó
    // actualizado; se emite el evento de ventana para que la campana del Shell refresque su
    // contador de no leídas. Solo en éxito: un PATCH fallido no debe mover el badge del header.
    window.dispatchEvent(new Event(EVENTO_NOTIFICACION_LEIDA));
    return true;
  }

  /**
   * Manejador de activación de una fila navegable (click o Enter/Espacio): resuelve el
   * destino con {@link rutaDestino}, marca como leída vía PATCH y NAVEGA al final — el
   * {@code router.push} solo ocurre si el PATCH fue 200 y el estado local ya está
   * actualizado (decisión 4 de PHA09TSK05-L01).
   *
   * @param notificacion fila activada
   */
  async function handleNotificacionClick(notificacion: NotificacionUI): Promise<void> {
    const destino = rutaDestino(
      notificacion.tipo,
      notificacion.transaccionId,
      notificacion.mensaje
    );
    if (destino === null) {
      return;
    }
    const marcada = await marcarYActualizar(notificacion);
    if (!marcada) {
      return;
    }
    router.push(destino);
  }

  /**
   * Manejador del botón "Marcar como leída": detiene la propagación del click para que NO
   * llegue al contenedor navegable (decisión 5 de PHA09TSK05-L01) y ejecuta únicamente el
   * PATCH con actualización local — sin navegación.
   *
   * @param event evento de click del botón (se detiene su propagación)
   * @param notificacion fila objetivo del PATCH
   */
  function handleMarcarComoLeidaClick(
    event: React.MouseEvent<HTMLButtonElement>,
    notificacion: NotificacionUI
  ): void {
    event.stopPropagation();
    void marcarYActualizar(notificacion);
  }

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
            const destino = rutaDestino(
              notificacion.tipo,
              notificacion.transaccionId,
              notificacion.mensaje
            );
            const navegable = destino !== null;
            return (
              <li key={notificacion.id}>
                <div
                  {...(navegable
                    ? {
                        role: 'link',
                        tabIndex: 0,
                        onClick: () => void handleNotificacionClick(notificacion),
                        onKeyDown: (event: React.KeyboardEvent<HTMLDivElement>) => {
                          if (event.key === 'Enter' || event.key === ' ') {
                            event.preventDefault();
                            void handleNotificacionClick(notificacion);
                          }
                        }
                      }
                    : {})}
                  className={`bg-white border border-slate-200 rounded-lg p-5 shadow-none space-y-2 ${
                    navegable
                      ? 'cursor-pointer hover:bg-slate-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-[#0F172A]/30'
                      : ''
                  }`}
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
                        {/* Categoría legible derivada del tipo estable (PHA15TSK06): etiqueta
                            discreta label-caps de DESIGN.md (badge 4px, no pill). */}
                        <span
                          className="label-caps inline-flex items-center rounded-xs border border-slate-200 bg-slate-50 px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wider text-slate-500"
                        >
                          {categoriaNotificacion(notificacion.tipo)}
                        </span>
                        <span className="font-mono">{formatearFechaNotificacion(notificacion.createdAt)}</span>
                        {notificacion.transaccionId !== null && (
                          <span className="font-mono">Transacción #{notificacion.transaccionId}</span>
                        )}
                      </div>
                      {noLeida && (
                        <button
                          type="button"
                          onClick={(event) =>
                            handleMarcarComoLeidaClick(event, notificacion)
                          }
                          className="mt-2 inline-flex items-center px-3 py-1.5 text-xs font-semibold rounded border border-slate-300 text-slate-700 bg-white hover:bg-slate-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-[#0F172A]/30"
                        >
                          Marcar como leída
                        </button>
                      )}
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
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
