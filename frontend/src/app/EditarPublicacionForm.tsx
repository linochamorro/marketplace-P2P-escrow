'use client';

import React, { useState } from 'react';

/**
 * Interface para representar los datos de una publicación a editar.
 */
export interface PublicacionAEditar {
  id: number;
  usuarioId: number;
  categoriaId: number;
  categoriaNombre: string;
  subcategoriaId: number;
  subcategoriaNombre: string;
  precio: number; // En centavos
  stock: number;
  descripcion: string;
  estado: string;
  /** Nombre de archivo de imagen (sin prefijo de URL); cadena vacía cuando no hay imagen (PHA09TSK04). */
  imagenFilename?: string | null;
}

/**
 * Interface de props para EditarPublicacionForm.
 */
export interface EditarPublicacionFormProps {
  /** Publicación inicial a editar */
  publicacionInicial: PublicacionAEditar;
  /** Callback opcional ejecutado al guardar exitosamente la edición */
  onSuccess?: () => void;
  /** Callback opcional ejecutado al cancelar; cierra el panel sin enviar PATCH (PHA09TSK04) */
  onCancel?: () => void;
}

/**
 * Componente EditarPublicacionForm (PHA02TSK14 - Parte B).
 * Formulario de edición de publicación propia por su vendedor según Stories 3 y 10 de spec.md y DESIGN.md.
 * Criterio explícito de acceptance (tasks.md): Bloquea la edición de categoría y subcategoría en publicaciones APROBADAS (se muestran como inmutables / solo lectura).
 *
 * @param props Props del componente {@link EditarPublicacionFormProps}
 * @returns Elemento JSX con el formulario de edición de publicación
 */
