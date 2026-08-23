import { UsuariosBloqueadosAdmin } from '../AdminUI';

/**
 * @file admin/usuarios-bloqueados/page.tsx
 * @description Ruta `/admin/usuarios-bloqueados` integrada con lectura y desbloqueo reales.
 */

/**
 * Monta la lista real de bloqueos permanentes y su acción de desbloqueo.
 *
 * @returns gestión de desbloqueos de Story 0c
 */
export default function UsuariosBloqueadosPage() {
  return <UsuariosBloqueadosAdmin />;
}
