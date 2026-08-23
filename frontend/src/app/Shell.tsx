'use client';

import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import { createContext, useContext, useEffect, useState } from 'react';
import { cargarUsuarioActual, cerrarSesion, type UsuarioActualUI } from './sesion-utils';

/**
 * @file Shell.tsx
 * @description Shell de navegación sensible a sesión y rol (PHA06TSK09) con
 * acción de cierre de sesión integrada en la barra (PHA07TSK03) y menú lateral
 * (sidebar) accesible mediante botón hamburguesa colapsado por defecto.
 *
 * Marca clicable (PHA09TSK01): el nombre "EasyMarket" es un enlace a
 * `/publicaciones` en sus dos ubicaciones — header superior izquierdo y
 * cabecera del sidebar. En el header solo es enlace con sesión autenticada;
 * mientras la sesión resuelve (estado `cargando`) o se cierra se conserva
 * como texto plano, para no exponer navegación antes de tener identidad
 * (contrato de PHA06TSK09). La identidad visual es idéntica: mismas clases
 * de tipografía y color, sin subrayado (preflight de Tailwind).
 *
 * Integrado desde el layout raíz: envuelve a {@link children} en TODAS las
 * rutas, pero solo se materializa en las rutas autenticadas del mapa de la
 * tarea (ver {@link esRutaConShell}). En `/` y `/auth` pasa los children sin
 * modificar, de modo que el formulario de login y la landing conservan su
 * presentación propia.
 *
 * Comportamiento en rutas autenticadas:
 * - Al montar consulta `GET /usuarios/me` (patrón inyectable para test).
 * - Mientras resuelve muestra la marca y "Cargando sesión…", sin enlaces ni
 *   contenido protegido.
 * - Con sesión válida renderiza el header limpio con botón hamburguesa y un
 *   sidebar organizado y agrupado por tipo de acción (Explorar, Publicaciones,
 *   Transacciones, Mi Cuenta y Administración si aplica).
 * - Con sesión fallida redirige a `/auth` vía `router.replace`.
 *
 * La acción de logout (PHA07TSK03): ejecuta `POST /auth/logout` con
 * `credentials: 'include'`; la cookie httpOnly `jwt` la expira el `Set-Cookie`
 * del backend (sin invalidación server-side — exclusión de spec.md Story 0b),
 * por lo que el frontend solo limpia su estado local y redirige. Mientras la
 * petición corre, el estado intermedio `'cerrando-sesion'` retira del DOM el
 * contenido protegido ("sin dejar contenido protegido operativo"); ante fallo
 * (red o HTTP no-2xx) se conserva la sesión local, no se redirige y se muestra
 * un mensaje de error en la barra.
 */

/** Destinos de navegación visibles para el rol USUARIO (contrato PHA06TSK09). */
export const DESTINOS_USUARIO = [
  { etiqueta: 'Mercado', ruta: '/publicaciones' },
  { etiqueta: 'Publicar', ruta: '/publicar' },
  { etiqueta: 'Mis publicaciones', ruta: '/mis-publicaciones' },
  { etiqueta: 'Compras', ruta: '/compras' },
  { etiqueta: 'Ventas', ruta: '/ventas' },
  { etiqueta: 'Notificaciones', ruta: '/notificaciones' },
  { etiqueta: 'Saldo', ruta: '/saldo' },
] as const;

/** Destinos de navegación visibles solo para el rol ADMIN (contrato PHA06TSK09). */
export const DESTINOS_ADMIN = [
  { etiqueta: 'Administración', ruta: '/admin' },
  { etiqueta: 'Moderación', ruta: '/admin/moderacion' },
  { etiqueta: 'Categorías', ruta: '/admin/categorias' },
  { etiqueta: 'Disputas', ruta: '/admin/disputas' },
  { etiqueta: 'Cuentas bloqueadas', ruta: '/admin/usuarios-bloqueados' },
] as const;

