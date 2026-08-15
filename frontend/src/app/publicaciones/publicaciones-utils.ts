/**
 * Modelos, transporte y conversiones de presentación para el listado de publicaciones
 * de Story 11 (PHA05TSK04).
 *
 * Los precios permanecen como centavos enteros en el contrato de aplicación. Los textos
 * en soles se convierten a una cadena de centavos exclusivamente al construir la URL HTTP
 * y se formatean mediante segmentos de texto para no introducir precisión monetaria flotante.
 */

/** Subcategoría anidada devuelta por `GET /categorias`. */
export interface SubcategoriaListado {
  /** Identificador estable de la subcategoría. */
  id: number;
  /** Nombre visible de la subcategoría. */
  nombre: string;
}

/** Categoría raíz y sus subcategorías devueltas por `GET /categorias`. */
export interface CategoriaListado {
  /** Identificador estable de la categoría raíz. */
  id: number;
  /** Nombre visible de la categoría raíz. */
  nombre: string;
  /** Subcategorías pertenecientes a esta categoría. */
  subcategorias: SubcategoriaListado[];
}

/** Publicación tal como llega de `GET /publicaciones` en Story 11. */
export interface PublicacionListado {
  /** Identificador de la publicación. */
  id: number;
  /** Precio entero en centavos, sin representación decimal persistente. */
  precio: number;
  /** Unidades disponibles que entrega el backend. */
  stock: number;
  /** Estado de publicación provisto por el contrato. */
  estado: string;
  /** Descripción literal de la publicación. */
  descripcion: string;
  /** Identificador de la categoría de la publicación. */
  categoriaId: number;
  /** Identificador de la subcategoría de la publicación. */
  subcategoriaId: number;
  /** Identificador del vendedor dueño de la publicación. */
  usuarioId: number;
}

/** Valores autorizados por `OrdenListadoPublicaciones` para el query `orden`. */
export type OrdenListado = 'PRECIO_ASCENDENTE' | 'PRECIO_DESCENDENTE' | 'MAS_VENDIDO';

/** Valores locales de filtros que pueden convertirse al contrato HTTP de Story 11. */
export interface FiltrosListado {
  /** ID textual de categoría seleccionado; vacío significa que no se envía. */
  categoriaId: string;
  /** ID textual de subcategoría seleccionado; vacío significa que no se envía. */
  subcategoriaId: string;
  /** Límite inferior escrito en soles; vacío significa que no se envía. */
  precioMinimo: string;
  /** Límite superior escrito en soles; vacío significa que no se envía. */
  precioMaximo: string;
  /** Orden explícito seleccionado; vacío significa que no se envía. */
  orden: OrdenListado | '';
}

/** Error HTTP del transporte que conserva el `mensaje` del backend cuando existe. */
export class ErrorApiPublicaciones extends Error {
  /** Código HTTP entregado por la respuesta no exitosa. */
  readonly codigoHttp: number;

  /**
   * Construye un error de transporte del listado.
   *
   * @param mensaje mensaje del backend o fallback contextual
   * @param codigoHttp estado HTTP de la respuesta
   */
  constructor(mensaje: string, codigoHttp: number) {
    super(mensaje);
    this.name = 'ErrorApiPublicaciones';
    this.codigoHttp = codigoHttp;
  }
}

/**
 * Convierte una cantidad en soles ingresada como texto a su representación entera en
 * centavos, sin usar aritmética decimal.
 *
 * @param soles texto no negativo con hasta dos decimales
 * @returns cadena de centavos para el query, o `null` si el formato no es válido
 */
export function solesACentavos(soles: string): string | null {
  if (!/^\d+(?:\.\d{1,2})?$/.test(soles)) {
    return null;
  }
  const [unidades, fraccion = ''] = soles.split('.');
  const centavos = `${unidades}${fraccion.padEnd(2, '0')}`.replace(/^0+(?=\d)/, '');
  return centavos || '0';
}

/**
 * Formatea centavos enteros del contrato a soles para lectura, separando los dos últimos
 * dígitos en vez de calcular con un decimal de JavaScript.
 *
 * @param centavos precio entero en centavos entregado por el backend
 * @returns texto monetario con el prefijo `S/` y dos decimales
 */
export function formatearPrecioSoles(centavos: number): string {
  const digitos = String(centavos).padStart(3, '0');
  return `S/ ${digitos.slice(0, -2)}.${digitos.slice(-2)}`;
}

/**
 * Construye el query de filtros autorizado y omite controles vacíos.
 *
 * @param filtros valores locales controlados por el formulario
 * @returns `URLSearchParams` con IDs, centavos y orden únicamente cuando tienen valor
 */
export function construirParametrosListado(filtros: FiltrosListado): URLSearchParams {
  const parametros = new URLSearchParams();
  if (filtros.categoriaId) parametros.set('categoriaId', filtros.categoriaId);
  if (filtros.subcategoriaId) parametros.set('subcategoriaId', filtros.subcategoriaId);
  if (filtros.precioMinimo) parametros.set('precioMinimo', filtros.precioMinimo);
  if (filtros.precioMaximo) parametros.set('precioMaximo', filtros.precioMaximo);
  if (filtros.orden) parametros.set('orden', filtros.orden);
  return parametros;
}

/**
 * Extrae el mensaje de error del contrato común del backend sin asumir que el cuerpo es JSON.
 *
 * @param response respuesta HTTP no exitosa
 * @param fallback texto contextual si el backend no envía `mensaje`
 * @returns mensaje seguro para mostrar al usuario
 */
async function mensajeError(response: Response, fallback: string): Promise<string> {
  const data: unknown = await response.json().catch(() => null);
  if (data !== null && typeof data === 'object' && 'mensaje' in data) {
    const mensaje = (data as { mensaje?: unknown }).mensaje;
    if (typeof mensaje === 'string' && mensaje) return mensaje;
  }
  return fallback;
}

/**
 * Carga las categorías reales para los selectores dependientes con la cookie httpOnly.
 *
 * @param url endpoint completo de categorías
 * @returns árbol de categorías devuelto por el backend
 * @throws ErrorApiPublicaciones si la respuesta no es exitosa
 */
export async function cargarCategorias(url: string): Promise<CategoriaListado[]> {
  const response = await fetch(url, { method: 'GET', credentials: 'include' });
  if (!response.ok) {
    throw new ErrorApiPublicaciones(
      await mensajeError(response, `Error al cargar categorías (código ${response.status})`),
      response.status
    );
  }
  const data: unknown = await response.json();
  if (!Array.isArray(data)) {
    throw new ErrorApiPublicaciones('La respuesta del servidor no tiene el formato esperado', response.status);
  }
  return data as CategoriaListado[];
}

/**
 * Carga publicaciones aprobadas desde el endpoint de listado con la cookie httpOnly.
 *
 * @param url endpoint completo, con query opcional de filtros autorizados
 * @returns publicaciones en el orden exacto devuelto por el backend
 * @throws ErrorApiPublicaciones si la respuesta no es exitosa o no es una lista
 */
export async function cargarPublicaciones(url: string): Promise<PublicacionListado[]> {
  const response = await fetch(url, { method: 'GET', credentials: 'include' });
  if (!response.ok) {
    throw new ErrorApiPublicaciones(
      await mensajeError(response, `Error al cargar publicaciones (código ${response.status})`),
      response.status
    );
  }
  const data: unknown = await response.json();
  if (!Array.isArray(data)) {
    throw new ErrorApiPublicaciones('La respuesta del servidor no tiene el formato esperado', response.status);
  }
  return data as PublicacionListado[];
}
