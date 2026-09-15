import { CategoriasAdmin } from '../AdminUI';

/**
 * @file admin/categorias/page.tsx
 * @description Ruta `/admin/categorias` integrada con el CRUD real del catálogo.
 */

/**
 * Monta la gestión real de categorías y subcategorías.
 *
 * @returns gestión completa del catálogo de Story 4
 */
export default function CategoriasPage() {
  return <CategoriasAdmin />;
}