/** Definición de grupo de navegación para el sidebar. */
export interface GrupoNavegacion {
  /** Nombre o categoría del grupo. */
  titulo: string;
  /** Enlaces pertenecientes al grupo. */
  destinos: Array<{ etiqueta: string; ruta: string }>;
}

/** Grupos de acciones para usuarios estándar. */
export const GRUPOS_USUARIO: readonly GrupoNavegacion[] = [
  {
    titulo: 'Explorar',
    destinos: [{ etiqueta: 'Mercado', ruta: '/publicaciones' }],
  },
  {
    titulo: 'Publicaciones',
    destinos: [
      { etiqueta: 'Publicar', ruta: '/publicar' },
      { etiqueta: 'Mis publicaciones', ruta: '/mis-publicaciones' },
    ],
  },
  {
    titulo: 'Transacciones',
    destinos: [
      { etiqueta: 'Compras', ruta: '/compras' },
      { etiqueta: 'Ventas', ruta: '/ventas' },
    ],
  },
  {
    titulo: 'Mi Cuenta',
    destinos: [
      { etiqueta: 'Notificaciones', ruta: '/notificaciones' },
      { etiqueta: 'Saldo', ruta: '/saldo' },
    ],
  },
] as const;

/** Grupo de acciones exclusivas para el rol ADMIN. */
export const GRUPO_ADMIN: GrupoNavegacion = {
  titulo: 'Administración',
  destinos: [
    { etiqueta: 'Administración', ruta: '/admin' },
    { etiqueta: 'Moderación', ruta: '/admin/moderacion' },
    { etiqueta: 'Categorías', ruta: '/admin/categorias' },
    { etiqueta: 'Disputas', ruta: '/admin/disputas' },
    { etiqueta: 'Cuentas bloqueadas', ruta: '/admin/usuarios-bloqueados' },
  ],
};

/** Rutas de usuario del mapa de la tarea (sin el prefijo administrativo). */
const RUTAS_USUARIO: readonly string[] = [
  '/publicaciones',
  '/publicar',
  '/mis-publicaciones',
  '/compras',
  '/ventas',
  '/notificaciones',
  '/saldo',
];

/**
 * Decide si una ruta del router lleva el shell de navegación.
 *
 * @param pathname Ruta actual devuelta por `usePathname()`.
 * @returns `true` si la ruta pertenece al mapa autenticado de la tarea
 *   (incluidos detalles de compras/ventas y cualquier submódulo bajo
 *   `/admin`); `false` para la home y la autenticación.
 */
export function esRutaConShell(pathname: string): boolean {
  if (pathname === '/admin' || pathname.startsWith('/admin/')) {
    return true;
  }
  return RUTAS_USUARIO.includes(pathname)
    || pathname.startsWith('/compras/')
    || pathname.startsWith('/ventas/');
}

/** Propiedades del shell de navegación. */
export interface ShellProps {
  /** Contenido de la página envuelta por el shell. */
  children: React.ReactNode;
  /**
   * Cargador de la identidad de sesión, inyectable para test.
   *
   * @param url URL completa de `usuarios/me`.
   * @returns La identidad del usuario autenticado.
   */
  obtenerUsuarioActual?: (url: string) => Promise<UsuarioActualUI>;
}

/** Estados del ciclo de vida del shell en una ruta autenticada. */
type EstadoSesion = 'cargando' | 'autenticado' | 'sin-sesion' | 'cerrando-sesion';

/** Identidad compartida por el shell con las vistas protegidas descendientes. */
const UsuarioSesionContext = createContext<UsuarioActualUI | null>(null);

/**
 * Obtiene la identidad ya resuelta por el shell sin volver a consultar la API.
 *
 * @returns identidad autenticada o `null` fuera de una rama autenticada
 */
export function useUsuarioSesion(): UsuarioActualUI | null {
  return useContext(UsuarioSesionContext);
}

/**
 * Renderiza el ícono correspondiente para cada etiqueta de destino.
 *
 * @param etiqueta Nombre legible del destino.
 * @returns Elemento SVG con el ícono representativo.
 */
