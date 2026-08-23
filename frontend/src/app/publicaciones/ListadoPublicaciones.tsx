'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  cargarCategorias,
  cargarPublicaciones,
  construirParametrosListado,
  formatearPrecioSoles,
  solesACentavos,
  type CategoriaListado,
  type FiltrosListado,
  type PublicacionListado
} from './publicaciones-utils';

/** Props de transporte inyectable para aislar consultas reales en pruebas de componente. */
export interface ListadoPublicacionesProps {
  /** Transporte opcional de categorías; por defecto usa `cargarCategorias`. */
  obtenerCategorias?: (url: string) => Promise<CategoriaListado[]>;
  /** Transporte opcional de publicaciones; por defecto usa `cargarPublicaciones`. */
  obtenerPublicaciones?: (url: string) => Promise<PublicacionListado[]>;
}

/** Estado local inicial sin filtros ni orden seleccionados. */
const FILTROS_INICIALES: FiltrosListado = {
  categoriaId: '', subcategoriaId: '', precioMinimo: '', precioMaximo: '', orden: ''
};

/**
 * Listado interactivo de publicaciones de Story 11 con filtros aplicados solo mediante
 * acción explícita del usuario.
 *
 * @param props transportes opcionales para pruebas; la ruta usa los endpoints reales por defecto
 * @returns controles de filtros, estados de consulta y tarjetas de publicaciones aprobadas
 */
