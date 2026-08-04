import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import AuthPage from './page';

/**
 * @file auth.test.tsx
 * @description Pruebas de componente para AuthPage (PHA01TSK10).
 *
 * Cobertura TDD (test previo de tasks.md PHA01TSK10):
 * 1. Validación de campos: registro sin email, sin contraseña o con
 *    contraseña de menos de 8 caracteres NO envía petición (Story 0).
 * 2. Envío de registro exitoso: POST {NEXT_PUBLIC_API_URL}/auth/registro
 *    con `credentials: 'include'` y muestra mensaje de éxito local.
 * 3. Manejo de error del backend en registro (ej. 409 email duplicado).
 * 4. Login exitoso: POST {NEXT_PUBLIC_API_URL}/auth/login con
 *    `credentials: 'include'` y redirección a `/publicaciones`.
 * 5. Fallo de login: muestra el mensaje genérico del backend (Story 0b:
 *    no revela cuál dato falló) y NO redirige.
 * 6. Estado de envío: botón deshabilitado mientras la petición está en curso.
 *
 * Contrato real del backend verificado en AuthController:
 * - POST /auth/registro → 201 + RegistroResponseDto (SIN campo `mensaje`).
 * - POST /auth/login    → 200 + Set-Cookie `jwt` (httpOnly) + body
 *   `{"mensaje": "Inicio de sesión exitoso"}`.
 */

// ─── Mock de next/navigation (top-level, evita warning de vi.mock anidado) ───
const { mockPush } = vi.hoisted(() => ({ mockPush: vi.fn() }));

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: mockPush }),
}));

