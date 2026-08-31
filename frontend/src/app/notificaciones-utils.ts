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
 *
 * <p>Desde la recuperación de PHA09TSK05 (PHA12TSK05), incluye además los 9 tipos
 * accionables del CHECK constraint V19, divididos por rol según
 * {@code NotificacionService.listarPorUsuarioYRol}: los 2 tipos ADMIN
 * ({@code PUBLICACION_PENDIENTE_APROBAR}, {@code DISPUTA_PENDIENTE_RESOLVER}) y los 7
 * tipos USER ({@code RESPUESTA_USUARIO_PENDIENTE}, {@code PUBLICACION_APROBADA_RECHAZADA},
 * {@code COMPRA_CONFIRMADA}, {@code ENVIO_MARCADO}, {@code ENTREGA_MARCADA},
 * {@code DISPUTA_ABIERTA}, {@code DISPUTA_RESUELTA}). Los 3 tipos históricos de PHA04 se
 * conservan para no romper la presentación de datos previos a V19.</p>
 */
export const ETIQUETAS_TIPO: Record<string, string> = {
  // Tipos históricos de PHA04 (avisos diarios/puntuales de transacción abierta).
  ENVIO_PENDIENTE_48H: 'Envío pendiente',
  COMPRA_PENDIENTE_DIARIA: 'Compra pendiente',
  VENTA_POR_ENTREGAR_DIARIA: 'Venta por entregar',
  // Tipos ADMIN (moderación y disputas pendientes de acción administrativa).
  PUBLICACION_PENDIENTE_APROBAR: 'Publicación por aprobar',
  DISPUTA_PENDIENTE_RESOLVER: 'Disputa por resolver',
  // Tipos USER (proceso compra/venta/envío/disputa).
  RESPUESTA_USUARIO_PENDIENTE: 'Respuesta pendiente',
  PUBLICACION_APROBADA_RECHAZADA: 'Moderación de publicación',
  COMPRA_CONFIRMADA: 'Compra confirmada',
  ENVIO_MARCADO: 'Envío marcado',
  ENTREGA_MARCADA: 'Entrega marcada',
  DISPUTA_ABIERTA: 'Disputa abierta',
  DISPUTA_RESUELTA: 'Disputa resuelta'
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

/**
 * Tipos USER cuyo destino depende del mensaje: el backend no indica el rol del destinatario
 * dentro de la transacción, pero los textos emitidos por los servicios de dominio
 * (PHA12TSK03) distinguen al comprador con "tu compra" y al vendedor con "tu venta".
 *
 * <p>Incluye {@code RESPUESTA_USUARIO_PENDIENTE} aunque hoy ningún servicio lo emita:
 * figura en el filtro por rol de {@code NotificacionService.listarPorUsuarioYRol} y en el
 * CHECK constraint V19, así que si aparece una notificación con ese tipo debe enrutarse por
 * la misma regla de mensaje y no caer al fallback.</p>
 */
const TIPOS_USER_CON_TRANSACCION = new Set([
  'RESPUESTA_USUARIO_PENDIENTE',
  'COMPRA_CONFIRMADA',
  'ENVIO_MARCADO',
  'ENTREGA_MARCADA',
  'DISPUTA_ABIERTA',
  'DISPUTA_RESUELTA'
]);

/**
 * Resuelve la ruta de destino a la que navega una notificación accionable al hacer click.
 *
 * <p>Mapeo completo (decisión declarada en el Artifact PHA12TSK05-L01, trazado a los
 * destinos del criterio original de PHA09TSK05 y a las rutas existentes del frontend):</p>
 * <ul>
 *   <li>ADMIN — moderación: {@code PUBLICACION_PENDIENTE_APROBAR} → {@code /admin/moderacion}.</li>
 *   <li>ADMIN — disputas: {@code DISPUTA_PENDIENTE_RESOLVER} → {@code /admin/disputas}.</li>
 *   <li>USER sin transacción: {@code PUBLICACION_APROBADA_RECHAZADA} →
 *       {@code /mis-publicaciones} (el backend la emite con {@code transaccionId: null}).</li>
 *   <li>USER con transacción ({@code COMPRA_CONFIRMADA}, {@code ENVIO_MARCADO},
 *       {@code ENTREGA_MARCADA}, {@code DISPUTA_ABIERTA}, {@code DISPUTA_RESUELTA},
 *       {@code RESPUESTA_USUARIO_PENDIENTE}): el mensaje decide el lado de la transacción —
 *       contiene "tu compra" → {@code /compras/{transaccionId}}; contiene "tu venta" →
 *       {@code /ventas/{transaccionId}} (textos reales de PHA12TSK03, p. ej.
 *       "El vendedor marcó tu compra #N como enviada" vs "Marcaste tu venta #N como enviada").</li>
 * </ul>
 *
 * <p>Fallback defensivo: devuelve {@code null} para tipos desconocidos, para tipos USER
 * con transacción sin {@code transaccionId} o cuando el mensaje no matchea ninguna de las
 * dos expresiones — la fila se muestra como texto plano, nunca como un link roto. El
 * matching es insensible a mayúsculas/minúsculas por robustez ante cambios de estilo del
 * copy en el backend.</p>
 *
 * @param tipo categoría estable del aviso (contrato NotificacionResponseDto)
 * @param transaccionId ID de la transacción asociada (nullable)
 * @param mensaje contenido literal del aviso emitido por el backend
 * @returns ruta interna navegable, o {@code null} si no hay destino determinable
 */
export function rutaDestino(
  tipo: string,
  transaccionId: number | null,
  mensaje: string
): string | null {
  if (tipo === 'PUBLICACION_PENDIENTE_APROBAR') {
    return '/admin/moderacion';
  }
  if (tipo === 'DISPUTA_PENDIENTE_RESOLVER') {
    return '/admin/disputas';
  }
  if (tipo === 'PUBLICACION_APROBADA_RECHAZADA') {
    return '/mis-publicaciones';
  }
  if (TIPOS_USER_CON_TRANSACCION.has(tipo)) {
    if (transaccionId === null) {
      return null;
    }
    const mensajeNormalizado = mensaje.toLowerCase();
    if (mensajeNormalizado.includes('tu compra')) {
      return `/compras/${transaccionId}`;
    }
    if (mensajeNormalizado.includes('tu venta')) {
      return `/ventas/${transaccionId}`;
    }
    return null;
  }
  return null;
}

/**
 * Marca una notificación como leída contra el contrato real
 * {@code PATCH /notificaciones/{id}/leer} (PHA12TSK04) con la cookie httpOnly:
 * {@code credentials: 'include'}. La operación es idempotente en el backend (200 con el DTO
 * actualizado incluso si ya estaba leída) y responde 403 si la notificación pertenece a otro
 * usuario o 404 si no existe, siempre con JSON {@code {"mensaje"}}.
 *
 * <p>Espeja exactamente el manejo de errores de {@link cargarNotificaciones}: respuesta
 * HTTP no-ok → {@link ErrorApiNotificaciones} con el {@code mensaje} del backend o un
 * fallback con el código; fallo de red → propaga el error original de fetch para que el
 * llamador decida cómo reportarlo.</p>
 *
 * @param url endpoint completo de marcado (base + `/notificaciones/{id}/leer`)
 * @returns el DTO actualizado de la notificación (contrato 200 del endpoint)
 * @throws ErrorApiNotificaciones si la respuesta HTTP no es 200 (con el {@code mensaje} del
 *         backend o fallback con código HTTP), o el error de red original si fetch rechaza
 */
export async function marcarComoLeida(url: string): Promise<NotificacionUI> {
  const response = await fetch(url, {
    method: 'PATCH',
    credentials: 'include'
  });

  if (!response.ok) {
    const errorData = await response.json().catch(() => null);
    const mensaje =
      errorData?.mensaje || `Error al marcar la notificación como leída (código ${response.status})`;
    throw new ErrorApiNotificaciones(mensaje, response.status);
  }

  return (await response.json()) as NotificacionUI;
}

/**
 * Nombre del evento de ventana que el {@link PanelCentroNotificaciones} emite después de marcar
 * una notificación como leída con éxito (PATCH 200) y que el {@code Shell} escucha para
 * refrescar el contador de no leídas de la campana (PHA15TSK06).
 *
 * <p>Es el mecanismo declarado de sincronización panel → header: un evento de ventana nativo,
 * sin dependencias nuevas ni estado global. La comunicación es unidireccional (solo el header
 * reacciona) y el emisor no conoce a sus oyentes, de modo que el panel sigue funcionando de
 * forma autónoma.</p>
 */
export const EVENTO_NOTIFICACION_LEIDA = 'easymarket:notificacion-leida';

/**
 * Categorías legibles con las que el panel identifica visualmente cada aviso (PHA15TSK06,
 * tasks.md: "compra nueva, estado de compra/envío, recordatorio periódico, moderación, disputa
 * o advertencia"). NO existe categoría de aprobación de compra (exclusión explícita de la
 * tarea). {@code Otro} es únicamente el fallback defensivo para tipos desconocidos — no es una
 * categoría de negocio.
 */
export type CategoriaNotificacion =
  | 'Compra nueva'
  | 'Estado de compra/envío'
  | 'Recordatorio periódico'
  | 'Moderación'
  | 'Disputa'
  | 'Advertencia'
  | 'Otro';

/**
 * Mapeo exhaustivo de {@code tipo} → categoría legible (PHA15TSK06).
 *
 * <p>Cubre TODOS los tipos estables del contrato: los 7 accionables USER, los 2 accionables
 * ADMIN, los 3 recordatorios periódicos del USUARIO y {@code NUEVA_PUBLICACION_PENDIENTE} del
 * ADMIN (los cuatro últimos visibles en el listado desde PHA15TSK06). Cualquier tipo futuro no
 * listado cae en el fallback "Otro" de {@link categoriaNotificacion}.</p>
 */
const CATEGORIA_POR_TIPO: Record<string, CategoriaNotificacion> = {
  // Compra nueva.
  COMPRA_CONFIRMADA: 'Compra nueva',
  // Estado de compra/envío.
  ENVIO_MARCADO: 'Estado de compra/envío',
  ENTREGA_MARCADA: 'Estado de compra/envío',
  // Recordatorio periódico (avisos diarios/48H, visibles desde PHA15TSK06).
  COMPRA_PENDIENTE_DIARIA: 'Recordatorio periódico',
  VENTA_POR_ENTREGAR_DIARIA: 'Recordatorio periódico',
  ENVIO_PENDIENTE_48H: 'Recordatorio periódico',
  // Moderación.
  PUBLICACION_PENDIENTE_APROBAR: 'Moderación',
  NUEVA_PUBLICACION_PENDIENTE: 'Moderación',
  PUBLICACION_APROBADA_RECHAZADA: 'Moderación',
  // Disputa.
  DISPUTA_PENDIENTE_RESOLVER: 'Disputa',
  DISPUTA_ABIERTA: 'Disputa',
  DISPUTA_RESUELTA: 'Disputa',
  // Advertencia.
  RESPUESTA_USUARIO_PENDIENTE: 'Advertencia'
};

/**
 * Clasifica un {@code tipo} de notificación en su categoría legible para el usuario final
 * (PHA15TSK06).
 *
 * <p>Fallback declarado para tipos desconocidos (incluidos tipos futuros que el backend
 * agregue sin aviso): la categoría {@code Otro}, que evita inventar una categoría de negocio
 * sin autorización y garantiza que la fila siempre recibe una etiqueta renderizable. La
 * clasificación es una función pura: el panel y su test comparten exactamente el mismo
 * criterio, sin duplicar lógica en el render.</p>
 *
 * @param tipo categoría estable del aviso (contrato NotificacionResponseDto)
 * @returns categoría legible entre las 6 de negocio, o "Otro" para tipos desconocidos
 */
export function categoriaNotificacion(tipo: string): CategoriaNotificacion {
  return CATEGORIA_POR_TIPO[tipo] ?? 'Otro';
}

/**
 * Carga la cantidad de notificaciones accionables no leídas del usuario autenticado desde el
 * contrato real {@code GET /notificaciones/no-leidas/count} (PHA15TSK05) con la cookie
 * httpOnly: {@code GET} puro, sin body ni parámetros (usuario y rol salen del JWT del backend,
 * constitution principio 7). Es la fuente del badge numérico de la campana del header
 * (PHA15TSK06).
 *
 * <p>Tratamiento de errores declarado: cualquier fallo (respuesta HTTP no-ok, cuerpo con forma
 * inesperada o error de red) devuelve {@code null} en lugar de lanzar — el header debe seguir
 * funcional sin badge ante un backend caído o una sesión expirada, y el fallo se registra en
 * consola (log silencioso, sin UI de error en el header). La revalidación defensiva del cuerpo
 * ({@code cantidad} numérico finito no negativo) protege al shell de un backend que cambie de
 * contrato sin aviso.</p>
 *
 * @param url endpoint completo (base + '/notificaciones/no-leidas/count')
 * @returns la cantidad de no leídas, o {@code null} si no pudo obtenerse (error o forma inválida)
 */
export async function cargarCantidadNoLeidas(url: string): Promise<number | null> {
  try {
    const response = await fetch(url, {
      method: 'GET',
      credentials: 'include'
    });

    if (!response.ok) {
      console.error(`No se pudo obtener el contador de notificaciones (código ${response.status})`);
      return null;
    }

    const data: unknown = await response.json();
    if (typeof data === 'object' && data !== null && 'cantidad' in data) {
      const candidata = (data as { cantidad: unknown }).cantidad;
      if (typeof candidata === 'number' && Number.isFinite(candidata) && candidata >= 0) {
        return candidata;
      }
    }
    console.error('Respuesta del contador de notificaciones con formato inesperado');
    return null;
  } catch (error) {
    console.error('Error de red al consultar el contador de notificaciones', error);
    return null;
  }
}