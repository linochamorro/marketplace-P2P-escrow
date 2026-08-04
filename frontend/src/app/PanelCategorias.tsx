'use client';

import React, { useState } from 'react';

/**
 * Interface para representar una subcategoría en el árbol de catálogo.
 */
export interface SubcategoriaItem {
  id: number;
  nombre: string;
}

/**
 * Interface para representar una categoría raíz con sus subcategorías.
 */
export interface CategoriaItem {
  id: number;
  nombre: string;
  subcategorias: SubcategoriaItem[];
}

/**
 * Interface de props para PanelCategorias.
 */
export interface PanelCategoriasProps {
  /** Lista opcional de categorías iniciales (por defecto usa CATEGORIAS_TREE_MOCK) */
  categoriasIniciales?: CategoriaItem[];
}

/**
 * Datos estáticos temporales para el árbol de categorías/subcategorías.
 * TODO: reemplazar con GET /categorias real cuando exista esa tarea de API — ver PHA02TSK14 (vacío de dependencia 1).
 */
const CATEGORIAS_TREE_MOCK: CategoriaItem[] = [
  {
    id: 1,
    nombre: 'Electrónica',
    subcategorias: [
      { id: 101, nombre: 'Smartphones' },
      { id: 102, nombre: 'Laptops' }
    ]
  },
  {
    id: 2,
    nombre: 'Hogar',
    subcategorias: [
      { id: 201, nombre: 'Muebles' },
      { id: 202, nombre: 'Decoración' }
    ]
  }
];

/**
 * Componente PanelCategorias (PHA02TSK14 - Parte A).
 * Panel de gestión de categorías y subcategorías para administradores según Story 4 de spec.md y DESIGN.md.
 *
 * @param props Props del componente {@link PanelCategoriasProps}
 * @returns Elemento JSX con el panel de administración de categorías
 */
