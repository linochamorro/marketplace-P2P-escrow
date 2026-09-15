'use client';

import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react';
import EditarPublicacionForm from './EditarPublicacionForm';
import Image from 'next/image';
import {
  cargarCategorias,
  cargarPublicaciones,
  formatearPrecioSoles,
  type CategoriaListado,
  type PublicacionListado
} from './publicaciones/publicaciones-utils';

/** Estados API cubiertos por la matriz de acciones de PHA06TSK10. */
type EstadoGestionable = 'PENDIENTE_REVISION' | 'APROBADA' | 'OCULTA' | 'CAMBIOS_SOLICITADOS' | 'RECHAZADA';

/** Props de transporte inyectable para aislar lecturas en pruebas de componente. */
export interface GestionPublicacionesPropiasProps {
  /** Transporte opcional del catálogo real. */
  obtenerCategorias?: (url: string) => Promise<CategoriaListado[]>;
  /** Transporte opcional de publicaciones propias. */
  obtenerPublicaciones?: (url: string) => Promise<PublicacionListado[]>;
}

/** Clasificación resuelta para lectura y para habilitar acciones seguras. */
interface ClasificacionResuelta {
  /** Nombre de la categoría real. */ categoriaNombre: string;
  /** Nombre de la subcategoría real. */ subcategoriaNombre: string;
}

/** Props del formulario inline de corrección de categoría/subcategoría. */
interface CorreccionClasificacionFormProps {
  /** Publicación rechazada o con cambios solicitados. */ publicacion: PublicacionListado;
  /** Catálogo contra el que se construyen parejas válidas. */ categorias: CategoriaListado[];
  /** Callback de cierre posterior al PATCH exitoso. */ onSuccess: () => Promise<void>;
}

/**
 * Extrae `mensaje` de una respuesta no exitosa sin asumir JSON válido.
 *
 * @param response respuesta HTTP de una mutación
 * @param fallback mensaje contextual cuando no existe `mensaje`
 * @returns mensaje backend o fallback honesto
 */
async function mensajeMutacion(response: Response, fallback: string): Promise<string> {
  const data: unknown = await response.json().catch(() => null);
  return data && typeof data === 'object' && 'mensaje' in data && typeof data.mensaje === 'string'
    ? data.mensaje : fallback;
}

/**
 * Resuelve una pareja de IDs solo si la subcategoría pertenece a la categoría indicada.
 *
 * @param publicacion publicación obtenida del backend
 * @param categorias árbol canónico de categorías
 * @returns nombres resueltos o `null` ante catálogo vacío/inconsistente
 */
function resolverClasificacion(publicacion: PublicacionListado, categorias: CategoriaListado[]): ClasificacionResuelta | null {
  const categoria = categorias.find((item) => item.id === publicacion.categoriaId);
  const subcategoria = categoria?.subcategorias.find((item) => item.id === publicacion.subcategoriaId);
  return categoria && subcategoria ? { categoriaNombre: categoria.nombre, subcategoriaNombre: subcategoria.nombre } : null;
}

/**
 * Corrige exclusivamente categoría/subcategoría mediante selectores dependientes.
 *
 * @param props publicación, catálogo y callback de refetch
 * @returns formulario inline de `PATCH /corregir`
 */