export default function ListadoPublicaciones({
  obtenerCategorias = cargarCategorias,
  obtenerPublicaciones = cargarPublicaciones
}: ListadoPublicacionesProps) {
  const [categorias, setCategorias] = useState<CategoriaListado[]>([]);
  const [filtros, setFiltros] = useState<FiltrosListado>(FILTROS_INICIALES);
  const [publicaciones, setPublicaciones] = useState<PublicacionListado[]>([]);
  const [cargando, setCargando] = useState<boolean>(true);
  const [errorMensaje, setErrorMensaje] = useState<string | null>(null);
  const [cargado, setCargado] = useState<boolean>(false);

  const subcategorias = useMemo(
    () => categorias.find((categoria) => String(categoria.id) === filtros.categoriaId)?.subcategorias ?? [],
    [categorias, filtros.categoriaId]
  );

  /**
   * Consulta publicaciones y conserva el orden exacto recibido, sin ordenarlas en cliente.
   *
   * @param parametros query ya validado; vacío representa el listado inicial o limpio
   * @returns promesa que termina cuando el estado de la consulta fue actualizado
   */
  const consultarPublicaciones = useCallback(async (parametros: URLSearchParams): Promise<void> => {
    const baseUrl = process.env.NEXT_PUBLIC_API_URL || '';
    const query = parametros.toString();
    setCargando(true);
    setErrorMensaje(null);
    try {
      const lista = await obtenerPublicaciones(`${baseUrl}/publicaciones${query ? `?${query}` : ''}`);
      setPublicaciones(lista);
      setCargado(true);
    } catch (error) {
      setErrorMensaje(error instanceof Error ? error.message : 'Error de red al conectar con el servidor');
    } finally {
      setCargando(false);
    }
  }, [obtenerPublicaciones]);

  useEffect(() => {
    const baseUrl = process.env.NEXT_PUBLIC_API_URL || '';
    /**
     * Carga en el montaje las categorías de selects, el listado inicial sin filtros
     * y evita llamadas síncronas a setState dentro del efecto (react-hooks/set-state-in-effect).
     *
     * @returns promesa que resuelve tras actualizar ambas lecturas independientes
     */
    const cargarInicial = async (): Promise<void> => {
      try {
        const arbol = await obtenerCategorias(`${baseUrl}/categorias`);
        setCategorias(arbol);
      } catch (error) {
        setErrorMensaje(error instanceof Error ? error.message : 'Error de red al conectar con el servidor');
      }
      await consultarPublicaciones(new URLSearchParams());
    };
    void cargarInicial();
  }, [consultarPublicaciones, obtenerCategorias]);

  /**
   * Actualiza categoría y descarta subcategoría, porque deja de pertenecer al árbol seleccionado.
   *
   * @param categoriaId identificador textual seleccionado o vacío
   * @returns nada; actualiza únicamente los controles locales
   */
  const cambiarCategoria = (categoriaId: string): void => {
    setFiltros((actuales) => ({ ...actuales, categoriaId, subcategoriaId: '' }));
  };

  /**
   * Convierte los precios locales a centavos y dispara la consulta solo ante el botón explícito.
   *
   * @returns promesa que resuelve al terminar la consulta o tras reportar formato inválido
   */
  const aplicarFiltros = async (): Promise<void> => {
    const minimo = filtros.precioMinimo ? solesACentavos(filtros.precioMinimo) : '';
    const maximo = filtros.precioMaximo ? solesACentavos(filtros.precioMaximo) : '';
    if (minimo === null || maximo === null) {
      setErrorMensaje('Ingresa precios en soles con hasta dos decimales');
      return;
    }
    await consultarPublicaciones(construirParametrosListado({
      ...filtros, precioMinimo: minimo, precioMaximo: maximo
    }));
  };

  /**
   * Restablece todos los controles y consulta nuevamente el listado sin query params.
   *
   * @returns promesa que resuelve al terminar la consulta limpia
   */
  const limpiarFiltros = async (): Promise<void> => {
    setFiltros(FILTROS_INICIALES);
    await consultarPublicaciones(new URLSearchParams());
  };

  return (
    <main className="min-h-screen bg-[#F8FAFC] px-4 py-6 sm:px-5 lg:px-6">
      <div className="mx-auto grid max-w-[1280px] grid-cols-4 gap-4 sm:grid-cols-8 sm:gap-5 lg:grid-cols-12 lg:gap-6">
        <section className="col-span-full space-y-6">
          <header className="border-b border-slate-200 pb-4">
            <h1 className="text-3xl font-bold tracking-tight text-[#0F172A]">Publicaciones</h1>
            <p className="mt-1 text-sm text-slate-600">Explora publicaciones aprobadas del marketplace.</p>
          </header>
          <div className="grid grid-cols-1 gap-4 border border-slate-200 bg-white p-6 sm:grid-cols-2 lg:grid-cols-5">
            <label className="space-y-1 text-sm text-slate-700">Categoría
              <select aria-label="Categoría" value={filtros.categoriaId} onChange={(event) => cambiarCategoria(event.target.value)} className="w-full rounded border border-slate-300 bg-white p-2 text-[#0F172A]">
                <option value="">Todas</option>{categorias.map((categoria) => <option key={categoria.id} value={categoria.id}>{categoria.nombre}</option>)}
              </select>
            </label>
            <label className="space-y-1 text-sm text-slate-700">Subcategoría
              <select aria-label="Subcategoría" value={filtros.subcategoriaId} disabled={!filtros.categoriaId} onChange={(event) => setFiltros((actuales) => ({ ...actuales, subcategoriaId: event.target.value }))} className="w-full rounded border border-slate-300 bg-white p-2 text-[#0F172A]">
                <option value="">Todas</option>{subcategorias.map((subcategoria) => <option key={subcategoria.id} value={subcategoria.id}>{subcategoria.nombre}</option>)}
              </select>
            </label>
            <label className="space-y-1 text-sm text-slate-700">Precio mínimo (S/)
              <input aria-label="Precio mínimo (S/)" value={filtros.precioMinimo} onChange={(event) => setFiltros((actuales) => ({ ...actuales, precioMinimo: event.target.value }))} inputMode="decimal" pattern="\d+(\.\d{1,2})?" className="w-full rounded border border-slate-300 bg-white p-2 text-[#0F172A]" />
            </label>
            <label className="space-y-1 text-sm text-slate-700">Precio máximo (S/)
              <input aria-label="Precio máximo (S/)" value={filtros.precioMaximo} onChange={(event) => setFiltros((actuales) => ({ ...actuales, precioMaximo: event.target.value }))} inputMode="decimal" pattern="\d+(\.\d{1,2})?" className="w-full rounded border border-slate-300 bg-white p-2 text-[#0F172A]" />
            </label>
            <label className="space-y-1 text-sm text-slate-700">Orden
              <select aria-label="Orden" value={filtros.orden} onChange={(event) => setFiltros((actuales) => ({ ...actuales, orden: event.target.value as FiltrosListado['orden'] }))} className="w-full rounded border border-slate-300 bg-white p-2 text-[#0F172A]">
                <option value="">Sin orden</option><option value="PRECIO_ASCENDENTE">Precio: menor a mayor</option><option value="PRECIO_DESCENDENTE">Precio: mayor a menor</option><option value="MAS_VENDIDO">Más vendido</option>
              </select>
            </label>
            <div className="flex items-end gap-2 sm:col-span-2 lg:col-span-5">
              <button type="button" onClick={() => void aplicarFiltros()} className="rounded border border-[#0F172A] bg-[#0F172A] px-4 py-2 text-sm font-semibold text-white">Aplicar filtros</button>
              <button type="button" onClick={() => void limpiarFiltros()} className="rounded border border-slate-300 bg-white px-4 py-2 text-sm font-semibold text-[#0F172A]">Limpiar filtros</button>
            </div>
          </div>
          {errorMensaje && <p role="alert" className="border border-[#ba1a1a] bg-[#ffdad6] p-3 text-sm font-medium text-[#93000a]">{errorMensaje}</p>}
          {cargando ? <div role="status" aria-busy="true" className="border border-slate-200 bg-white p-6 text-slate-600">Cargando publicaciones...</div> : publicaciones.length === 0 && cargado && !errorMensaje ? <div className="border border-slate-200 bg-white p-6 text-slate-600">No se encontraron publicaciones.</div> : <ul className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">{publicaciones.map((publicacion) => <li key={publicacion.id} className="space-y-3 border border-slate-200 bg-white p-6"><p className="text-base text-[#0F172A]">{publicacion.descripcion}</p><p className="font-mono text-xl font-semibold text-[#0F172A]">{formatearPrecioSoles(publicacion.precio)}</p><p className="text-sm text-slate-600">Stock: {publicacion.stock}</p><p className="font-mono text-xs text-slate-500">ID publicación: {publicacion.id} · Categoría: {publicacion.categoriaId} · Subcategoría: {publicacion.subcategoriaId} · Vendedor: {publicacion.usuarioId}</p></li>)}</ul>}
        </section>
      </div>
    </main>
  );
}
