'use client';

import { useRouter } from 'next/navigation';
import { useState, type FormEvent, type KeyboardEvent } from 'react';

/**
 * Errores de validación y de servidor del formulario de autenticación.
 * Cada campo opcional corresponde a un error asociado a ese campo;
 * `general` agrupa errores del backend o de red.
 */
interface AuthFormErrors {
  email?: string;
  password?: string;
  general?: string;
}

/**
 * AuthPage — Página de autenticación de EasyMarket (PHA01TSK10).
 *
 * Formulario único con pestañas conmutables entre registro y login.
 * Traza a las Stories 0 y 0b de spec.md:
 *
 * - Story 0 (registro): política de contraseña mínima de 8 caracteres,
 *   rechazo de email duplicado (mensaje del backend), sin auto-login.
 * - Story 0b (login): cookie de sesión httpOnly `jwt` manejada por el
 *   navegador (el frontend nunca la lee ni la almacena, solo envía
 *   `credentials: 'include'`), redirección post-login a `/publicaciones`
 *   (decisión confirmada por Lino en plan.md, sección "Navegación frontend")
 *   y mensaje genérico ante credenciales inválidas (no revela cuál dato
 *   falló, para prevenir enumeración de usuarios).
 *
 * Contrato real del backend (verificado en `AuthController`):
 * - `POST {NEXT_PUBLIC_API_URL}/auth/registro` → 201 + `RegistroResponseDto`
 *   (SIN campo `mensaje`; el éxito se comunica con mensaje local).
 * - `POST {NEXT_PUBLIC_API_URL}/auth/login` → 200 + `Set-Cookie: jwt`
 *   (httpOnly; Secure; SameSite=None) + body `{"mensaje": "..."}`.
 * - Errores del backend llegan con campo `mensaje` (GlobalExceptionHandler).
 *
 * @returns Elemento JSX de la página de autenticación.
 */
