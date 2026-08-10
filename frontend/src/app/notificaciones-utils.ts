/**
 * Tipos y utilidades de presentación del Centro de Notificaciones (PHA04TSK20).
 *
 * <p>Modela el contrato de {@code NotificacionResponseDto} del endpoint
 * {@code GET /notificaciones} (PHA04TSK16, cerrado) y centraliza las decisiones de
 * presentación puras (mapeo de tipos, formato de fecha) para que el componente y su test
 * compartan exactamente el mismo modelo, sin duplicar lógica en el render.</p>
 */

/**
 * Notificación tal como llega del contrato `GET /notificaciones` (PHA04TSK16, ya cerrado).
 *
 * <p>Modela literalmente el JSON de {@code NotificacionResponseDto}: el backend devuelve
 * TODAS las notificaciones del usuario autenticado con el flag {@code leida} — no solo las
 * no leídas — ordenadas por {@code createdAt} desc con {@code id} como desempate. No existe
 * endpoint de marcado como leída; el Centro de notificaciones lista todo y resalta
 * visualmente lo no leído (decisión de Lino 2026-08-10 que extiende el test previo de
 * tasks.md).</p>
 */
export interface NotificacionUI {
  /** ID numérico de la notificación (clave de render y de la referencia a transacción) */
  id: number;
  /** Contenido literal del aviso generado por los jobs de PHA04 (story 7 y 7b) */
  mensaje: string;
  /** Categoría estable del aviso (ej. ENVIO_PENDIENTE_48H, COMPRA_PENDIENTE_DIARIA, VENTA_POR_ENTREGAR_DIARIA) */
  tipo: string;
  /** Flag de lectura: {@code true} ya leída; {@code false} no leída (resaltada en el panel) */
  leida: boolean;
  /** Timestamp ISO8601+offset (ZonedDateTime del backend) de creación del aviso */
  createdAt: string;
  /** ID de la transacción asociada (nullable desde la migración V14); null → sin referencia */
  transaccionId: number | null;
}

/**
 * Mapeo presentacional de {@code tipo} → etiqueta legible para el usuario final.
 *
 * <p>Decisión de presentación (el plan NO define este mapeo): las categorías estables
 * conocidas de PHA04 (plan.md:368-370) reciben una etiqueta entendible; cualquier tipo
 * desconocido cae al fallback {@code tipo.toUpperCase()} (el tipo crudo ya viaja en
 * mayúsculas). Declarado como decisión no trivial en el Artifact PHA04TSK20-L01.</p>
 */
export const ETIQUETAS_TIPO: Record<string, string> = {
  ENVIO_PENDIENTE_48H: 'Envío pendiente',
  COMPRA_PENDIENTE_DIARIA: 'Compra pendiente',
  VENTA_POR_ENTREGAR_DIARIA: 'Venta por entregar'
};

/**
 * Devuelve la etiqueta legible para un {@code tipo} de notificación, con fallback al tipo
 * crudo en mayúsculas para valores desconocidos.
 *
 * @param tipo categoría estable del aviso (contrato NotificacionResponseDto)
 * @returns etiqueta entendible para el usuario final
 */
export function etiquetaTipo(tipo: string): string {
  return ETIQUETAS_TIPO[tipo] ?? tipo.toUpperCase();
}

/**
 * Formatea el timestamp ISO del backend a una fecha/hora legible en locale {@code es-PE}
 * (hora de Perú, UTC-5 — decisión del proyecto en spec.md:35 y plan.md:288).
 *
 * <p>Usa {@code dateStyle: 'medium'} + {@code timeStyle: 'short'} del API Intl (sin
 * variantes {@code hour12} frágiles entre runtimes) y {@code timeZone: 'America/Lima'}
 * explícito para no depender de la zona horaria del navegador del usuario. Si la fecha es
 * inválida devuelve la cadena ISO cruda (fallback defensivo: no romper el listado por un
 * timestamp corrupto).</p>
 *
 * @param isoFecha timestamp ISO8601 del backend (p. ej. "2026-08-10T17:00:00-05:00")
 * @returns fecha/hora legible en es-PE, o el valor crudo si no es una fecha válida
 */
export function formatearFechaNotificacion(isoFecha: string): string {
  const fecha = new Date(isoFecha);
  if (Number.isNaN(fecha.getTime())) {
    return isoFecha;
  }
  return new Intl.DateTimeFormat('es-PE', {
    dateStyle: 'medium',
    timeStyle: 'short',
    timeZone: 'America/Lima'
  }).format(fecha);
}

/**
 * Error tipado de la capa de transporte: distingue una respuesta HTTP no-ok del backend
 * (con {@code mensaje} extraíble) de un fallo de red que no devolvió respuesta HTTP.
 */
export class ErrorApiNotificaciones extends Error {
  /** Código HTTP de la respuesta (p. ej. 403 sin sesión) */
  readonly codigoHttp: number;

  /**
   * @param mensaje texto del error (el {@code mensaje} del backend o un texto genérico)
   * @param codigoHttp código HTTP de la respuesta no-ok
   */
  constructor(mensaje: string, codigoHttp: number) {
    super(mensaje);
    this.name = 'ErrorApiNotificaciones';
    this.codigoHttp = codigoHttp;
  }
}

/**
 * Carga la lista de notificaciones del usuario autenticado desde el contrato real
 * {@code GET /notificaciones} (PHA04TSK16) con la cookie httpOnly: {@code GET} puro, sin
 * body ni query params (el plan NO define filtros, paginación ni agrupación server-side).
 *
 * @param url endpoint completo (base + '/notificaciones')
 * @returns la lista de notificaciones devuelta por el backend (posiblemente vacía)
 * @throws ErrorApiNotificaciones si la respuesta HTTP no es 200 (con el {@code mensaje} del
 *         backend o fallback con código HTTP), o el error de red original si fetch rechaza
 */
export async function cargarNotificaciones(url: string): Promise<NotificacionUI[]> {
  const response = await fetch(url, {
    method: 'GET',
    credentials: 'include'
  });

  if (!response.ok) {
    const errorData = await response.json().catch(() => null);
    const mensaje =
      errorData?.mensaje || `Error al cargar las notificaciones (código ${response.status})`;
    throw new ErrorApiNotificaciones(mensaje, response.status);
  }

  // El contrato devuelve un JSON array (posiblemente vacío); lo revalidamos de forma
  // defensiva para no romper el render si el backend cambiara la forma de la respuesta.
  const data: unknown = await response.json();
  return Array.isArray(data) ? (data as NotificacionUI[]) : [];
}