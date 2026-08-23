/**
 * @file PaginaEnConstruccion.tsx
 * @description Página mínima compartida para las rutas del mapa de PHA06TSK09
 * que aún no tienen implementación. Muestra el título de la sección y un
 * aviso, usando los tokens de diseño de DESIGN.md (Deep Navy #0F172A, fondo
 * #F8FAFC, bordes slate-200, contenedor de 1280px).
 */

/** Propiedades de la página en construcción. */
export interface PaginaEnConstruccionProps {
  /** Título de la sección, renderizado como encabezado de nivel 1. */
  titulo: string;
}

/**
 * Renderiza el marcador de posición de una sección pendiente de
 * implementación.
 *
 * @param props Ver {@link PaginaEnConstruccionProps}.
 * @returns Tarjeta con el título y el aviso de construcción.
 */
export default function PaginaEnConstruccion({ titulo }: PaginaEnConstruccionProps) {
  return (
    <main className="flex min-h-screen items-start justify-center bg-[#F8FAFC] px-6 py-12">
      <div className="w-full max-w-[1280px]">
        <div className="rounded-lg border border-slate-200 bg-white p-8">
          <h1 className="text-2xl font-bold tracking-tight text-[#0F172A]">{titulo}</h1>
          <p className="mt-3 text-sm text-slate-600">
            Sección en construcción. Disponible en una próxima entrega del roadmap.
          </p>
        </div>
      </div>
    </main>
  );
}
