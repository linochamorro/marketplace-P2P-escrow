'use client';

import ListadoPublicaciones from './ListadoPublicaciones';

/**
 * Ruta `/publicaciones` de EasyMarket que entrega el listado interactivo de Story 11.
 *
 * @returns elemento cliente que consume las categorías y publicaciones reales
 */
export default function PublicacionesPage() {
  return <ListadoPublicaciones />;
}
