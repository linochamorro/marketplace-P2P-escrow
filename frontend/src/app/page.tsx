'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';
import { cargarUsuarioActual, type UsuarioActualUI } from './sesion-utils';

/**
 * @file page.tsx
 * @description Home `/` de EasyMarket (PHA06TSK09).
 *
 * Decide el destino inicial según la sesión (ver spec.md, story "Inicio"):
 * con sesión válida redirige al mercado (`/publicaciones`); sin sesión
 * muestra la landing EasyMarket con llamada a la acción hacia `/auth`.
 */

/** Propiedades de la home, con el cargador de sesión inyectable para test. */
export interface HomeProps {
  /**
   * Cargador de la identidad de sesión.
   *
   * @param url URL completa de `usuarios/me`.
   * @returns La identidad del usuario autenticado.
   */
  obtenerUsuarioActual?: (url: string) => Promise<UsuarioActualUI>;
}

/** Destinos de la home según el estado de sesión. */
type EstadoInicio = 'cargando' | 'con-sesion' | 'sin-sesion';

/**
 * Home de EasyMarket: redirige al mercado si hay sesión o muestra la landing.
 *
 * @param props Ver {@link HomeProps}.
 * @returns La landing de bienvenida o `null` mientras se evalúa/redirige.
 */
export default function Home({ obtenerUsuarioActual = cargarUsuarioActual }: HomeProps) {
  const router = useRouter();
  const [estado, setEstado] = useState<EstadoInicio>('cargando');

  useEffect(() => {
    let activo = true;
    const url = `${process.env.NEXT_PUBLIC_API_URL ?? ''}/usuarios/me`;
    obtenerUsuarioActual(url)
      .then(() => {
        if (activo) {
          setEstado('con-sesion');
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
  }, [obtenerUsuarioActual]);

  useEffect(() => {
    if (estado === 'con-sesion') {
      router.replace('/publicaciones');
    }
  }, [estado, router]);

  if (estado !== 'sin-sesion') {
    return null;
  }

  return (
    <main className="flex min-h-screen items-center justify-center bg-[#F8FAFC] px-6">
      <div className="w-full max-w-md text-center">
        <h1 className="text-3xl font-bold tracking-tight text-[#0F172A]">EasyMarket</h1>
        <p className="mt-3 text-sm leading-6 text-slate-600">
          Marketplace P2P con pago protegido por escrow: publica, compra y
          vende con confianza.
        </p>
        <Link
          href="/auth"
          className="mt-8 inline-block rounded-md bg-[#0F172A] px-6 py-3 text-sm font-medium text-white transition-colors hover:bg-slate-800"
        >
          Iniciar sesión / Crear cuenta
        </Link>
      </div>
    </main>
  );
}
