'use client';

import { useRef, useState } from 'react';

/** Subcategoría real del árbol de catálogo. */
export interface SubcategoriaItem {
  /** ID requerido por las rutas de escritura. */
  id: number;
  /** Nombre visible confirmado por backend. */
  nombre: string;
}

/** Categoría raíz real con sus subcategorías. */
export interface CategoriaItem {
  /** ID requerido por las rutas de escritura. */
  id: number;
  /** Nombre visible confirmado por backend. */
  nombre: string;
  /** Hijas anidadas del árbol canónico. */
  subcategorias: SubcategoriaItem[];
}

/** Props del panel de catálogo administrativo. */
export interface PanelCategoriasProps {
  /** Árbol real cargado por la ruta administrativa; nunca existe fallback mock. */
  categoriasIniciales: CategoriaItem[];
}

/** Operación editable actualmente abierta. */
type Edicion = { tipo: 'categoria'; categoriaId: number; nombreOriginal: string }
  | { tipo: 'subcategoria'; categoriaId: number; subcategoriaId: number; nombreOriginal: string }
  | null;

/**
 * Extrae el mensaje común del backend para una mutación fallida.
 *
 * @param response respuesta HTTP no exitosa
 * @param fallback mensaje contextual
 * @returns mensaje remoto o fallback
 */
async function mensajeError(response: Response, fallback: string): Promise<string> {
  const body: unknown = await response.json().catch(() => null);
  if (body && typeof body === 'object' && 'mensaje' in body && typeof (body as { mensaje?: unknown }).mensaje === 'string') {
    return (body as { mensaje: string }).mensaje;
  }
  return fallback;
}

/**
 * Envía una mutación JSON del catálogo con cookie httpOnly.
 *
 * @param ruta ruta HTTP relativa a la API
 * @param method método POST o PUT
 * @param nombre nombre validado
 * @returns cuerpo confirmado por backend
 * @throws Error si la red o respuesta fallan
 */
async function mutarJson(ruta: string, method: 'POST' | 'PUT', nombre: string): Promise<Record<string, unknown>> {
  let response: Response;
  try {
    response = await fetch(`${process.env.NEXT_PUBLIC_API_URL ?? ''}${ruta}`, {
      method,
      headers: { 'Content-Type': 'application/json' },
      credentials: 'include',
      body: JSON.stringify({ nombre }),
    });
  } catch {
    throw new Error('Error de red al conectar con el servidor');
  }
  if (!response.ok) throw new Error(await mensajeError(response, `Error al guardar catálogo (código ${response.status})`));
  const body: unknown = await response.json();
  if (!body || typeof body !== 'object') throw new Error('La respuesta del catálogo no tiene un formato válido');
  return body as Record<string, unknown>;
}

/**
 * Envía una eliminación del catálogo con cookie httpOnly.
 *
 * @param ruta ruta HTTP relativa a la API
 * @returns promesa resuelta solo tras confirmación HTTP exitosa
 * @throws Error si la red o respuesta fallan
 */
async function eliminar(ruta: string): Promise<void> {
  let response: Response;
  try {
    response = await fetch(`${process.env.NEXT_PUBLIC_API_URL ?? ''}${ruta}`, { method: 'DELETE', credentials: 'include' });
  } catch {
    throw new Error('Error de red al conectar con el servidor');
  }
  if (!response.ok) throw new Error(await mensajeError(response, `Error al eliminar del catálogo (código ${response.status})`));
}

/**
 * Verifica un ID y nombre confirmados por una respuesta de categoría.
 *
 * @param body respuesta remota desconocida
 * @returns categoría raíz validada con hijas vacías
 * @throws Error ante contrato inválido
 */
function categoriaConfirmada(body: Record<string, unknown>): CategoriaItem {
  if (!Number.isSafeInteger(body.id) || typeof body.nombre !== 'string') throw new Error('La respuesta de categoría no tiene un formato válido');
  return { id: body.id as number, nombre: body.nombre, subcategorias: [] };
}

/**
 * Verifica un ID y nombre confirmados por una respuesta de subcategoría.
 *
 * @param body respuesta remota desconocida
 * @returns subcategoría validada
 * @throws Error ante contrato inválido
 */
