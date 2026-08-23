'use client';

import DetalleTransaccion, { type DetalleTransaccionProps } from '../../transacciones/TransaccionesUI';

/**
 * Ruta dinámica de una compra compatible con params prometidos de Next.js 16.
 *
 * @param props parámetros dinámicos de la transacción
 * @returns detalle con pertenencia de comprador verificada
 */
export default function CompraDetallePage({ params }: Pick<DetalleTransaccionProps, 'params'>) {
  return <DetalleTransaccion params={params} flujo="compras" />;
}
