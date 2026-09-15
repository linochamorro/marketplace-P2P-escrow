'use client';

import DetalleTransaccion, { type DetalleTransaccionProps } from '../../transacciones/TransaccionesUI';

/**
 * Ruta dinámica de una venta compatible con params prometidos de Next.js 16.
 *
 * @param props parámetros dinámicos de la transacción
 * @returns detalle con pertenencia de vendedor verificada
 */
export default function VentaDetallePage({ params }: Pick<DetalleTransaccionProps, 'params'>) {
  return <DetalleTransaccion params={params} flujo="ventas" />;
}
