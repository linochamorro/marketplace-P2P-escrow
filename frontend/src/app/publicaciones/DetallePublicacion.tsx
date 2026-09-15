'use client';

import { Suspense, use, useEffect, useState } from 'react';
import CompraButton from '../CompraButton';
import {
  cargarPublicacionDetalle,
  formatearPrecioSoles,
  type PublicacionListado
} from './publicaciones-utils';

/** Parámetros dinámicos de la ruta de detalle conforme a Next.js 16. */
export interface DetallePublicacionParams {
  /** Segmento textual recibido desde `/publicaciones/[id]`. */
  id: string;
}

/** Props públicas del detalle cargado desde el segmento dinámico. */
export interface DetallePublicacionProps {
  /** Promise de parámetros entregada por App Router en Next.js 16. */
  params: Promise<DetallePublicacionParams>;
  /** Transporte opcional para aislar la lectura real en pruebas de componente. */
  obtenerPublicacion?: (url: string) => Promise<PublicacionListado>;
}

/** Props internas que ya contienen el segmento resuelto por React. */
interface DetalleContenidoProps {
  /** Segmento textual sin convertir ni truncar. */
  id: string;
  /** Transporte de detalle real o inyectado. */
  obtenerPublicacion: (url: string) => Promise<PublicacionListado>;
}

/** Props internas con transporte obligatorio para resolver la Promise de parámetros. */
interface DetalleConParamsProps {
  /** Promise de parámetros entregada por App Router. */
  params: Promise<DetallePublicacionParams>;
  /** Transporte ya resuelto por el componente público. */
  obtenerPublicacion: (url: string) => Promise<PublicacionListado>;
}

/**
 * Convierte exclusivamente una cadena decimal positiva y segura en ID numérico.
 *
 * @param segmento valor literal capturado por la ruta dinámica
 * @returns entero positivo seguro, o `null` sin truncar entradas inválidas
 */
function validarPublicacionId(segmento: string): number | null {
  if (!/^[1-9]\d*$/.test(segmento)) return null;
  const id = Number(segmento);
  return Number.isSafeInteger(id) ? id : null;
}

/**
 * Resuelve la lectura del detalle y renderiza estados explícitos de error o éxito.
 *
 * <p>La rama de identificador inválido se deriva durante el render con un early return
 * (mismo mensaje, rol y marcado accesible que verifica el test previo), por lo que el
 * efecto solo consulta IDs válidos. Todos sus setState ocurren en callbacks asíncronos
 * o tras el primer await, y el estado inicial ya es {@code cargando}; el remount por
 * {@code key} en {@link DetalleConParams} limpia datos previos al navegar de A a B. No
 * se viola la regla {@code react-hooks/set-state-in-effect}.</p>
 *
 * @param props segmento ya resuelto y transporte de la publicación
 * @returns datos autorizados, compra integrada o un error honesto
 */
function DetalleContenido({ id, obtenerPublicacion }: DetalleContenidoProps) {
  const publicacionId = validarPublicacionId(id);
  const [publicacion, setPublicacion] = useState<PublicacionListado | null>(null);
  const [errorMensaje, setErrorMensaje] = useState<string | null>(null);
  const [cargando, setCargando] = useState<boolean>(publicacionId !== null);

  useEffect(() => {
    if (publicacionId === null) return;
    let activo = true;
    const url = `${process.env.NEXT_PUBLIC_API_URL || ''}/publicaciones/${publicacionId}`;
    obtenerPublicacion(url)
      .then((resultado) => {
        if (activo) setPublicacion(resultado);
      })
      .catch((error: unknown) => {
        if (activo) {
          setErrorMensaje(error instanceof Error ? error.message : 'Error de red al cargar la publicación.');
        }
      })
      .finally(() => {
        if (activo) setCargando(false);
      });
    return () => {
      activo = false;
    };
  }, [obtenerPublicacion, publicacionId]);

  if (publicacionId === null) {
    return <div role="alert" className="rounded border border-[#ba1a1a] bg-[#ffdad6] p-4 font-medium text-[#93000a]">El identificador de la publicación no es válido.</div>;
  }
  if (cargando) {
    return <div role="status" aria-busy="true" className="rounded-lg border border-slate-200 bg-white p-6 text-slate-600">Cargando publicación...</div>;
  }
  if (errorMensaje || !publicacion) {
    return <div role="alert" className="rounded border border-[#ba1a1a] bg-[#ffdad6] p-4 font-medium text-[#93000a]">{errorMensaje || 'No se pudo cargar la publicación.'}</div>;
  }

  return (
    <div className="grid grid-cols-1 gap-6 lg:grid-cols-12">
      <article className="space-y-6 rounded-lg border border-slate-200 bg-white p-6 lg:col-span-7">
        <header className="space-y-3 border-b border-slate-200 pb-6">
          <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">Publicación disponible</p>
          <h1 className="text-3xl font-bold tracking-tight text-[#0F172A]">{publicacion.descripcion}</h1>
        </header>
        <dl className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <div className="rounded border border-slate-200 p-4">
            <dt className="text-xs font-semibold uppercase tracking-wider text-slate-500">Precio</dt>
            <dd className="mt-2 font-mono text-2xl font-semibold text-[#0F172A]">{formatearPrecioSoles(publicacion.precio)}</dd>
          </div>
          <div className="rounded border border-slate-200 p-4">
            <dt className="text-xs font-semibold uppercase tracking-wider text-slate-500">Disponibilidad</dt>
            <dd className="mt-2 text-base font-semibold text-[#0F172A]">Stock disponible: {publicacion.stock}</dd>
          </div>
        </dl>
        <aside className="rounded-lg border border-emerald-200 bg-emerald-50 p-4 text-sm text-emerald-900">
          EasyMarket mantiene los fondos en escrow durante la operación y los libera según el flujo confirmado de entrega.
        </aside>
      </article>
      <div className="lg:col-span-5">
        <CompraButton publicacionId={publicacionId} />
      </div>
    </div>
  );
}

/**
 * Detalle cliente que resuelve `params` como Promise mediante React `use`, según Next.js 16.
 *
 * @param props parámetros dinámicos y transporte opcional
 * @returns composición institucional con fallback de carga y contenido real
 */
export default function DetallePublicacion({
  params,
  obtenerPublicacion = cargarPublicacionDetalle
}: DetallePublicacionProps) {
  return (
    <main className="min-h-screen bg-[#F8FAFC] px-4 py-8 sm:px-5 lg:px-6">
      <div className="mx-auto max-w-[1280px]">
        <Suspense fallback={<div role="status" aria-busy="true" className="rounded-lg border border-slate-200 bg-white p-6 text-slate-600">Cargando publicación...</div>}>
          <DetalleConParams params={params} obtenerPublicacion={obtenerPublicacion} />
        </Suspense>
      </div>
    </main>
  );
}

/**
 * Lee la Promise de App Router dentro de la frontera Suspense y delega la consulta.
 *
 * @param props parámetros prometidos y transporte seleccionado
 * @returns contenido de detalle con el segmento literal resuelto
 */
function DetalleConParams({ params, obtenerPublicacion }: DetalleConParamsProps) {
  const { id } = use(params);
  return <DetalleContenido key={id} id={id} obtenerPublicacion={obtenerPublicacion} />;
}
