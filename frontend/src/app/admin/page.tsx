import { DashboardAdmin } from './AdminUI';

/**
 * @file admin/page.tsx
 * @description Ruta `/admin`: tablero operativo real protegido visualmente por rol.
 */

/**
 * Monta el tablero administrativo cliente.
 *
 * @returns tablero de Story 13
 */
export default function AdminPage() {
  return <DashboardAdmin />;
}
