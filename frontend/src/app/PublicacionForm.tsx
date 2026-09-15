'use client';

import { useEffect, useMemo, useState, type ChangeEvent, type FormEvent } from 'react';
import {
  cargarCategorias,
  solesACentavos,
  type CategoriaListado
} from './publicaciones/publicaciones-utils';

/** Props del formulario de creación integrado con el catálogo real. */
export interface PublicacionFormProps {
  /** Callback ejecutado únicamente después de una creación HTTP exitosa. */
  onSuccess?: () => void;
  /** Transporte inyectable de categorías; por defecto consulta `GET /categorias`. */
  obtenerCategorias?: (url: string) => Promise<CategoriaListado[]>;
}

/** Errores visibles de validación o transporte del formulario. */
interface FormErrors {
  /** Error del precio escrito en soles. */
  precio?: string;
  /** Error del stock entero. */
  stock?: string;
  /** Error del catálogo o del request de creación. */
  general?: string;
}

/**
 * Crea una publicación con IDs del catálogo backend y dinero convertido por segmentos
 * de texto, sin aritmética decimal.
 *
 * @param props callback de éxito y transporte opcional para pruebas
 * @returns formulario con carga, error y selectores dependientes accesibles
 */
export default function PublicacionForm({
  onSuccess,
  obtenerCategorias = cargarCategorias
}: PublicacionFormProps) {
  const [categorias, setCategorias] = useState<CategoriaListado[]>([]);
  const [precioInput, setPrecioInput] = useState('');
  const [stockInput, setStockInput] = useState('1');
  const [categoriaId, setCategoriaId] = useState('');
  const [subcategoriaId, setSubcategoriaId] = useState('');
  const [descripcion, setDescripcion] = useState('');
  const [imagenFilename, setImagenFilename] = useState('');
  const [errors, setErrors] = useState<FormErrors>({});
  const [cargandoCategorias, setCargandoCategorias] = useState(true);
  const [catalogoRespondio, setCatalogoRespondio] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);

  useEffect(() => {
    const baseUrl = process.env.NEXT_PUBLIC_API_URL || '';

    /**
     * Carga el catálogo y selecciona la primera pareja real disponible.
     *
     * @returns promesa resuelta después de reflejar carga o error
     */
    const cargarCatalogo = async (): Promise<void> => {
      try {
        const arbol = await obtenerCategorias(`${baseUrl}/categorias`);
        setCatalogoRespondio(true);
        setCategorias(arbol);
        const primeraCategoriaConPareja = arbol.find((categoria) => categoria.subcategorias.length > 0);
        setCategoriaId(primeraCategoriaConPareja ? String(primeraCategoriaConPareja.id) : '');
        setSubcategoriaId(primeraCategoriaConPareja?.subcategorias[0]
          ? String(primeraCategoriaConPareja.subcategorias[0].id)
          : '');
      } catch (error) {
        setErrors({ general: error instanceof Error ? error.message : 'Error de red al cargar categorías' });
      } finally {
        setCargandoCategorias(false);
      }
    };

    void cargarCatalogo();
  }, [obtenerCategorias]);

  const categoriaSeleccionada = useMemo(
    () => categorias.find((categoria) => String(categoria.id) === categoriaId),
    [categorias, categoriaId]
  );

  /**
   * Cambia la categoría y selecciona solo la primera subcategoría perteneciente a ella.
   *
   * @param event evento del selector de categoría
   * @returns nada; actualiza la pareja de IDs local
   */
  const handleCategoriaChange = (event: ChangeEvent<HTMLSelectElement>): void => {
    const nuevoId = event.target.value;
    const categoria = categorias.find((item) => String(item.id) === nuevoId);
    setCategoriaId(nuevoId);
    setSubcategoriaId(categoria?.subcategorias[0] ? String(categoria.subcategorias[0].id) : '');
  };

  /**
   * Valida enteros/centavos y crea la publicación usando exclusivamente IDs resueltos.
   *
   * @param event envío del formulario
   * @returns promesa resuelta al terminar el request o la validación local
   */
  const handleSubmit = async (event: FormEvent<HTMLFormElement>): Promise<void> => {
    event.preventDefault();
    const nuevosErrores: FormErrors = {};
    const centavosTexto = solesACentavos(precioInput);
    const stockValido = /^\d+$/.test(stockInput) && Number(stockInput) >= 1;
    if (centavosTexto === null || Number(centavosTexto) <= 0) nuevosErrores.precio = 'Ingresa un precio válido en soles mayor a 0 y con hasta dos decimales';
    if (!stockValido) nuevosErrores.stock = 'El stock debe ser al menos 1';
    if (!categoriaId || !subcategoriaId) nuevosErrores.general = 'Selecciona una categoría con subcategorías disponibles';
    if (Object.keys(nuevosErrores).length > 0) {
      setErrors(nuevosErrores);
      return;
    }

    setErrors({});
    setIsSubmitting(true);
    const baseUrl = process.env.NEXT_PUBLIC_API_URL || '';
    try {
      const response = await fetch(`${baseUrl}/publicaciones`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({
          precio: Number(centavosTexto),
          stock: Number(stockInput),
          categoriaId: Number(categoriaId),
          subcategoriaId: Number(subcategoriaId),
          descripcion,
          imagenFilename: imagenFilename
        })
      });
      if (!response.ok) {
        const data: unknown = await response.json().catch(() => null);
        const mensaje = data && typeof data === 'object' && 'mensaje' in data && typeof data.mensaje === 'string'
          ? data.mensaje : `Error al crear publicación (código ${response.status})`;
        setErrors({ general: mensaje });
        return;
      }
      onSuccess?.();
    } catch {
      setErrors({ general: 'Error de red al conectar con el servidor' });
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <form onSubmit={handleSubmit} className="mx-auto max-w-xl space-y-6 rounded-lg border border-slate-200 bg-white p-6">
      <h1 className="text-2xl font-bold tracking-tight text-[#0F172A]">Publicar Producto o Servicio</h1>
      {cargandoCategorias && <p role="status" aria-busy="true" className="text-sm text-slate-600">Cargando categorías...</p>}
      {errors.general && <p role="alert" className="rounded border border-[#ba1a1a] bg-[#ffdad6] p-3 text-sm font-medium text-[#93000a]">{errors.general}</p>}
      <label className="block text-sm text-slate-700">Precio (S/)
        <input aria-label="Precio (S/)" inputMode="decimal" value={precioInput} onChange={(event) => setPrecioInput(event.target.value)} className="mt-1 w-full rounded border border-slate-300 bg-white px-3 py-2 font-mono text-[#0F172A]" />
        {errors.precio && <span className="mt-1 block text-xs text-red-600">{errors.precio}</span>}
      </label>
      <label className="block text-sm text-slate-700">Stock
        <input aria-label="Stock" inputMode="numeric" value={stockInput} onChange={(event) => setStockInput(event.target.value)} className="mt-1 w-full rounded border border-slate-300 bg-white px-3 py-2 font-mono text-[#0F172A]" />
        {errors.stock && <span className="mt-1 block text-xs text-red-600">{errors.stock}</span>}
      </label>
      <label className="block text-sm text-slate-700">Categoría
        <select aria-label="Categoría" value={categoriaId} onChange={handleCategoriaChange} disabled={cargandoCategorias || categorias.length === 0} className="mt-1 w-full rounded border border-slate-300 bg-white px-3 py-2 text-[#0F172A]">
          {categorias.map((categoria) => <option key={categoria.id} value={categoria.id}>{categoria.nombre}</option>)}
        </select>
      </label>
      <label className="block text-sm text-slate-700">Subcategoría
        <select aria-label="Subcategoría" value={subcategoriaId} onChange={(event) => setSubcategoriaId(event.target.value)} disabled={!categoriaSeleccionada?.subcategorias.length} className="mt-1 w-full rounded border border-slate-300 bg-white px-3 py-2 text-[#0F172A]">
          {categoriaSeleccionada?.subcategorias.map((subcategoria) => <option key={subcategoria.id} value={subcategoria.id}>{subcategoria.nombre}</option>)}
        </select>
      </label>
      <label className="block text-sm text-slate-700">Descripción
        <textarea aria-label="Descripción" rows={4} value={descripcion} onChange={(event) => setDescripcion(event.target.value)} className="mt-1 w-full rounded border border-slate-300 bg-white px-3 py-2 text-[#0F172A]" />
      </label>
      <label className="block text-sm text-slate-700">Imagen (archivo)
        <input
          aria-label="Imagen (archivo)"
          placeholder="producto.jpg"
          value={imagenFilename}
          onChange={(event) => setImagenFilename(event.target.value)}
          className="mt-1 w-full rounded border border-slate-300 bg-white px-3 py-2 text-[#0F172A]"
        />
      </label>
      <button type="submit" disabled={cargandoCategorias || !catalogoRespondio || isSubmitting} className="w-full rounded border border-slate-900 bg-[#0F172A] px-4 py-2.5 text-sm font-semibold text-white disabled:opacity-50">{isSubmitting ? 'Publicando...' : 'Publicar'}</button>
    </form>
  );
}