function CorreccionClasificacionForm({ publicacion, categorias, onSuccess }: CorreccionClasificacionFormProps) {
  const categoriaInicial = categorias.find((item) => item.id === publicacion.categoriaId) ?? categorias[0];
  const [categoriaId, setCategoriaId] = useState(categoriaInicial ? String(categoriaInicial.id) : '');
  const subcategoriaInicial = categoriaInicial?.subcategorias.find((item) => item.id === publicacion.subcategoriaId)
    ?? categoriaInicial?.subcategorias[0];
  const [subcategoriaId, setSubcategoriaId] = useState(subcategoriaInicial ? String(subcategoriaInicial.id) : '');
  const [error, setError] = useState<string | null>(null);
  const [enviando, setEnviando] = useState(false);
  const categoriaSeleccionada = useMemo(
    () => categorias.find((item) => String(item.id) === categoriaId),
    [categorias, categoriaId]
  );

  /**
   * Cambia categoría y reinicia la subcategoría a una pareja perteneciente real.
   *
   * @param nuevoId ID textual del selector
   * @returns nada; actualiza controles locales
   */
  const cambiarCategoria = (nuevoId: string): void => {
    const categoria = categorias.find((item) => String(item.id) === nuevoId);
    setCategoriaId(nuevoId);
    setSubcategoriaId(categoria?.subcategorias[0] ? String(categoria.subcategorias[0].id) : '');
  };

  /**
   * Envía el cuerpo exclusivo autorizado y solicita refetch tras éxito.
   *
   * @param event envío del formulario de corrección
   * @returns promesa resuelta después del PATCH y eventual refetch
   */
  const corregir = async (event: FormEvent<HTMLFormElement>): Promise<void> => {
    event.preventDefault();
    if (!categoriaId || !subcategoriaId) {
      setError('Selecciona una categoría con subcategorías disponibles');
      return;
    }
    setError(null);
    setEnviando(true);
    const baseUrl = process.env.NEXT_PUBLIC_API_URL || '';
    try {
      const response = await fetch(`${baseUrl}/publicaciones/${publicacion.id}/corregir`, {
        method: 'PATCH', headers: { 'Content-Type': 'application/json' }, credentials: 'include',
        body: JSON.stringify({ categoriaId: Number(categoriaId), subcategoriaId: Number(subcategoriaId) })
      });
      if (!response.ok) {
        setError(await mensajeMutacion(response, `Error al corregir publicación (código ${response.status})`));
        return;
      }
      await onSuccess();
    } catch {
      setError('Error de red al conectar con el servidor');
    } finally {
      setEnviando(false);
    }
  };

  return (
    <form onSubmit={corregir} className="mt-4 space-y-4 border-t border-slate-200 pt-4">
      {error && <p role="alert" className="rounded border border-[#ba1a1a] bg-[#ffdad6] p-3 text-sm text-[#93000a]">{error}</p>}
      <label className="block text-sm text-slate-700">Categoría de corrección
        <select aria-label="Categoría de corrección" value={categoriaId} onChange={(event) => cambiarCategoria(event.target.value)} className="mt-1 w-full rounded border border-slate-300 bg-white p-2">
          {categorias.map((categoria) => <option key={categoria.id} value={categoria.id}>{categoria.nombre}</option>)}
        </select>
      </label>
      <label className="block text-sm text-slate-700">Subcategoría de corrección
        <select aria-label="Subcategoría de corrección" value={subcategoriaId} onChange={(event) => setSubcategoriaId(event.target.value)} disabled={!categoriaSeleccionada?.subcategorias.length} className="mt-1 w-full rounded border border-slate-300 bg-white p-2">
          {categoriaSeleccionada?.subcategorias.map((subcategoria) => <option key={subcategoria.id} value={subcategoria.id}>{subcategoria.nombre}</option>)}
        </select>
      </label>
      <button type="submit" disabled={enviando || !subcategoriaId} className="rounded border border-slate-900 bg-[#0F172A] px-4 py-2 text-sm font-semibold text-white disabled:opacity-50">{enviando ? 'Enviando...' : 'Enviar corrección'}</button>
    </form>
  );
}

/**
 * Lista publicaciones propias y ofrece paneles expandibles inline según su estado API.
 * Todas las mutaciones vuelven a `GET /publicaciones/mias`; no simula transiciones locales.
 *
 * @param props transportes opcionales para las dos lecturas iniciales
 * @returns vista de loading/error/vacío o tarjetas con la matriz exacta de acciones
 */
