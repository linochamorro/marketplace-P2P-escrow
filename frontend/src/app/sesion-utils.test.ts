import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ErrorApiSesion, cargarUsuarioActual, cerrarSesion } from './sesion-utils';

/**
 * @file sesion-utils.test.ts
 * @description Pruebas de la capa de transporte de identidad de sesión
 * (PHA06TSK09) y de cierre de sesión (PHA07TSK03).
 *
 * Cobertura del contrato real de `GET /usuarios/me` (PHA06TSK04, cerrado):
 * 1. 200 OK con forma válida (`{ id, email, rol }`) devuelve `UsuarioActualUI`.
 * 2. Respuesta no-ok (p. ej. 403 sin cookie o token inválido) lanza
 *    `ErrorApiSesion` conservando el `codigoHttp`.
 * 3. Cuerpo 200 con forma inválida (rol desconocido) lanza `ErrorApiSesion`
 *    en lugar de fabricar una identidad (revalidación defensiva).
 *
 * Cobertura del contrato real de `POST /auth/logout` (PHA07TSK02, cerrado):
 * 1. Cualquier 2xx resuelve sin lanzar (el éxito es el `Set-Cookie` expirante
 *    que el navegador aplica; el frontend no puede leer la cookie httpOnly).
 * 2. Respuesta no-ok lanza `ErrorApiSesion` con `codigoHttp` y el `mensaje`
 *    del backend cuando existe.
 * 3. Fallo de red lanza `ErrorApiSesion` sin `codigoHttp` con mensaje genérico.
 */

const URL = 'http://localhost:8080/usuarios/me';

/** Construye una respuesta HTTP simulada con la porción consumida por el transporte. */
function respuestaHttp(ok: boolean, status: number, body: unknown) {
  return { ok, status, json: async () => body };
}

describe('cargarUsuarioActual (PHA06TSK09)', () => {
  const originalApiUrl = process.env.NEXT_PUBLIC_API_URL;

  beforeEach(() => {
    vi.restoreAllMocks();
    process.env.NEXT_PUBLIC_API_URL = 'http://localhost:8080';
  });

  afterEach(() => {
    process.env.NEXT_PUBLIC_API_URL = originalApiUrl;
  });

  it('200 con forma válida devuelve la identidad mínima del usuario', async () => {
    global.fetch = vi.fn().mockResolvedValue(
      respuestaHttp(true, 200, { id: 7, email: 'vendedor@easymarket.dev', rol: 'USUARIO' })
    );

    const usuario = await cargarUsuarioActual(URL);

    expect(usuario).toEqual({ id: 7, email: 'vendedor@easymarket.dev', rol: 'USUARIO' });
    expect(global.fetch).toHaveBeenCalledWith(URL, { method: 'GET', credentials: 'include' });
  });

  it('403 sin sesión lanza ErrorApiSesion con codigoHttp 403 y el mensaje del backend', async () => {
    global.fetch = vi.fn().mockResolvedValue(
      respuestaHttp(false, 403, { mensaje: 'No autorizado' })
    );

    const error = await cargarUsuarioActual(URL).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ErrorApiSesion);
    expect((error as ErrorApiSesion).codigoHttp).toBe(403);
    expect((error as ErrorApiSesion).message).toContain('No autorizado');
  });

  it('200 con rol desconocido en el cuerpo lanza ErrorApiSesion (revalidación defensiva)', async () => {
    global.fetch = vi.fn().mockResolvedValue(
      respuestaHttp(true, 200, { id: 1, email: 'x@test.com', rol: 'INVITADO' })
    );

    const error = await cargarUsuarioActual(URL).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ErrorApiSesion);
    expect((error as ErrorApiSesion).message).toContain('formato esperado');
  });
});

describe('cerrarSesion (PHA07TSK03)', () => {
  /** URL determinista del endpoint de logout bajo prueba. */
  const URL_LOGOUT = 'http://localhost:8080/auth/logout';

  it('2xx exitoso resuelve sin lanzar, enviando POST con credentials include', async () => {
    global.fetch = vi.fn().mockResolvedValue(
      respuestaHttp(true, 200, { mensaje: 'Sesión cerrada' })
    );

    await expect(cerrarSesion(URL_LOGOUT)).resolves.toBeUndefined();
    expect(global.fetch).toHaveBeenCalledWith(URL_LOGOUT, { method: 'POST', credentials: 'include' });
  });

  it('acepta cualquier 2xx (204 sin body) sin fabricar validación del cuerpo de éxito', async () => {
    global.fetch = vi.fn().mockResolvedValue(respuestaHttp(true, 204, null));

    await expect(cerrarSesion(URL_LOGOUT)).resolves.toBeUndefined();
  });

  it('respuesta no-ok (500) lanza ErrorApiSesion con codigoHttp y el mensaje del backend', async () => {
    global.fetch = vi.fn().mockResolvedValue(
      respuestaHttp(false, 500, { mensaje: 'Error interno del servidor' })
    );

    const error = await cerrarSesion(URL_LOGOUT).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ErrorApiSesion);
    expect((error as ErrorApiSesion).codigoHttp).toBe(500);
    expect((error as ErrorApiSesion).message).toContain('Error interno del servidor');
  });

  it('fallo de red lanza ErrorApiSesion sin codigoHttp con mensaje genérico', async () => {
    global.fetch = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));

    const error = await cerrarSesion(URL_LOGOUT).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ErrorApiSesion);
    expect((error as ErrorApiSesion).codigoHttp).toBeUndefined();
    expect((error as ErrorApiSesion).message).toContain('No se pudo contactar');
  });
});