function IconoDestino({ etiqueta }: { etiqueta: string }) {
  switch (etiqueta) {
    case 'Mercado':
      return (
        <svg className="h-4 w-4 shrink-0" fill="none" viewBox="0 0 24 24" strokeWidth="1.75" stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" d="M13.5 21v-7.5a.75.75 0 0 1 .75-.75h3a.75.75 0 0 1 .75.75V21m-4.5 0H2.25A2.25 2.25 0 0 1 0 18.75V6.75A2.25 2.25 0 0 1 2.25 4.5h19.5A2.25 2.25 0 0 1 24 6.75v12a2.25 2.25 0 0 1-2.25 2.25H13.5Zm-9-13.5h15" />
        </svg>
      );
    case 'Publicar':
      return (
        <svg className="h-4 w-4 shrink-0" fill="none" viewBox="0 0 24 24" strokeWidth="1.75" stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v6m3-3H9m12 0a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z" />
        </svg>
      );
    case 'Mis publicaciones':
      return (
        <svg className="h-4 w-4 shrink-0" fill="none" viewBox="0 0 24 24" strokeWidth="1.75" stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" d="M3.75 6A2.25 2.25 0 0 1 6 3.75h2.25A2.25 2.25 0 0 1 10.5 6v2.25a2.25 2.25 0 0 1-2.25 2.25H6a2.25 2.25 0 0 1-2.25-2.25V6ZM3.75 15.75A2.25 2.25 0 0 1 6 13.5h2.25a2.25 2.25 0 0 1 2.25 2.25V18a2.25 2.25 0 0 1-2.25 2.25H6A2.25 2.25 0 0 1 3.75 18v-2.25ZM13.5 6a2.25 2.25 0 0 1 2.25-2.25H18A2.25 2.25 0 0 1 20.25 6v2.25A2.25 2.25 0 0 1 18 10.5h-2.25a2.25 2.25 0 0 1-2.25-2.25V6ZM13.5 15.75a2.25 2.25 0 0 1 2.25-2.25H18a2.25 2.25 0 0 1 2.25 2.25V18A2.25 2.25 0 0 1 18 20.25h-2.25A2.25 2.25 0 0 1 13.5 18v-2.25Z" />
        </svg>
      );
    case 'Compras':
      return (
        <svg className="h-4 w-4 shrink-0" fill="none" viewBox="0 0 24 24" strokeWidth="1.75" stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" d="M15.75 10.5V6a3.75 3.75 0 1 0-7.5 0v4.5m11.356-1.993 1.263 12c.07.665-.45 1.243-1.119 1.243H4.25a1.125 1.125 0 0 1-1.12-1.243l1.264-12A1.125 1.125 0 0 1 5.513 7.5h12.974c.576 0 1.059.435 1.119 1.007ZM8.625 10.5a.375.375 0 1 1-.75 0 .375.375 0 0 1 .75 0Zm7.5 0a.375.375 0 1 1-.75 0 .375.375 0 0 1 .75 0Z" />
        </svg>
      );
    case 'Ventas':
      return (
        <svg className="h-4 w-4 shrink-0" fill="none" viewBox="0 0 24 24" strokeWidth="1.75" stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" d="M2.25 18.75a60.07 60.07 0 0 1 15.797 2.101c.727.198 1.453-.342 1.453-1.096V18.75M3.75 4.5v.75A.75.75 0 0 1 3 6H2.25m0 0v10.5m0-10.5h19.5m0 0v10.5m0 0a2.25 2.25 0 0 1-2.25 2.25H4.5M21.75 6v.75a.75.75 0 0 1-.75.75h-.75m0 0v10.5m-3.75-6a2.25 2.25 0 1 1-4.5 0 2.25 2.25 0 0 1 4.5 0Z" />
        </svg>
      );
    case 'Notificaciones':
      return (
        <svg className="h-4 w-4 shrink-0" fill="none" viewBox="0 0 24 24" strokeWidth="1.75" stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" d="M14.857 17.082a23.848 23.848 0 0 0 5.454-1.31A8.967 8.967 0 0 1 18 9.75V9A6 6 0 0 0 6 9v.75a8.967 8.967 0 0 1-2.312 6.022c1.733.64 3.56 1.085 5.455 1.31m5.714 0a24.255 24.255 0 0 1-5.714 0m5.714 0a3 3 0 1 1-5.714 0" />
        </svg>
      );
    case 'Saldo':
      return (
        <svg className="h-4 w-4 shrink-0" fill="none" viewBox="0 0 24 24" strokeWidth="1.75" stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" d="M21 12a2.25 2.25 0 0 0-2.25-2.25H15a3 3 0 1 1-6 0H5.25A2.25 2.25 0 0 0 3 12m18 0v6a2.25 2.25 0 0 1-2.25 2.25H5.25A2.25 2.25 0 0 1 3 18v-6m18 0V9M3 12V9m18 0a2.25 2.25 0 0 0-2.25-2.25H5.25A2.25 2.25 0 0 0 3 9m18 0V6a2.25 2.25 0 0 0-2.25-2.25H5.25A2.25 2.25 0 0 0 3 6v3" />
        </svg>
      );
    case 'Administración':
      return (
        <svg className="h-4 w-4 shrink-0" fill="none" viewBox="0 0 24 24" strokeWidth="1.75" stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" d="M10.5 6h9.75M10.5 6a1.5 1.5 0 1 1-3 0m3 0a1.5 1.5 0 1 0-3 0M3.75 6H7.5m3 12h9.75m-9.75 0a1.5 1.5 0 0 1-3 0m3 0a1.5 1.5 0 0 0-3 0m-3.75 0H7.5m9-6h3.75m-3.75 0a1.5 1.5 0 0 1-3 0m3 0a1.5 1.5 0 0 0-3 0m-9.75 0h9.75" />
        </svg>
      );
    case 'Moderación':
      return (
        <svg className="h-4 w-4 shrink-0" fill="none" viewBox="0 0 24 24" strokeWidth="1.75" stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" d="M9 12.75 11.25 15 15 9.75m-3-7.036A11.959 11.959 0 0 1 3.598 6 11.99 11.99 0 0 0 3 9.749c0 5.592 3.824 10.29 9 11.623 5.176-1.332 9-6.03 9-11.622 0-1.31-.21-2.571-.598-3.751h-.152c-3.196 0-6.1-1.248-8.25-3.285Z" />
        </svg>
      );
    case 'Categorías':
      return (
        <svg className="h-4 w-4 shrink-0" fill="none" viewBox="0 0 24 24" strokeWidth="1.75" stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" d="M9.568 3H5.25A2.25 2.25 0 0 0 3 5.25v4.318c0 .597.237 1.17.659 1.591l9.581 9.581c.699.699 1.78.872 2.607.33a18.095 18.095 0 0 0 5.223-5.223c.542-.827.369-1.908-.33-2.607L11.16 3.66A2.25 2.25 0 0 0 9.568 3Z" />
          <path strokeLinecap="round" strokeLinejoin="round" d="M6 6h.008v.008H6V6Z" />
        </svg>
      );
    case 'Disputas':
      return (
        <svg className="h-4 w-4 shrink-0" fill="none" viewBox="0 0 24 24" strokeWidth="1.75" stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0 2.813-1.874 1.948-3.374L13.949 3.378c-.866-1.5-3.032-1.5-3.898 0L2.697 16.126ZM12 15.75h.007v.008H12v-.008Z" />
        </svg>
      );
    case 'Cuentas bloqueadas':
      return (
        <svg className="h-4 w-4 shrink-0" fill="none" viewBox="0 0 24 24" strokeWidth="1.75" stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" d="M18.364 18.364A9 9 0 0 0 5.636 5.636m12.728 12.728A9 9 0 0 1 5.636 5.636m12.728 12.728L5.636 5.636" />
        </svg>
      );
    default:
      return (
        <svg className="h-4 w-4 shrink-0" fill="none" viewBox="0 0 24 24" strokeWidth="1.75" stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" d="m8.25 4.5 7.5 7.5-7.5 7.5" />
        </svg>
      );
  }
}