export default function EditarPublicacionForm({ publicacionInicial, onSuccess, onCancel }: EditarPublicacionFormProps) {
  const [precioInput, setPrecioInput] = useState<string>((publicacionInicial.precio / 100).toFixed(2));
  const [stockInput, setStockInput] = useState<string>(publicacionInicial.stock.toString());
  const [descripcion, setDescripcion] = useState<string>(publicacionInicial.descripcion);
  const [imagenFilename, setImagenFilename] = useState<string>(publicacionInicial.imagenFilename ?? '');

  const [errors, setErrors] = useState<{ precio?: string; stock?: string; general?: string }>({});
  const [exito, setExito] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState<boolean>(false);

  /**
   * Valida localmente y envía los datos modificados a PATCH /publicaciones/{id}.
   */
  const handleSubmit = async (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    setErrors({});
    setExito(null);

    const newErrors: { precio?: string; stock?: string } = {};
    const precioNumerico = parseFloat(precioInput);
    const stockNumerico = parseInt(stockInput, 10);

    if (isNaN(precioNumerico) || precioNumerico <= 0) {
      newErrors.precio = 'El precio debe ser mayor a 0';
    }

    if (isNaN(stockNumerico) || stockNumerico < 0) {
      newErrors.stock = 'El stock debe ser al menos 0';
    }

    if (Object.keys(newErrors).length > 0) {
      setErrors(newErrors);
      return;
    }

    setIsSubmitting(true);
    const precioEnCentavos = Math.round(precioNumerico * 100);

    const payload = {
      precio: precioEnCentavos,
      stock: stockNumerico,
      descripcion,
      imagenFilename
    };

    const baseUrl = process.env.NEXT_PUBLIC_API_URL || '';
    const endpoint = `${baseUrl}/publicaciones/${publicacionInicial.id}`;

    try {
      const response = await fetch(endpoint, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify(payload)
      });

      if (!response.ok) {
        const errorData = await response.json().catch(() => null);
        const mensajeBackend = errorData?.mensaje || `Error al guardar edición (código ${response.status})`;
        setErrors({ general: mensajeBackend });
      } else {
        setExito('Publicación actualizada exitosamente');
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

  return (
    <form
      onSubmit={handleSubmit}
      className="max-w-xl mx-auto p-6 bg-white border border-slate-200 rounded-lg shadow-none space-y-6"
    >
      <div className="border-b border-slate-200 pb-3">
        <h2 className="text-2xl font-bold text-[#0F172A] tracking-tight">Editar Publicación #{publicacionInicial.id}</h2>
        <p className="text-xs text-slate-500 uppercase font-semibold tracking-wider mt-1">
          Estado actual: <span className="text-[#0F172A]">{publicacionInicial.estado}</span>
        </p>
      </div>

      {errors.general && (
        <div className="p-3 bg-[#ffdad6] border border-[#ba1a1a] text-[#93000a] rounded text-sm font-medium">
          {errors.general}
        </div>
      )}

      {exito && (
        <div className="p-3 bg-[#d1fae5] border border-[#10B981] text-[#065f46] rounded text-sm font-medium">
          {exito}
        </div>
      )}

      {/* Categoría y Subcategoría (Solo Lectura / Inmutables segun Story 3) */}
      <div className="p-3 bg-slate-50 border border-slate-200 rounded space-y-1">
        <div className="text-xs font-semibold text-slate-700 uppercase tracking-wider">
          Categoría (inmutable): <span className="font-normal text-[#0F172A]">{publicacionInicial.categoriaNombre}</span>
        </div>
        <div className="text-xs font-semibold text-slate-700 uppercase tracking-wider">
          Subcategoría (inmutable): <span className="font-normal text-[#0F172A]">{publicacionInicial.subcategoriaNombre}</span>
        </div>
        <p className="text-[11px] text-slate-500 italic mt-1">
          Las categorías de una publicación aprobada no pueden modificarse.
        </p>
      </div>

      {/* Precio Field */}
      <div>
        <label htmlFor="edit-precio" className="block text-xs font-semibold uppercase tracking-wider text-slate-700 mb-1">
          Precio (S/)
        </label>
        <input
          id="edit-precio"
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
        <label htmlFor="edit-stock" className="block text-xs font-semibold uppercase tracking-wider text-slate-700 mb-1">
          Stock
        </label>
        <input
          id="edit-stock"
          type="number"
          step="1"
          placeholder="0"
          value={stockInput}
          onChange={(e) => setStockInput(e.target.value)}
          className={`w-full px-3 py-2 bg-white border rounded text-sm text-[#0F172A] focus:outline-none focus:ring-2 focus:ring-slate-200 focus:border-[#0F172A] font-mono ${
            errors.stock ? 'border-red-500' : 'border-slate-300'
          }`}
        />
        {errors.stock && <p className="mt-1 text-xs text-red-600">{errors.stock}</p>}
      </div>

      {/* Descripcion Field */}
      <div>
        <label htmlFor="edit-descripcion" className="block text-xs font-semibold uppercase tracking-wider text-slate-700 mb-1">
          Descripción
        </label>
        <textarea
          id="edit-descripcion"
          rows={4}
          placeholder="Describe los detalles de tu producto..."
          value={descripcion}
          onChange={(e) => setDescripcion(e.target.value)}
          className="w-full px-3 py-2 bg-white border border-slate-300 rounded text-sm text-[#0F172A] focus:outline-none focus:ring-2 focus:ring-slate-200 focus:border-[#0F172A]"
        />
      </div>

      {/* Imagen Field (PHA09TSK04) */}
      <div>
        <label htmlFor="edit-imagen" className="block text-xs font-semibold uppercase tracking-wider text-slate-700 mb-1">
          Imagen (archivo)
        </label>
        <input
          id="edit-imagen"
          type="text"
          aria-label="Imagen (archivo)"
          placeholder="producto.jpg"
          value={imagenFilename}
          onChange={(e) => setImagenFilename(e.target.value)}
          className="w-full px-3 py-2 bg-white border border-slate-300 rounded text-sm text-[#0F172A] focus:outline-none focus:ring-2 focus:ring-slate-200 focus:border-[#0F172A]"
        />
      </div>

      {/* Action Buttons */}
      <div className="flex gap-3">
        <button
          type="submit"
          disabled={isSubmitting}
          className="flex-1 py-2.5 px-4 bg-[#0F172A] hover:bg-slate-800 text-white font-medium rounded text-sm transition-colors border border-slate-900 disabled:opacity-50"
        >
          {isSubmitting ? 'Guardando...' : 'Guardar Cambios'}
        </button>
        {onCancel && (
          <button
            type="button"
            onClick={onCancel}
            className="flex-1 py-2.5 px-4 bg-white hover:bg-slate-50 text-black font-medium rounded text-sm transition-colors border border-slate-300"
          >
            Cancelar
          </button>
        )}
      </div>
    </form>
  );
}
