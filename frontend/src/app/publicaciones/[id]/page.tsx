'use client';

import DetallePublicacion, { type DetallePublicacionProps } from '../DetallePublicacion';

/**
 * Ruta dinámica `/publicaciones/[id]` compatible con la Promise de params de Next.js 16.
 *
 * @param props parámetros prometidos entregados por App Router
 * @returns detalle real que valida el segmento antes de consultar o comprar
 */
export default function PublicacionDetallePage({ params }: DetallePublicacionProps) {
  return <DetallePublicacion params={params} />;
}
