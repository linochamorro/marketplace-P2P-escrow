import { ListadoTransacciones } from '../transacciones/TransaccionesUI';

/**
 * @file compras/page.tsx
 * @description Ruta `/compras` del flujo real de compras PHA06TSK12.
 */

/**
 * Página de compras del actor autenticado.
 *
 * @returns lista conectada al flujo canónico de compras
 */
export default function ComprasPage() {
  return <ListadoTransacciones flujo="compras" />;
}