/**
 * Shell de aplicación: barra superior limpia con menú hamburguesa colapsable
 * y sidebar con opciones agrupadas por categoría funcional.
 *
 * @param props Ver {@link ShellProps}.
 * @returns El contenido envuelto o, en rutas autenticadas, el shell de
 *   navegación más el contenido.
 */
export default function Shell({ children, obtenerUsuarioActual = cargarUsuarioActual }: ShellProps) {
  const pathname = usePathname();
  const router = useRouter();
  const conShell = esRutaConShell(pathname);
  const [estado, setEstado] = useState<EstadoSesion>('cargando');
  const [usuario, setUsuario] = useState<UsuarioActualUI | null>(null);
  /** Control de apertura/cierre del menú hamburguesa / sidebar lateral. */
  const [menuAbierto, setMenuAbierto] = useState<boolean>(false);
  /** Mensaje de error del logout (fallo de red o HTTP no-2xx); `null` sin error. */
  const [errorLogout, setErrorLogout] = useState<string | null>(null);

  useEffect(() => {
    if (!conShell) {
      return;
    }
    let activo = true;
    const url = `${process.env.NEXT_PUBLIC_API_URL ?? ''}/usuarios/me`;
    obtenerUsuarioActual(url)
      .then((identidad) => {
        if (activo) {
          setUsuario(identidad);
          setEstado('autenticado');
        }
      })
      .catch(() => {
        if (activo) {
          setEstado('sin-sesion');
        }
      });
    return () => {
      activo = false;
    };
  }, [conShell, obtenerUsuarioActual]);

  useEffect(() => {
    if (estado === 'sin-sesion') {
      router.replace('/auth');
    }
  }, [estado, router]);

  // Cierra el sidebar cuando el usuario presiona la tecla Escape
  useEffect(() => {
    const manejarEscape = (evento: KeyboardEvent) => {
      if (evento.key === 'Escape') {
        setMenuAbierto(false);
      }
    };
    if (menuAbierto) {
      window.addEventListener('keydown', manejarEscape);
    }
    return () => {
      window.removeEventListener('keydown', manejarEscape);
    };
  }, [menuAbierto]);

  // Cierra el sidebar al navegar a una nueva ruta.
  // La actualización se difiere al siguiente frame para no invocar setState
  // sincrónicamente dentro del efecto (react-hooks/set-state-in-effect).
  useEffect(() => {
    const frame = requestAnimationFrame(() => {
      setMenuAbierto(false);
    });
    return () => cancelAnimationFrame(frame);
  }, [pathname]);

  /**
   * Ejecuta la acción de cierre de sesión (PHA07TSK03).
   *
   * Llama a `POST /auth/logout` con `credentials: 'include'`. La cookie
   * httpOnly `jwt` la expira el `Set-Cookie` del backend; este frontend solo
   * limpia su estado local. Durante la petición el estado `'cerrando-sesion'`
   * retira del DOM la navegación y el contenido protegido; en éxito se limpia
   * `usuario` y se pasa a `'sin-sesion'`, cuyo efecto existente redirige a
   * `/auth` vía `router.replace`. Ante fallo se vuelve a `'autenticado'`
   * conservando la sesión local y se muestra el mensaje de error del
   * transporte (backend o red) en la barra.
   */
  const manejarLogout = async () => {
    if (estado !== 'autenticado') {
      return;
    }
    setErrorLogout(null);
    setEstado('cerrando-sesion');
    const url = `${process.env.NEXT_PUBLIC_API_URL ?? ''}/auth/logout`;
    try {
      await cerrarSesion(url);
      setUsuario(null);
      setEstado('sin-sesion');
    } catch (error) {
      setErrorLogout(error instanceof Error ? error.message : 'No se pudo cerrar la sesión');
      setEstado('autenticado');
    }
  };

  if (!conShell) {
    return <>{children}</>;
  }

  if (estado === 'sin-sesion') {
    return null;
  }

  const gruposVisibles =
    usuario?.rol === 'ADMIN'
      ? [...GRUPOS_USUARIO, GRUPO_ADMIN]
      : GRUPOS_USUARIO;

  return (
    <div className="min-h-screen flex flex-col bg-[#F8FAFC]">
      {/* Header superior limpio */}
      <header className="sticky top-0 z-30 border-b border-slate-200 bg-white/95 backdrop-blur-xs">
        <div className="mx-auto flex h-16 w-full max-w-[1280px] items-center justify-between gap-4 px-4 sm:px-6">
          <div className="flex items-center gap-3">
            {estado === 'autenticado' && (
              <button
                type="button"
                onClick={() => setMenuAbierto((prev) => !prev)}
                aria-label={menuAbierto ? 'Cerrar menú' : 'Abrir menú'}
                aria-expanded={menuAbierto}
                aria-controls="sidebar-navegacion"
                className="flex h-10 w-10 items-center justify-center rounded-md border border-slate-200 text-slate-700 hover:bg-slate-100 hover:text-slate-900 focus:outline-hidden focus:ring-2 focus:ring-slate-400"
              >
                {menuAbierto ? (
                  <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" strokeWidth="2" stroke="currentColor">
                    <path strokeLinecap="round" strokeLinejoin="round" d="M6 18 18 6M6 6l12 12" />
                  </svg>
                ) : (
                  <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" strokeWidth="2" stroke="currentColor">
                    <path strokeLinecap="round" strokeLinejoin="round" d="M3.75 6.75h16.5M3.75 12h16.5m-16.5 5.25h16.5" />
                  </svg>
                )}
              </button>
            )}
            {estado === 'autenticado' ? (
              <Link
                href="/publicaciones"
                className="text-lg font-bold tracking-tight text-[#0F172A]"
              >
                EasyMarket
              </Link>
            ) : (
              <span className="text-lg font-bold tracking-tight text-[#0F172A]">EasyMarket</span>
            )}
          </div>

          <div className="flex items-center gap-3">
            {estado === 'autenticado' ? (
              <div className="flex items-center gap-2">
                <span className="hidden sm:inline-flex items-center rounded-xs bg-slate-100 px-2.5 py-1 text-xs font-mono text-slate-600 border border-slate-200">
                  {usuario?.email}
                </span>
                <span
                  className={`inline-flex items-center rounded-xs px-2 py-0.5 text-xs font-semibold ${
                    usuario?.rol === 'ADMIN'
                      ? 'bg-purple-100 text-purple-800'
                      : 'bg-emerald-50 text-emerald-700 border border-emerald-200'
                  }`}
                >
                  {usuario?.rol}
                </span>
              </div>
            ) : (
              <span className="text-sm text-slate-500">
                {estado === 'cerrando-sesion' ? 'Cerrando sesión…' : 'Cargando sesión…'}
              </span>
            )}
          </div>
        </div>

        {errorLogout ? (
          <div
            role="alert"
            className="border-t border-[#ffdad6] bg-[#ffdad6]/40 px-6 py-2 text-sm font-medium text-[#93000a]"
          >
            {errorLogout}
          </div>
        ) : null}
      </header>

      {/* Backdrop para cerrar el sidebar al hacer clic afuera */}
      {estado === 'autenticado' && (
        <div
          role="presentation"
          onClick={() => setMenuAbierto(false)}
          className={`fixed inset-0 z-40 bg-slate-900/40 backdrop-blur-xs transition-opacity duration-200 ${
            menuAbierto ? 'opacity-100 pointer-events-auto' : 'opacity-0 pointer-events-none'
          }`}
        />
      )}

      {/* Sidebar / Cajón lateral de navegación */}
      {estado === 'autenticado' && (
        <aside
          id="sidebar-navegacion"
          aria-label="Menú lateral de navegación"
          className={`fixed inset-y-0 left-0 z-50 flex w-72 sm:w-80 flex-col bg-white border-r border-slate-200 shadow-2xl transition-transform duration-200 ease-in-out ${
            menuAbierto ? 'translate-x-0' : '-translate-x-full pointer-events-none'
          }`}
        >
          {/* Cabecera del sidebar */}
          <div className="flex h-16 items-center justify-between border-b border-slate-200 px-4">
            <div className="flex items-center gap-2">
              <Link
                href="/publicaciones"
                className="text-base font-bold tracking-tight text-[#0F172A]"
              >
                EasyMarket
              </Link>
              <span className="rounded-xs bg-emerald-50 px-1.5 py-0.5 text-[10px] font-semibold text-emerald-800 border border-emerald-200">
                Escrow P2P
              </span>
            </div>
            <button
              type="button"
              onClick={() => setMenuAbierto(false)}
              aria-label="Cerrar menú lateral"
              className="flex h-8 w-8 items-center justify-center rounded-md text-slate-500 hover:bg-slate-100 hover:text-slate-900"
            >
              <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" strokeWidth="2" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" d="M6 18 18 6M6 6l12 12" />
              </svg>
            </button>
          </div>

          {/* Navegación con categorías agrupadas */}
          <nav
            aria-label="Navegación principal"
            className="flex-1 overflow-x-auto overflow-y-auto px-3 py-4 space-y-5"
          >
            {gruposVisibles.map((grupo) => (
              <div key={grupo.titulo} className="space-y-1">
                <div className="px-3 py-1 text-[11px] font-bold uppercase tracking-wider text-slate-400">
                  {grupo.titulo}
                </div>
                <div className="space-y-0.5">
                  {grupo.destinos.map((destino) => {
                    const activo = pathname === destino.ruta;
                    return (
                      <Link
                        key={destino.ruta}
                        href={destino.ruta}
                        onClick={() => setMenuAbierto(false)}
                        className={`whitespace-nowrap shrink-0 flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium transition-colors ${
                          activo
                            ? 'bg-[#0F172A] text-white shadow-xs'
                            : 'text-slate-700 hover:bg-slate-100 hover:text-[#0F172A]'
                        }`}
                      >
                        <IconoDestino etiqueta={destino.etiqueta} />
                        <span>{destino.etiqueta}</span>
                      </Link>
                    );
                  })}
                </div>
              </div>
            ))}
          </nav>

          {/* Pie del sidebar con usuario y botón de cerrar sesión */}
          <div className="border-t border-slate-200 bg-slate-50/50 p-3 space-y-2">
            <div className="flex items-center justify-between px-2 py-1">
              <div className="truncate text-xs font-mono text-slate-600" title={usuario?.email}>
                {usuario?.email}
              </div>
              <span
                className={`text-[10px] font-semibold px-1.5 py-0.5 rounded-xs ${
                  usuario?.rol === 'ADMIN' ? 'bg-purple-100 text-purple-800' : 'bg-slate-200 text-slate-700'
                }`}
              >
                {usuario?.rol}
              </span>
            </div>
            <button
              type="button"
              onClick={() => void manejarLogout()}
              className="flex w-full items-center justify-center gap-2 rounded-md border border-slate-200 bg-white px-3 py-2 text-sm font-medium text-slate-700 transition-colors hover:bg-red-50 hover:text-[#93000a] hover:border-red-200"
            >
              <svg className="h-4 w-4 shrink-0 text-[#93000a]" fill="none" viewBox="0 0 24 24" strokeWidth="1.75" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" d="M15.75 9V5.25A2.25 2.25 0 0 0 13.5 3h-6a2.25 2.25 0 0 0-2.25 2.25v13.5A2.25 2.25 0 0 0 7.5 21h6a2.25 2.25 0 0 0 2.25-2.25V15M12 9l-3 3m0 0 3 3m-3-3h12.75" />
              </svg>
              <span>Cerrar sesión</span>
            </button>
          </div>
        </aside>
      )}

      {/* Contenido principal */}
      <main className="flex-1">
        {estado === 'autenticado' ? (
          <UsuarioSesionContext.Provider value={usuario}>{children}</UsuarioSesionContext.Provider>
        ) : null}
      </main>
    </div>
  );
}

