/**
 * @file sesion-utils.ts
 * @description Capa de transporte de la identidad de sesión actual.
 *
 * Expone el contrato de `GET /usuarios/me` (PHA06TSK04, cerrado): una sola
 * función que devuelve la identidad mínima del usuario autenticado o lanza
 * {@link ErrorApiSesion} ante cualquier respuesta no satisfactoria. La
 * revalidación defensiva de la forma del cuerpo protege al shell y a la home
 * de un backend que cambie de contrato sin aviso.
 *
 * También expone el contrato de `POST /auth/logout` (PHA07TSK02, cerrado):
 * {@link cerrarSesion} instruye al backend a expirar la cookie `jwt` (que es
 * httpOnly y no puede manipularse desde JavaScript) y resuelve cuando la
 * respuesta es 2xx, sin fabricar validación de un cuerpo que la interfaz no
 * consume.
 */

/** Roles que el sistema reconoce (contrato de `GET /usuarios/me`). */
export type RolUsuario = 'USUARIO' | 'ADMIN';

/** Identidad mínima del usuario autenticado consumida por la interfaz. */
export interface UsuarioActualUI {
  /** Identificador numérico del usuario. */
  id: number;
  /** Correo electrónico del usuario. */
  email: string;
  /** Rol del usuario, decide los destinos autorizados de la navegación. */
  rol: RolUsuario;
}

/** Valores aceptados por el transporte al revalidar el cuerpo de la respuesta. */
const ROLES_VALIDOS: readonly RolUsuario[] = ['USUARIO', 'ADMIN'];

/**
 * Error de sesión con código HTTP, para que la interfaz distinga una sesión
 * caducada (403) de un fallo de red o de un contrato inesperado.
 */
export class ErrorApiSesion extends Error {
  /**
   * Código de estado HTTP de la respuesta; `undefined` si el fallo ocurrió
   * antes de recibir una respuesta (red, JSON inválido, forma inesperada).
   */
  readonly codigoHttp?: number;

  /**
   * @param mensaje Descripción del fallo para logs y depuración.
   * @param codigoHttp Código HTTP de la respuesta cuando existe.
   */
  constructor(mensaje: string, codigoHttp?: number) {
    super(mensaje);
    this.name = 'ErrorApiSesion';
    this.codigoHttp = codigoHttp;
  }
}

/**
 * Carga la identidad del usuario autenticado desde la API.
 *
 * @param url URL completa del endpoint `usuarios/me` (API base + ruta).
 * @returns La identidad mínima del usuario si la sesión es válida.
 * @throws {ErrorApiSesion} Si la respuesta no es satisfactoria (403 sin
 *   sesión, red caída) o el cuerpo no cumple la forma esperada.
 */
export async function cargarUsuarioActual(url: string): Promise<UsuarioActualUI> {
  let respuesta: Response;
  try {
    respuesta = await fetch(url, { method: 'GET', credentials: 'include' });
  } catch {
    throw new ErrorApiSesion('No se pudo contactar al servicio de sesión');
  }

  if (!respuesta.ok) {
    let detalle = '';
    try {
      const cuerpo = await respuesta.json();
      if (typeof cuerpo?.mensaje === 'string') {
        detalle = cuerpo.mensaje;
      }
    } catch {
      // Sin cuerpo legible: se reporta solo el código HTTP.
    }
    throw new ErrorApiSesion(
      detalle ? `Sesión no válida: ${detalle}` : 'Sesión no válida',
      respuesta.status
    );
  }

  let cuerpo: unknown;
  try {
    cuerpo = await respuesta.json();
  } catch {
    throw new ErrorApiSesion('Respuesta de sesión sin cuerpo JSON');
  }

  if (!esUsuarioActual(cuerpo)) {
    throw new ErrorApiSesion('Respuesta de sesión con formato esperado no válido');
  }

  return { id: cuerpo.id, email: cuerpo.email, rol: cuerpo.rol };
}

/**
 * Revalidación defensiva: comprueba que el cuerpo cumpla la forma de
 * {@link UsuarioActualUI}.
 *
 * @param valor Cuerpo de la respuesta recibido desde la API.
 * @returns `true` solo si `id` es número, `email` es string no vacío y
 *   `rol` es uno de los roles conocidos.
 */
function esUsuarioActual(valor: unknown): valor is UsuarioActualUI {
  if (typeof valor !== 'object' || valor === null) {
    return false;
  }
  const candidato = valor as Record<string, unknown>;
  return (
    typeof candidato.id === 'number' &&
    typeof candidato.email === 'string' &&
    candidato.email.length > 0 &&
    typeof candidato.rol === 'string' &&
    (ROLES_VALIDOS as readonly string[]).includes(candidato.rol)
  );
}

/**
 * Cierra la sesión del usuario instruyendo al backend a expirar la cookie.
 *
 * Contrato real de `POST /auth/logout` (PHA07TSK02, cerrado): responde 200
 * con `Set-Cookie: jwt=; Max-Age=0; Path=/; HttpOnly; Secure; SameSite=None`
 * y body `{"mensaje":"Sesión cerrada"}`. La cookie `jwt` es httpOnly: la
 * borra el `Set-Cookie` expirante del backend y el frontend no puede ni debe
 * manipularla con `document.cookie`. No existe invalidación server-side del
 * JWT — exclusión deliberada de spec.md Story 0b (alcance de portfolio).
 *
 * Criterio de éxito: cualquier respuesta 2xx. El body de éxito no se lee ni
 * se valida (la interfaz no consume datos del logout) y el `Set-Cookie` no
 * es verificable desde JavaScript por `httpOnly`, por lo que el contrato de
 * la función se limita a "el backend confirmó el cierre".
 *
 * @param url URL completa del endpoint `auth/logout` (API base + ruta).
 * @returns Promesa resuelta (`void`) cuando el backend confirmó el cierre.
 * @throws {ErrorApiSesion} Si la respuesta no es satisfactoria — con
 *   `codigoHttp` y el `mensaje` del backend cuando el cuerpo lo trae — o si
 *   la red falla (sin `codigoHttp`).
 */
export async function cerrarSesion(url: string): Promise<void> {
  let respuesta: Response;
  try {
    respuesta = await fetch(url, { method: 'POST', credentials: 'include' });
  } catch {
    throw new ErrorApiSesion('No se pudo contactar al servicio de sesión');
  }

  if (!respuesta.ok) {
    let detalle = '';
    try {
      const cuerpo = await respuesta.json();
      if (typeof cuerpo?.mensaje === 'string') {
        detalle = cuerpo.mensaje;
      }
    } catch {
      // Sin cuerpo legible: se reporta solo el código HTTP.
    }
    throw new ErrorApiSesion(
      detalle ? `No se pudo cerrar la sesión: ${detalle}` : 'No se pudo cerrar la sesión',
      respuesta.status
    );
  }
}