export default function AuthPage() {
  const router = useRouter();

  /** Pestaña activa del formulario: `registro` (por defecto) o `login`. */
  const [activeTab, setActiveTab] = useState<'registro' | 'login'>('registro');
  /** Valor del campo email. */
  const [email, setEmail] = useState<string>('');
  /** Valor del campo contraseña. */
  const [password, setPassword] = useState<string>('');
  /** Errores de validación local y de servidor. */
  const [errors, setErrors] = useState<AuthFormErrors>({});
  /** Mensaje de éxito mostrado tras un registro exitoso. */
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  /** Indica si hay una petición en curso (deshabilita el formulario). */
  const [isSubmitting, setIsSubmitting] = useState<boolean>(false);

  /**
   * Cambia la pestaña activa y limpia el estado del formulario.
   *
   * @param tab Pestaña destino (`registro` o `login`).
   */
  const switchTab = (tab: 'registro' | 'login') => {
    setActiveTab(tab);
    setErrors({});
    setSuccessMessage(null);
    setEmail('');
    setPassword('');
  };

  /**
   * Valida los campos del cliente según la pestaña activa.
   *
   * - Registro (Story 0): email requerido; contraseña requerida y con
   *   al menos 8 caracteres (política mínima).
   * - Login (Story 0b): email y contraseña requeridos (la validez de las
   *   credenciales la decide el backend, que responde con mensaje genérico).
   *
   * @returns `true` si no hay errores de validación; `false` en caso contrario.
   */
  const validate = (): boolean => {
    const newErrors: AuthFormErrors = {};

    if (!email.trim()) {
      newErrors.email = 'El email es requerido';
    }

    if (!password) {
      newErrors.password = 'La contraseña es requerida';
    } else if (activeTab === 'registro' && password.length < 8) {
      newErrors.password = 'La contraseña debe tener al menos 8 caracteres';
    }

    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  /**
   * Envía la petición POST al endpoint correspondiente a la pestaña activa.
   *
   * Registro: `POST /auth/registro` con `credentials: 'include'`; en éxito
   * muestra un mensaje local (el backend no devuelve `mensaje` en el body 201).
   * Login: `POST /auth/login` con `credentials: 'include'`; en éxito redirige
   * a `/publicaciones` (la cookie httpOnly `jwt` ya fue seteada por el backend).
   * En ambos casos, ante una respuesta no-OK se muestra el campo `mensaje`
   * devuelto por el backend, o un fallback con el código de estado.
   */
  const doSubmit = async () => {
    setErrors({});
    setSuccessMessage(null);
    setIsSubmitting(true);

    const baseUrl = process.env.NEXT_PUBLIC_API_URL || '';
    const endpoint =
      activeTab === 'registro'
        ? `${baseUrl}/auth/registro`
        : `${baseUrl}/auth/login`;

    try {
      const response = await fetch(endpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ email: email.trim(), password })
      });

      if (!response.ok) {
        const data = await response.json().catch(() => ({}));
        // Story 0b: el backend responde con mensaje genérico; se muestra tal cual.
        setErrors({
          general: data?.mensaje || `Error del servidor (${response.status})`
        });
      } else if (activeTab === 'registro') {
        // 201 Created: el body es un RegistroResponseDto sin `mensaje`.
        setSuccessMessage('Registro exitoso. Ya puedes iniciar sesión.');
        setEmail('');
        setPassword('');
      } else {
        // Login exitoso: la cookie httpOnly `jwt` ya quedó en el navegador.
        router.push('/publicaciones');
      }
    } catch {
      setErrors({ general: 'Error de red al conectar con el servidor' });
    } finally {
      setIsSubmitting(false);
    }
  };

  /**
   * Maneja el submit del formulario: previene el envío si ya hay una petición
   * en curso (doble submit) y solo dispara la petición si la validación local
   * es exitosa.
   *
   * @param e Evento de submit del formulario.
   */
  const handleSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (isSubmitting) return;
    if (validate()) {
      void doSubmit();
    }
  };

  /**
   * Previene el submit por Enter mientras hay una petición en curso.
   *
   * @param e Evento de teclado del formulario.
   */
  const handleKeyDown = (e: KeyboardEvent<HTMLFormElement>) => {
    if (e.key === 'Enter' && isSubmitting) {
      e.preventDefault();
    }
  };

  // Clases base de las pestañas (DESIGN.md: primario Deep Navy #0F172A).
  const tabBase =
    'flex-1 py-2.5 text-sm font-semibold text-center rounded transition-colors border';
  const activeTabClass = 'bg-[#0F172A] text-white border-slate-900';
  const inactiveTabClass = 'bg-white text-slate-600 border-slate-200 hover:bg-slate-50';

  return (
    <div className="min-h-screen flex items-center justify-center bg-[#F8FAFC] px-4">
      <div className="w-full max-w-md bg-white border border-slate-200 rounded-lg p-6 space-y-6">
        {/* Título de la marca */}
        <div className="text-center">
          <h1 className="text-2xl font-bold text-[#0F172A] tracking-tight">
            EasyMarket
          </h1>
          <p className="text-sm text-slate-500 mt-1">
            Marketplace con escrow seguro
          </p>
        </div>

        {/* Pestañas registro / login */}
        <div className="flex gap-1 bg-slate-100 rounded p-0.5" role="tablist">
          <button
            type="button"
            role="tab"
            aria-selected={activeTab === 'registro'}
            onClick={() => switchTab('registro')}
            className={`${tabBase} ${
              activeTab === 'registro' ? activeTabClass : inactiveTabClass
            }`}
          >
            Crear cuenta
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={activeTab === 'login'}
            onClick={() => switchTab('login')}
            className={`${tabBase} ${
              activeTab === 'login' ? activeTabClass : inactiveTabClass
            }`}
          >
            Iniciar sesión
          </button>
        </div>

        {/* Encabezado según la pestaña activa */}
        <h2 className="text-lg font-semibold text-[#0F172A]">
          {activeTab === 'registro' ? 'Crear cuenta' : 'Iniciar sesión'}
        </h2>

        {/* Mensaje de éxito (solo registro; Emerald #10B981 según DESIGN.md) */}
        {successMessage && (
          <div className="p-3 bg-emerald-50 border border-emerald-500 text-emerald-800 rounded text-sm font-medium">
            {successMessage}
          </div>
        )}

        {/* Error general (servidor o red) */}
        {errors.general && (
          <div className="p-3 bg-[#ffdad6] border border-[#ba1a1a] text-[#93000a] rounded text-sm font-medium">
            {errors.general}
          </div>
        )}

        <form onSubmit={handleSubmit} onKeyDown={handleKeyDown} className="space-y-4">
          {/* Campo email */}
          <div>
            <label
              htmlFor="email"
              className="block text-sm font-semibold text-slate-700 mb-1"
            >
              Email
            </label>
            <input
              id="email"
              type="email"
              placeholder="tucorreo@email.com"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              disabled={isSubmitting}
              className={`w-full px-3 py-2 bg-white border rounded text-sm text-[#0F172A] focus:outline-none focus:ring-2 focus:ring-slate-200 focus:border-[#0F172A] ${
                errors.email ? 'border-red-500' : 'border-slate-300'
              }`}
            />
            {errors.email && (
              <p className="mt-1 text-xs text-red-600">{errors.email}</p>
            )}
          </div>

          {/* Campo contraseña */}
          <div>
            <label
              htmlFor="password"
              className="block text-sm font-semibold text-slate-700 mb-1"
            >
              Contraseña
            </label>
            <input
              id="password"
              type="password"
              placeholder={
                activeTab === 'registro' ? 'Mínimo 8 caracteres' : 'Ingresa tu contraseña'
              }
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              disabled={isSubmitting}
              className={`w-full px-3 py-2 bg-white border rounded text-sm text-[#0F172A] focus:outline-none focus:ring-2 focus:ring-slate-200 focus:border-[#0F172A] ${
                errors.password ? 'border-red-500' : 'border-slate-300'
              }`}
            />
            {errors.password && (
              <p className="mt-1 text-xs text-red-600">{errors.password}</p>
            )}
          </div>

          {/* Botón de submit */}
          <button
            type="submit"
            disabled={isSubmitting}
            className="w-full py-2.5 px-4 bg-[#0F172A] hover:bg-slate-800 text-white font-medium rounded text-sm transition-colors border border-slate-900 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {isSubmitting
              ? 'Procesando...'
              : activeTab === 'registro'
                ? 'Registrarse'
                : 'Ingresar'}
          </button>
        </form>
      </div>
    </div>
  );
}
