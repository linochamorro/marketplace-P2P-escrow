import { ListadoTransacciones } from '../transacciones/TransaccionesUI';

/**
 * @file ventas/page.tsx
 * @description Ruta `/ventas` del flujo real de ventas PHA06TSK12.
 */

/**
 * Página de ventas del actor autenticado.
 *
 * @returns lista conectada al flujo canónico de ventas
 */
export default function VentasPage() {
  return <ListadoTransacciones flujo="ventas" />;
}
