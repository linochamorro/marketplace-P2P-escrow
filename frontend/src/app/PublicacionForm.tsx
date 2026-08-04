'use client';

import React, { useState } from 'react';

/**
 * Interface para las props del componente PublicacionForm.
 */
export interface PublicacionFormProps {
  /** Callback opcional ejecutado al crear exitosamente la publicación */
  onSuccess?: () => void;
}

/**
 * Interface de errores de validación del cliente.
 */
interface FormErrors {
  precio?: string;
  stock?: string;
  general?: string;
}

/**
 * Datos estáticos temporales para categorías y subcategorías.
 * TODO: reemplazar con GET /categorias real cuando exista esa tarea de API — ver PHA02TSK12, riesgo declarado.
 */
const CATEGORIAS_MOCK = [
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
 * Componente PublicacionForm (PHA02TSK12).
 * Formulario de creación de publicación para el vendedor según Story 1 de spec.md y sistema de diseño DESIGN.md.
 *
 * @param props Props del componente {@link PublicacionFormProps}
 * @returns Elemento JSX con el formulario de publicación
 */
export default function PublicacionForm({ onSuccess }: PublicacionFormProps) {
  const [precioInput, setPrecioInput] = useState<string>('');
  const [stockInput, setStockInput] = useState<string>('1');
  const [categoriaId, setCategoriaId] = useState<number>(CATEGORIAS_MOCK[0].id);
  const [subcategoriaId, setSubcategoriaId] = useState<number>(CATEGORIAS_MOCK[0].subcategorias[0].id);
  const [descripcion, setDescripcion] = useState<string>('');

  const [errors, setErrors] = useState<FormErrors>({});
  const [isSubmitting, setIsSubmitting] = useState<boolean>(false);

  /**
   * Maneja el cambio de categoría seleccionada y actualiza automáticamente la subcategoría seleccionada a la primera disponible.
   */
  const handleCategoriaChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const catId = Number(e.target.value);
    setCategoriaId(catId);
    const catEncontrada = CATEGORIAS_MOCK.find((c) => c.id === catId);
    if (catEncontrada && catEncontrada.subcategorias.length > 0) {
      setSubcategoriaId(catEncontrada.subcategorias[0].id);
    }
  };

  /**
   * Valida el formulario del lado del cliente y realiza la petición HTTP POST al backend.
   */
  const handleSubmit = async (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    setErrors({});

    const newErrors: FormErrors = {};
    const precioNumerico = parseFloat(precioInput);
    const stockNumerico = parseInt(stockInput, 10);

    // Validación de precio: > 0 (Story 1)
    if (isNaN(precioNumerico) || precioNumerico <= 0) {
      newErrors.precio = 'El precio debe ser mayor a 0';
    }

    // Validación de stock: >= 1 (Story 1)
    if (isNaN(stockNumerico) || stockNumerico < 1) {
      newErrors.stock = 'El stock debe ser al menos 1';
    }

    if (Object.keys(newErrors).length > 0) {
      setErrors(newErrors);
      return;
    }

    setIsSubmitting(true);

    // Decisión de UX: Convertir precio flotante ingresado (ej. 15.00) a centavos (1500) según constitution principio 3 ANTES de construir el payload del fetch
    const precioEnCentavos = Math.round(precioNumerico * 100);

    const payload = {
      precio: precioEnCentavos,
      stock: stockNumerico,
      categoriaId,
      subcategoriaId,
      descripcion
    };

    // Obtener la URL base del backend desde la variable de entorno NEXT_PUBLIC_API_URL
    const baseUrl = process.env.NEXT_PUBLIC_API_URL || '';
    const endpoint = `${baseUrl}/publicaciones`;

    try {
      const response = await fetch(endpoint, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        credentials: 'include',
        body: JSON.stringify(payload)
      });

      if (!response.ok) {
        const errorData = await response.json().catch(() => null);
        // Extrae el campo 'mensaje' devuelto por GlobalExceptionHandler del backend
        const mensajeBackend = errorData?.mensaje || `Error del servidor (código ${response.status})`;
        setErrors({ general: mensajeBackend });
      } else {
        if (onSuccess) {
          onSuccess();
        }
      }
    } catch (err) {
      setErrors({ general: 'Error de red al conectar con el servidor' });
    } finally {
      setIsSubmitting(false);
    }
  };

  const categoriaSeleccionada = CATEGORIAS_MOCK.find((c) => c.id === categoriaId);

  return (
    <form
      onSubmit={handleSubmit}
      className="max-w-xl mx-auto p-6 bg-white border border-slate-200 rounded-lg shadow-none space-y-6"
    >
      <h2 className="text-2xl font-bold text-[#0F172A] tracking-tight">Publicar Producto o Servicio</h2>

      {errors.general && (
        <div className="p-3 bg-[#ffdad6] border border-[#ba1a1a] text-[#93000a] rounded text-sm font-medium">
          {errors.general}
        </div>
      )}

      {/* Precio Field */}
      <div>
        <label htmlFor="precio" className="block text-xs font-semibold uppercase tracking-wider text-slate-700 mb-1">
          Precio (USD)
        </label>
        <input
          id="precio"
          type="number"
          step="0.01"
          placeholder="0.00"
          value={precioInput}
          onChange={(e) => setPrecioInput(e.target.value)}
          className={`w-full px-3 py-2 bg-white border rounded text-sm text-[#0F172A] focus:outline-none focus:ring-2 focus:ring-slate-200 focus:border-[#0F172A] font-mono ${
            errors.precio ? 'border-red-500' : 'border-slate-300'
          }`}
        />
        {errors.precio && <p className="mt-1 text-xs text-red-600">{errors.precio}</p>}
      </div>

      {/* Stock Field */}
      <div>
        <label htmlFor="stock" className="block text-xs font-semibold uppercase tracking-wider text-slate-700 mb-1">
          Stock
        </label>
        <input
          id="stock"
          type="number"
          step="1"
          placeholder="1"
          value={stockInput}
          onChange={(e) => setStockInput(e.target.value)}
          className={`w-full px-3 py-2 bg-white border rounded text-sm text-[#0F172A] focus:outline-none focus:ring-2 focus:ring-slate-200 focus:border-[#0F172A] font-mono ${
            errors.stock ? 'border-red-500' : 'border-slate-300'
          }`}
        />
        {errors.stock && <p className="mt-1 text-xs text-red-600">{errors.stock}</p>}
      </div>

      {/* Categoria Selector */}
      <div>
        <label htmlFor="categoria" className="block text-xs font-semibold uppercase tracking-wider text-slate-700 mb-1">
          Categoría
        </label>
        <select
          id="categoria"
          value={categoriaId}
          onChange={handleCategoriaChange}
          className="w-full px-3 py-2 bg-white border border-slate-300 rounded text-sm text-[#0F172A] focus:outline-none focus:ring-2 focus:ring-slate-200 focus:border-[#0F172A]"
        >
          {CATEGORIAS_MOCK.map((cat) => (
            <option key={cat.id} value={cat.id}>
              {cat.nombre}
            </option>
          ))}
        </select>
      </div>

      {/* Subcategoria Selector */}
      <div>
        <label htmlFor="subcategoria" className="block text-xs font-semibold uppercase tracking-wider text-slate-700 mb-1">
          Subcategoría
        </label>
        <select
          id="subcategoria"
          value={subcategoriaId}
          onChange={(e) => setSubcategoriaId(Number(e.target.value))}
          className="w-full px-3 py-2 bg-white border border-slate-300 rounded text-sm text-[#0F172A] focus:outline-none focus:ring-2 focus:ring-slate-200 focus:border-[#0F172A]"
        >
          {categoriaSeleccionada?.subcategorias.map((sub) => (
            <option key={sub.id} value={sub.id}>
              {sub.nombre}
            </option>
          ))}
        </select>
      </div>

      {/* Descripcion Field */}
      <div>
        <label htmlFor="descripcion" className="block text-xs font-semibold uppercase tracking-wider text-slate-700 mb-1">
          Descripción
        </label>
        <textarea
          id="descripcion"
          rows={4}
          placeholder="Describe los detalles de tu producto..."
          value={descripcion}
          onChange={(e) => setDescripcion(e.target.value)}
          className="w-full px-3 py-2 bg-white border border-slate-300 rounded text-sm text-[#0F172A] focus:outline-none focus:ring-2 focus:ring-slate-200 focus:border-[#0F172A]"
        />
      </div>

      {/* Submit Button */}
      <button
        type="submit"
        disabled={isSubmitting}
        className="w-full py-2.5 px-4 bg-[#0F172A] hover:bg-slate-800 text-white font-medium rounded text-sm transition-colors border border-slate-900 disabled:opacity-50"
      >
        {isSubmitting ? 'Publicando...' : 'Publicar'}
      </button>
    </form>
  );
}