describe('AuthPage (PHA01TSK10)', () => {
  const originalEnv = process.env.NEXT_PUBLIC_API_URL;

  beforeEach(() => {
    vi.restoreAllMocks();
    mockPush.mockClear();
    process.env.NEXT_PUBLIC_API_URL = 'http://localhost:8080';
  });

  afterEach(() => {
    process.env.NEXT_PUBLIC_API_URL = originalEnv;
  });

  /**
   * Helper: rellena los campos del formulario con el modo activo actual
   * (registro por defecto) y dispara el submit con userEvent (envuelto en act).
   * Nota: usa `fireEvent.change` para los valores porque `userEvent.type` no
   * admite strings vacíos (caso "campo requerido").
   */
  async function fillAndSubmit(email: string, password: string) {
    fireEvent.change(screen.getByLabelText(/email/i), { target: { value: email } });
    fireEvent.change(screen.getByLabelText(/contraseña/i), { target: { value: password } });
    await userEvent.click(screen.getByRole('button', { name: /registrarse|ingresar/i }));
  }

  /** Helper: cambia a la pestaña de login usando userEvent (envuelto en act). */
  async function switchToLogin() {
    await userEvent.click(screen.getByRole('tab', { name: /iniciar sesión/i }));
  }

  it('renderiza el modo registro por defecto y permite cambiar a login', () => {
    render(<AuthPage />);

    expect(screen.getByRole('heading', { name: /crear cuenta/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /registrarse/i })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('tab', { name: /iniciar sesión/i }));

    expect(screen.getByRole('heading', { name: /iniciar sesión/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /ingresar/i })).toBeInTheDocument();
  });

  // ───── REGISTRO — VALIDACIÓN DE CAMPOS (Story 0) ───────────────────────────

  it('registro: bloquea el envío y muestra error si el email está vacío', async () => {
    const fetchMock = vi.fn();
    global.fetch = fetchMock;

    render(<AuthPage />);

    await fillAndSubmit('', 'passwordSegura123');

    expect(await screen.findByText(/el email es requerido/i)).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('registro: bloquea el envío y muestra error si la contraseña está vacía', async () => {
    const fetchMock = vi.fn();
    global.fetch = fetchMock;

    render(<AuthPage />);

    await fillAndSubmit('usuario@test.com', '');

    expect(await screen.findByText(/la contraseña es requerida/i)).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('registro: bloquea el envío y muestra error si la contraseña tiene menos de 8 caracteres', async () => {
    const fetchMock = vi.fn();
    global.fetch = fetchMock;

    render(<AuthPage />);

    // 7 caracteres — insuficiente para la política mínima (Story 0)
    await fillAndSubmit('usuario@test.com', '1234567');

    expect(
      await screen.findByText(/la contraseña debe tener al menos 8 caracteres/i)
    ).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  // ───── REGISTRO — ENVÍO EXITOSO (Story 0) ─────────────────────────────────

  it('registro: envía POST a /auth/registro con credentials include y muestra mensaje de éxito local', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 201,
      // Contrato real: RegistroResponseDto (id, email, rol, saldoDisponible, createdAt) — sin `mensaje`
      json: async () => ({
        id: 1,
        email: 'nuevo@test.com',
        rol: 'USUARIO',
        saldoDisponible: 0,
        createdAt: '2026-08-01T00:00:00Z'
      })
    });
    global.fetch = fetchMock;

    render(<AuthPage />);

    await fillAndSubmit('nuevo@test.com', 'password123');

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/auth/registro', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ email: 'nuevo@test.com', password: 'password123' })
      });
    });

    // El backend no devuelve `mensaje`; el éxito se muestra con mensaje local
    expect(
      await screen.findByText(/registro exitoso/i)
    ).toBeInTheDocument();
  });

  // ───── REGISTRO — ERROR DEL SERVIDOR ──────────────────────────────────────

  it('registro: muestra el mensaje del backend cuando responde con error (ej. 409 email duplicado)', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 409,
      json: async () => ({ mensaje: 'El email ya está registrado' })
    });
    global.fetch = fetchMock;

    render(<AuthPage />);

    await fillAndSubmit('existe@test.com', 'password123');

    expect(
      await screen.findByText(/el email ya está registrado/i)
    ).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  // ───── LOGIN — VALIDACIÓN DE CAMPOS (Story 0b) ────────────────────────────

  it('login: bloquea el envío y muestra error si el email está vacío', async () => {
    const fetchMock = vi.fn();
    global.fetch = fetchMock;

    render(<AuthPage />);
    await switchToLogin();

    await fillAndSubmit('', 'password123');

    expect(await screen.findByText(/el email es requerido/i)).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('login: bloquea el envío y muestra error si la contraseña está vacía', async () => {
    const fetchMock = vi.fn();
    global.fetch = fetchMock;

    render(<AuthPage />);
    await switchToLogin();

    await fillAndSubmit('usuario@test.com', '');

    expect(await screen.findByText(/la contraseña es requerida/i)).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  // ───── LOGIN — ENVÍO EXITOSO + REDIRECCIÓN (Story 0b) ─────────────────────

  it('login exitoso: envía POST a /auth/login con credentials include y redirige a /publicaciones', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      // Contrato real del login: body con `mensaje`
      json: async () => ({ mensaje: 'Inicio de sesión exitoso' })
    });
    global.fetch = fetchMock;

    render(<AuthPage />);
    await switchToLogin();

    await fillAndSubmit('usuario@test.com', 'password123');

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ email: 'usuario@test.com', password: 'password123' })
      });
    });

    // Redirect post-login a /publicaciones (decisión confirmada por Lino, plan.md)
    await waitFor(() => {
      expect(mockPush).toHaveBeenCalledWith('/publicaciones');
    });
  });

  // ───── LOGIN — FALLO: MENSAJE GENÉRICO (Story 0b) ─────────────────────────

  it('login fallido: muestra el mensaje genérico del backend y NO redirige', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 401,
      // Story 0b: mensaje genérico, no revela si falló email o contraseña
      json: async () => ({ mensaje: 'Credenciales inválidas' })
    });
    global.fetch = fetchMock;

    render(<AuthPage />);
    await switchToLogin();

    await fillAndSubmit('noexiste@test.com', 'passwordErrada');

    expect(
      await screen.findByText(/credenciales inválidas/i)
    ).toBeInTheDocument();

    // No debe redirigir ante un fallo de autenticación
    expect(mockPush).not.toHaveBeenCalled();
  });

  // ───── ESTADO DE ENVÍO — DOBLE SUBMIT ─────────────────────────────────────

  it('deshabilita el botón de submit mientras la petición está en curso (evita doble submit)', async () => {
    let resolveFetch!: (value: Response) => void;
    const pending = new Promise<Response>((r) => {
      resolveFetch = r;
    });

    const fetchMock = vi.fn().mockReturnValueOnce(pending);
    global.fetch = fetchMock;

    render(<AuthPage />);

    await fillAndSubmit('usuario@test.com', 'password123');

    // Mientras la promesa está pendiente, el botón debe estar deshabilitado
    const submitButton = screen.getByRole('button', { name: /procesando/i });
    expect(submitButton).toBeDisabled();

    // Resolver la promesa para liberar el mock (evita state updates fuera de act)
    await waitFor(async () => {
      resolveFetch({
        ok: true,
        status: 200,
        json: async () => ({ mensaje: 'Inicio de sesión exitoso' })
      } as Response);
      await pending;
    });
  });
});
