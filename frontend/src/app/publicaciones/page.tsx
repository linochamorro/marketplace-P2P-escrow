/**
 * PublicacionesPage — Página placeholder `/publicaciones` de EasyMarket.
 *
 * Destino de la redirección post-login (PHA01TSK10, Story 0b).
 * Decisión: implementación mínima de bienvenida para que el redirect
 * del formulario de login tenga una ruta real; el listado completo con
 * filtros, categorías y ordenamiento pertenece a PHA05TSK04 (Story 11)
 * y queda explícitamente fuera del alcance de esta tarea.
 *
 * @returns Elemento JSX de la página de bienvenida.
 */
export default function PublicacionesPage() {
  return (
    <div className="min-h-screen flex flex-col items-center justify-center bg-[#F8FAFC] px-4">
      <div className="w-full max-w-2xl bg-white border border-slate-200 rounded-lg p-8 text-center space-y-4">
        <h1 className="text-2xl font-bold text-[#0F172A]">EasyMarket</h1>
        <h2 className="text-xl font-semibold text-slate-700">Publicaciones</h2>
        <p className="text-sm text-slate-500">
          El listado de publicaciones aparecerá aquí (PHA05TSK04, Story 11).
        </p>
        <p className="text-sm text-slate-400">
          Has accedido correctamente con tu sesión.
        </p>
      </div>
    </div>
  );
}