export default function PanelCategorias({ categoriasIniciales }: PanelCategoriasProps) {
  const [categorias, setCategorias] = useState<CategoriaItem[]>(
    categoriasIniciales || CATEGORIAS_TREE_MOCK
  );

  const [nombreNuevaCategoria, setNombreNuevaCategoria] = useState<string>('');
  const [nombreNuevaSubcategoria, setNombreNuevaSubcategoria] = useState<{ [catId: number]: string }>({});

  const [errorGlobal, setErrorGlobal] = useState<string | null>(null);
  const [exitoGlobal, setExitoGlobal] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState<boolean>(false);

  /**
   * Crea una nueva categoría raíz enviando POST /categorias.
   */
  const handleCrearCategoria = async (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    setErrorGlobal(null);
    setExitoGlobal(null);

    const nombre = nombreNuevaCategoria.trim();
    if (!nombre) {
      setErrorGlobal('El nombre de la categoría es obligatorio');
      return;
    }

    setIsSubmitting(true);
    const baseUrl = process.env.NEXT_PUBLIC_API_URL || '';
    const endpoint = `${baseUrl}/categorias`;

    try {
      const response = await fetch(endpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ nombre })
      });

      if (!response.ok) {
        const errorData = await response.json().catch(() => null);
        const mensaje = errorData?.mensaje || `Error al crear categoría (código ${response.status})`;
        setErrorGlobal(mensaje);
      } else {
        const nuevaCat = await response.json();
        setCategorias((prev) => [...prev, { ...nuevaCat, subcategorias: nuevaCat.subcategorias || [] }]);
        setNombreNuevaCategoria('');
        setExitoGlobal('Categoría creada exitosamente');
      }
    } catch (err) {
      setErrorGlobal('Error de red al conectar con el servidor');
    } finally {
      setIsSubmitting(false);
    }
  };

  /**
   * Elimina una categoría raíz enviando DELETE /categorias/{id}.
   * Maneja el error 409 (CategoriaConPublicacionesException) si la categoría posee publicaciones vinculadas.
   */
  const handleEliminarCategoria = async (catId: number) => {
    setErrorGlobal(null);
    setExitoGlobal(null);
    setIsSubmitting(true);

    const baseUrl = process.env.NEXT_PUBLIC_API_URL || '';
    const endpoint = `${baseUrl}/categorias/${catId}`;

    try {
      const response = await fetch(endpoint, {
        method: 'DELETE',
        credentials: 'include'
      });

      if (!response.ok) {
        const errorData = await response.json().catch(() => null);
        const mensaje = errorData?.mensaje || `Error al eliminar categoría (código ${response.status})`;
        setErrorGlobal(mensaje);
      } else {
        setCategorias((prev) => prev.filter((c) => c.id !== catId));
        setExitoGlobal('Categoría eliminada exitosamente');
      }
    } catch (err) {
      setErrorGlobal('Error de red al conectar con el servidor');
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="max-w-4xl mx-auto p-6 space-y-6 bg-white border border-slate-200 rounded-lg shadow-none">
      <div className="border-b border-slate-200 pb-4">
        <h2 className="text-2xl font-bold text-[#0F172A] tracking-tight">Gestión de Catálogo de Categorías (Admin)</h2>
        <p className="text-sm text-slate-600 mt-1">
          Crea y administra categorías y subcategorías para la clasificación de productos.
        </p>
      </div>

      {errorGlobal && (
        <div className="p-3 bg-[#ffdad6] border border-[#ba1a1a] text-[#93000a] rounded text-sm font-medium">
          {errorGlobal}
        </div>
      )}

      {exitoGlobal && (
        <div className="p-3 bg-[#d1fae5] border border-[#10B981] text-[#065f46] rounded text-sm font-medium">
          {exitoGlobal}
        </div>
      )}

      {/* Formulario de Creación de Categoría Raíz */}
      <form onSubmit={handleCrearCategoria} className="flex gap-3 items-end">
        <div className="flex-1">
          <label htmlFor="nombreCategoria" className="block text-xs font-semibold uppercase tracking-wider text-slate-700 mb-1">
            Nombre de la Nueva Categoría
          </label>
          <input
            id="nombreCategoria"
            type="text"
            placeholder="Ej. Deportes, Calzado..."
            value={nombreNuevaCategoria}
            onChange={(e) => setNombreNuevaCategoria(e.target.value)}
            className="w-full px-3 py-2 bg-white border border-slate-300 rounded text-sm text-[#0F172A] focus:outline-none focus:ring-2 focus:ring-slate-200 focus:border-[#0F172A]"
          />
        </div>
        <button
          type="submit"
          disabled={isSubmitting}
          className="py-2 px-4 bg-[#0F172A] hover:bg-slate-800 text-white font-medium rounded text-sm transition-colors border border-slate-900 disabled:opacity-50"
        >
          Crear Categoría
        </button>
      </form>

      {/* Árbol de Categorías y Subcategorías */}
      <div className="space-y-4 pt-4 border-t border-slate-100">
        <h3 className="text-sm font-semibold uppercase tracking-wider text-slate-700">Categorías Existentes</h3>
        {categorias.map((cat) => (
          <div key={cat.id} className="p-4 border border-slate-200 rounded-md bg-slate-50 space-y-3">
            <div className="flex justify-between items-center">
              <span className="text-base font-semibold text-[#0F172A]">{cat.nombre}</span>
              <button
                type="button"
                disabled={isSubmitting}
                onClick={() => handleEliminarCategoria(cat.id)}
                className="px-3 py-1 bg-[#ba1a1a] hover:bg-red-800 text-white font-medium text-xs rounded transition-colors"
              >
                Eliminar
              </button>
            </div>

            {/* Listado de Subcategorías */}
            <div className="pl-4 space-y-1">
              <span className="text-xs font-medium text-slate-500">Subcategorías:</span>
              <div className="flex flex-wrap gap-2 pt-1">
                {cat.subcategorias.length === 0 ? (
                  <span className="text-xs italic text-slate-400">Sin subcategorías</span>
                ) : (
                  cat.subcategorias.map((sub) => (
                    <span
                      key={sub.id}
                      className="px-2.5 py-1 text-xs font-mono bg-white border border-slate-200 rounded text-slate-800"
                    >
                      {sub.nombre}
                    </span>
                  ))
                )}
              </div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