function subcategoriaConfirmada(body: Record<string, unknown>): SubcategoriaItem {
  if (!Number.isSafeInteger(body.id) || typeof body.nombre !== 'string') throw new Error('La respuesta de subcategoría no tiene un formato válido');
  return { id: body.id as number, nombre: body.nombre };
}

/**
 * Panel CRUD completo de categorías y subcategorías de Story 4.
 * Cada alta/edición usa el objeto confirmado por backend y cada eliminación
 * modifica el árbol solo después del status exitoso; nunca crea IDs locales.
 *
 * @param props árbol real inicial
 * @returns formularios y árbol responsive con las seis familias de operación
 */
export default function PanelCategorias({ categoriasIniciales }: PanelCategoriasProps) {
  const [categorias, setCategorias] = useState(categoriasIniciales);
  const [nuevaCategoria, setNuevaCategoria] = useState('');
  const [nuevasSubcategorias, setNuevasSubcategorias] = useState<Record<number, string>>({});
  const [edicion, setEdicion] = useState<Edicion>(null);
  const [nombreEdicion, setNombreEdicion] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [exito, setExito] = useState<string | null>(null);
  const [ocupado, setOcupado] = useState(false);
  const mutacionEnCurso = useRef(false);

  /**
   * Ejecuta una operación impidiendo reentrada y centralizando banners.
   *
   * @param accion mutación asíncrona concreta
   * @param mensaje texto de éxito
   * @returns promesa completada al restaurar el estado de envío
   */
  const ejecutar = async (accion: () => Promise<void>, mensaje: string): Promise<void> => {
    if (mutacionEnCurso.current) return;
    mutacionEnCurso.current = true;
    setOcupado(true); setError(null); setExito(null);
    try { await accion(); setExito(mensaje); }
    catch (cause) { setError(cause instanceof Error ? cause.message : 'Error inesperado'); }
    finally { mutacionEnCurso.current = false; setOcupado(false); }
  };

  /**
   * Crea una categoría con ID y nombre confirmados por el POST.
   *
   * @param event submit del formulario raíz
   * @returns promesa de la operación
   */
  const crearCategoria = async (event: React.FormEvent<HTMLFormElement>): Promise<void> => {
    event.preventDefault();
    const nombre = nuevaCategoria.trim();
    if (!nombre) { setError('El nombre de la categoría es obligatorio'); return; }
    await ejecutar(async () => {
      const creada = categoriaConfirmada(await mutarJson('/categorias', 'POST', nombre));
      setCategorias((actuales) => [...actuales, creada]); setNuevaCategoria('');
    }, 'Categoría creada exitosamente');
  };

  /**
   * Abre la edición inline de una categoría.
   *
   * @param categoria categoría seleccionada
   * @returns nada; actualiza estado local
   */
  const abrirCategoria = (categoria: CategoriaItem): void => {
    setEdicion({ tipo: 'categoria', categoriaId: categoria.id, nombreOriginal: categoria.nombre });
    setNombreEdicion(categoria.nombre);
  };

  /**
   * Persiste el nuevo nombre de la categoría abierta.
   *
   * @returns promesa de la operación
   */
  const guardarCategoria = async (): Promise<void> => {
    if (!edicion || edicion.tipo !== 'categoria') return;
    const nombre = nombreEdicion.trim();
    if (!nombre) { setError('El nombre de la categoría es obligatorio'); return; }
    const id = edicion.categoriaId;
    await ejecutar(async () => {
      const confirmada = categoriaConfirmada(await mutarJson(`/categorias/${id}`, 'PUT', nombre));
      setCategorias((actuales) => actuales.map((categoria) => categoria.id === id ? { ...categoria, nombre: confirmada.nombre } : categoria));
      setEdicion(null);
    }, 'Categoría editada exitosamente');
  };

  /**
   * Elimina una categoría solo tras respuesta exitosa.
   *
   * @param categoria categoría seleccionada
   * @returns promesa de la operación
   */
  const eliminarCategoria = async (categoria: CategoriaItem): Promise<void> => {
    await ejecutar(async () => {
      await eliminar(`/categorias/${categoria.id}`);
      setCategorias((actuales) => actuales.filter((item) => item.id !== categoria.id));
    }, 'Categoría eliminada exitosamente');
  };

  /**
   * Crea una subcategoría bajo su categoría padre usando la respuesta real.
   *
   * @param categoria categoría padre
   * @returns promesa de la operación
   */
  const crearSubcategoria = async (categoria: CategoriaItem): Promise<void> => {
    const nombre = (nuevasSubcategorias[categoria.id] ?? '').trim();
    if (!nombre) { setError('El nombre de la subcategoría es obligatorio'); return; }
    await ejecutar(async () => {
      const creada = subcategoriaConfirmada(await mutarJson(`/categorias/${categoria.id}/subcategorias`, 'POST', nombre));
      setCategorias((actuales) => actuales.map((item) => item.id === categoria.id ? { ...item, subcategorias: [...item.subcategorias, creada] } : item));
      setNuevasSubcategorias((actuales) => ({ ...actuales, [categoria.id]: '' }));
    }, 'Subcategoría creada exitosamente');
  };

  /**
   * Abre edición inline de una subcategoría.
   *
   * @param categoria categoría padre
   * @param subcategoria hija seleccionada
   * @returns nada; actualiza estado local
   */
  const abrirSubcategoria = (categoria: CategoriaItem, subcategoria: SubcategoriaItem): void => {
    setEdicion({ tipo: 'subcategoria', categoriaId: categoria.id, subcategoriaId: subcategoria.id, nombreOriginal: subcategoria.nombre });
    setNombreEdicion(subcategoria.nombre);
  };

  /**
   * Persiste la edición de subcategoría en la ruta jerárquica exacta.
   *
   * @returns promesa de la operación
   */
  const guardarSubcategoria = async (): Promise<void> => {
    if (!edicion || edicion.tipo !== 'subcategoria') return;
    const nombre = nombreEdicion.trim();
    if (!nombre) { setError('El nombre de la subcategoría es obligatorio'); return; }
    const { categoriaId, subcategoriaId } = edicion;
    await ejecutar(async () => {
      const confirmada = subcategoriaConfirmada(await mutarJson(`/categorias/${categoriaId}/subcategorias/${subcategoriaId}`, 'PUT', nombre));
      setCategorias((actuales) => actuales.map((categoria) => categoria.id === categoriaId ? { ...categoria, subcategorias: categoria.subcategorias.map((sub) => sub.id === subcategoriaId ? confirmada : sub) } : categoria));
      setEdicion(null);
    }, 'Subcategoría editada exitosamente');
  };

  /**
   * Elimina una subcategoría solo tras respuesta exitosa.
   *
   * @param categoria categoría padre
   * @param subcategoria hija seleccionada
   * @returns promesa de la operación
   */
  const eliminarSubcategoria = async (categoria: CategoriaItem, subcategoria: SubcategoriaItem): Promise<void> => {
    await ejecutar(async () => {
      await eliminar(`/categorias/${categoria.id}/subcategorias/${subcategoria.id}`);
      setCategorias((actuales) => actuales.map((item) => item.id === categoria.id ? { ...item, subcategorias: item.subcategorias.filter((sub) => sub.id !== subcategoria.id) } : item));
    }, 'Subcategoría eliminada exitosamente');
  };

  return <section className="mx-auto max-w-[1280px] space-y-6 p-4 sm:p-6"><header className="border-b border-slate-200 pb-4"><h1 className="text-3xl font-bold tracking-tight text-[#0F172A]">Gestión de catálogo</h1><p className="mt-1 text-sm text-slate-600">Administra categorías y subcategorías reales para nuevas publicaciones.</p></header>{error && <div role="alert" className="rounded border border-[#ba1a1a] bg-[#ffdad6] p-3 text-[#93000a]">{error}</div>}{exito && <div role="status" className="rounded border border-[#10B981] bg-emerald-50 p-3 text-emerald-800">{exito}</div>}<form onSubmit={(event) => void crearCategoria(event)} className="flex flex-col gap-3 rounded-lg border border-slate-200 bg-white p-5 sm:flex-row sm:items-end"><label className="flex-1 text-sm font-medium text-slate-700">Nombre de la Nueva Categoría<input value={nuevaCategoria} onChange={(event) => setNuevaCategoria(event.target.value)} className="mt-1 w-full rounded border border-slate-300 px-3 py-2" /></label><button type="submit" disabled={ocupado} className="rounded border border-slate-900 bg-[#0F172A] px-4 py-2 text-sm font-semibold text-white disabled:opacity-50">Crear Categoría</button></form>{categorias.length === 0 ? <div className="rounded-lg border border-slate-200 bg-white p-6 text-slate-600">No hay categorías registradas.</div> : <ul className="space-y-4">{categorias.map((categoria) => <li key={categoria.id} className="space-y-4 rounded-lg border border-slate-200 bg-white p-5"><div className="flex flex-wrap items-center justify-between gap-3">{edicion?.tipo === 'categoria' && edicion.categoriaId === categoria.id ? <div className="flex flex-1 flex-wrap gap-2"><label className="flex-1 text-sm">Nuevo nombre de la categoría {edicion.nombreOriginal}<input value={nombreEdicion} onChange={(event) => setNombreEdicion(event.target.value)} className="ml-2 rounded border border-slate-300 px-2 py-1" /></label><button type="button" onClick={() => void guardarCategoria()} disabled={ocupado} className="rounded border border-[#10B981] bg-[#10B981] px-3 py-1 text-sm font-semibold text-white">Guardar categoría {edicion.nombreOriginal}</button></div> : <><h2 className="text-lg font-semibold text-[#0F172A]">{categoria.nombre}</h2><div className="flex gap-2"><button type="button" aria-label={`Editar categoría ${categoria.nombre}`} onClick={() => abrirCategoria(categoria)} className="rounded border border-slate-900 bg-[#0F172A] px-3 py-1 text-sm text-white hover:bg-slate-800">Editar</button><button type="button" aria-label={`Eliminar categoría ${categoria.nombre}`} onClick={() => void eliminarCategoria(categoria)} disabled={ocupado} className="rounded border border-[#ba1a1a] bg-[#ba1a1a] px-3 py-1 text-sm text-white">Eliminar</button></div></>}</div><div className="space-y-2 border-l-2 border-slate-200 pl-4">{categoria.subcategorias.length === 0 ? <p className="text-sm text-slate-500">Sin subcategorías</p> : <ul className="space-y-2">{categoria.subcategorias.map((subcategoria) => <li key={subcategoria.id} className="flex flex-wrap items-center justify-between gap-2 rounded border border-slate-200 p-2">{edicion?.tipo === 'subcategoria' && edicion.subcategoriaId === subcategoria.id ? <div className="flex flex-1 flex-wrap gap-2"><label className="flex-1 text-sm">Nuevo nombre de la subcategoría {edicion.nombreOriginal}<input value={nombreEdicion} onChange={(event) => setNombreEdicion(event.target.value)} className="ml-2 rounded border border-slate-300 px-2 py-1" /></label><button type="button" onClick={() => void guardarSubcategoria()} className="rounded border border-[#10B981] bg-[#10B981] px-3 py-1 text-sm font-semibold text-white">Guardar subcategoría {edicion.nombreOriginal}</button></div> : <><span className="text-sm font-medium text-[#0F172A]">{subcategoria.nombre}</span><div className="flex gap-2"><button type="button" aria-label={`Editar subcategoría ${subcategoria.nombre}`} onClick={() => abrirSubcategoria(categoria, subcategoria)} className="rounded border border-slate-500 px-2 py-1 text-xs text-slate-700 hover:bg-slate-50">Editar</button><button type="button" aria-label={`Eliminar subcategoría ${subcategoria.nombre}`} onClick={() => void eliminarSubcategoria(categoria, subcategoria)} disabled={ocupado} className="rounded border border-[#ba1a1a] bg-white px-2 py-1 text-xs text-[#93000a] hover:bg-[#fff5f5]">Eliminar</button></div></>}</li>)}</ul>}<div className="flex flex-col gap-2 pt-2 sm:flex-row"><label className="flex-1 text-sm">Nueva subcategoría de {categoria.nombre}<input value={nuevasSubcategorias[categoria.id] ?? ''} onChange={(event) => setNuevasSubcategorias((actuales) => ({ ...actuales, [categoria.id]: event.target.value }))} className="ml-2 rounded border border-slate-300 px-2 py-1" /></label><button type="button" aria-label={`Crear subcategoría en ${categoria.nombre}`} onClick={() => void crearSubcategoria(categoria)} disabled={ocupado} className="rounded border border-[#0F172A] bg-[#0F172A] px-3 py-1 text-sm text-white">Crear subcategoría</button></div></div></li>)}</ul>}</section>;
}

