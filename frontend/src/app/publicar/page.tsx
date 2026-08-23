'use client';

import { useRouter } from 'next/navigation';
import PublicacionForm from '../PublicacionForm';

/**
 * Ruta navegable de creación; reemplaza el historial al finalizar para llevar al
 * vendedor a la fuente canónica de sus publicaciones.
 *
 * @returns formulario real de publicación
 */
export default function PublicarPage() {
  const router = useRouter();
  return <main className="min-h-screen bg-[#F8FAFC] px-4 py-8"><PublicacionForm onSuccess={() => router.replace('/mis-publicaciones')} /></main>;
}
