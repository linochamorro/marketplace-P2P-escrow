import { ModeracionAdmin } from '../AdminUI';

/**
 * @file admin/moderacion/page.tsx
 * @description Ruta `/admin/moderacion` integrada con publicaciones y catálogo reales.
 */

/**
 * Monta la moderación administrativa alimentada por publicaciones reales.
 *
 * @returns moderación administrativa de Story 2
 */
export default function ModeracionPage() {
  return <ModeracionAdmin />;
}