export default function GestionPublicacionesPropias({
  obtenerCategorias = cargarCategorias,
  obtenerPublicaciones = cargarPublicaciones
}: GestionPublicacionesPropiasProps) {
  const [categorias, setCategorias] = useState<CategoriaListado[]>([]);
  const [publicaciones, setPublicaciones] = useState<PublicacionListado[]>([]);
  const [cargando, setCargando] = useState(true);
  const [cargado, setCargado] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [panelAbierto, setPanelAbierto] = useState<{ id: number; tipo: 'editar' | 'corregir' } | null>(null);
  const [confirmandoId, setConfirmandoId] = useState<number | null>(null);

  /**
   * Consulta nuevamente publicaciones propias y conserva al backend como fuente canónica.
   *
   * @returns promesa resuelta tras reemplazar la lista o mostrar el error
   */
  const refetchPublicaciones = useCallback(async (): Promise<void> => {
    const baseUrl = process.env.NEXT_PUBLIC_API_URL || '';
    try {
      const lista = await obtenerPublicaciones(`${baseUrl}/publicaciones/mias`);
      setPublicaciones(lista);
      setPanelAbierto(null);
      setConfirmandoId(null);
    } catch (fallo) {
      setError(fallo instanceof Error ? fallo.message : 'Error de red al cargar publicaciones propias');
    }
  }, [obtenerPublicaciones]);

  useEffect(() => {
    const baseUrl = process.env.NEXT_PUBLIC_API_URL || '';

    /**
     * Carga catálogo y publicaciones en paralelo para habilitar acciones solo con ambas fuentes.
     *
     * @returns promesa resuelta al completar la carga inicial
     */
    const cargarInicial = async (): Promise<void> => {
      setCargando(true);
      setError(null);
      try {
        const [arbol, lista] = await Promise.all([
          obtenerCategorias(`${baseUrl}/categorias`),
          obtenerPublicaciones(`${baseUrl}/publicaciones/mias`)
        ]);
        setCategorias(arbol);
        setPublicaciones(lista);
        setCargado(true);
      } catch (fallo) {
        setError(fallo instanceof Error ? fallo.message : 'Error de red al cargar publicaciones propias');
      } finally {
        setCargando(false);
      }
    };
    void cargarInicial();
  }, [obtenerCategorias, obtenerPublicaciones]);

  /**
   * Elimina una publicación ya confirmada y ejecuta refetch solo tras 204/2xx.
   *
   * @param id identificador de la publicación rechazada
   * @returns promesa resuelta tras DELETE y refetch o mensaje de error
   */
  const eliminar = async (id: number): Promise<void> => {
    const baseUrl = process.env.NEXT_PUBLIC_API_URL || '';
    try {
      const response = await fetch(`${baseUrl}/publicaciones/${id}`, { method: 'DELETE', credentials: 'include' });
      if (!response.ok) {
        setError(await mensajeMutacion(response, `Error al eliminar publicación (código ${response.status})`));
        return;
      }
      await refetchPublicaciones();
    } catch {
      setError('Error de red al conectar con el servidor');
    }
  };

  return (
    <main className="min-h-screen bg-[#F8FAFC] px-4 py-6 sm:px-5 lg:px-6">
      <section className="mx-auto max-w-[1280px] space-y-6">
        <header className="border-b border-slate-200 pb-4"><h1 className="text-3xl font-bold tracking-tight text-[#0F172A]">Mis publicaciones</h1><p className="mt-1 text-sm text-slate-600">Gestiona tus ofertas según su estado de revisión.</p></header>
        {error && <p role="alert" className="rounded border border-[#ba1a1a] bg-[#ffdad6] p-3 text-sm font-medium text-[#93000a]">{error}</p>}
        {cargando ? <p role="status" aria-busy="true" className="rounded-lg border border-slate-200 bg-white p-6 text-slate-600">Cargando mis publicaciones...</p>
          : cargado && publicaciones.length === 0 ? <p className="rounded-lg border border-slate-200 bg-white p-6 text-slate-600">Aún no tienes publicaciones.</p>
            : <ul aria-label="Publicaciones propias" className="grid grid-cols-1 gap-4">{publicaciones.map((publicacion) => {
              const clasificacion = resolverClasificacion(publicacion, categorias);
              const estado = publicacion.estado as EstadoGestionable;
              const puedeEditar = Boolean(clasificacion) && (estado === 'PENDIENTE_REVISION' || estado === 'APROBADA' || estado === 'OCULTA');
              const puedeCorregir = Boolean(clasificacion) && (estado === 'CAMBIOS_SOLICITADOS' || estado === 'RECHAZADA');
              const imagenFilename = publicacion.imagenFilename;
              return <li key={publicacion.id} className="rounded-lg border border-slate-200 bg-white p-6">
                <div className="flex flex-wrap items-start justify-between gap-4">
                  <div className="flex gap-4">
                    {imagenFilename && (
                      <div className="flex-shrink-0 w-24 h-24 rounded border border-slate-200 bg-slate-50 overflow-hidden">
                        <Image
                          src={`/imagenes/publicaciones/${imagenFilename}`}
                          alt={imagenFilename}
                          width={96}
                          height={96}
                          className="object-cover"
                        />
                      </div>
                    )}
                    <div className="space-y-2">
                      <span className="inline-block rounded border border-slate-300 bg-slate-100 px-2 py-1 text-xs font-semibold text-[#0F172A]">{publicacion.estado}</span>
                      <p className="text-base text-[#0F172A]">{publicacion.descripcion}</p>
                      <p className="font-mono text-lg font-bold text-[#0F172A]">{formatearPrecioSoles(publicacion.precio)}</p>
                      <p className="text-sm text-slate-600">Stock: {publicacion.stock}</p>
                      <p className="text-sm text-slate-600">{clasificacion ? `${clasificacion.categoriaNombre} · ${clasificacion.subcategoriaNombre}` : 'Clasificación no disponible'}</p>
                    </div>
                  </div>
                  <div className="flex flex-wrap gap-2">
                    {puedeEditar && <button type="button" aria-label={`Editar publicación ${publicacion.id}`} onClick={() => setPanelAbierto({ id: publicacion.id, tipo: 'editar' })} className="rounded border border-slate-900 bg-[#0F172A] px-3 py-2 text-sm font-semibold text-white">Editar</button>}
                    {puedeCorregir && <button type="button" aria-label={`Corregir publicación ${publicacion.id}`} onClick={() => setPanelAbierto({ id: publicacion.id, tipo: 'corregir' })} className="rounded border border-slate-300 bg-white px-3 py-2 text-sm font-semibold text-[#0F172A]">Corregir</button>}
                    <button type="button" aria-label={`Eliminar publicación ${publicacion.id}`} onClick={() => setConfirmandoId(publicacion.id)} className="rounded border border-[#ba1a1a] bg-white px-3 py-2 text-sm font-semibold text-[#93000a]">Eliminar</button>
                  </div>
                </div>
                {panelAbierto?.id === publicacion.id && panelAbierto.tipo === 'editar' && clasificacion && <EditarPublicacionForm publicacionInicial={{ ...publicacion, ...clasificacion }} onSuccess={refetchPublicaciones} onCancel={() => setPanelAbierto(null)} />}
                {panelAbierto?.id === publicacion.id && panelAbierto.tipo === 'corregir' && clasificacion && <CorreccionClasificacionForm publicacion={publicacion} categorias={categorias} onSuccess={refetchPublicaciones} />}
                {confirmandoId === publicacion.id && <div role="group" aria-label={`Confirmar eliminación de publicación ${publicacion.id}`} className="mt-4 space-y-3 rounded border border-[#ba1a1a] bg-[#ffdad6] p-4"><p className="text-sm text-[#93000a]">Esta acción es definitiva. ¿Deseas eliminar la publicación?</p><div className="flex gap-2"><button type="button" onClick={() => void eliminar(publicacion.id)} className="rounded border border-[#93000a] bg-[#93000a] px-3 py-2 text-sm font-semibold text-white">Confirmar eliminación</button><button type="button" onClick={() => setConfirmandoId(null)} className="rounded border border-slate-300 bg-white px-3 py-2 text-sm text-black">Cancelar</button></div></div>}
              </li>;
            })}</ul>}
      </section>
    </main>
  );
}
