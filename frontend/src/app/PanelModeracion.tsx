'use client';

import React, { useState } from 'react';

/**
 * Interface para representar una publicación en lista de moderación.
 */
export interface PublicacionPendiente {
  id: number;
  usuarioId: number;
  categoriaNombre: string;
  subcategoriaNombre: string;
  precio: number; // En centavos
  stock: number;
  descripcion: string;
  estado: string;
}

/**
 * Interface de props para PanelModeracion.
 */
export interface PanelModeracionProps {
  /** Lista opcional de publicaciones a moderar (por defecto usa PUBLICACIONES_PENDIENTES_MOCK) */
  publicacionesIniciales?: PublicacionPendiente[];
}

/**
 * Datos estáticos temporales para la lista de publicaciones pendientes de revisión.
 * TODO: reemplazar con GET /publicaciones?estado=PENDIENTE_REVISION real cuando exista esa tarea de API — ver PHA02TSK13, riesgo declarado.
 */
const PUBLICACIONES_PENDIENTES_MOCK: PublicacionPendiente[] = [
  {
    id: 1,
    usuarioId: 10,
    categoriaNombre: 'Electrónica',
    subcategoriaNombre: 'Smartphones',
    precio: 25000, // $250.00
    stock: 3,
    descripcion: 'Smartphone desbloqueado de alta gama en excelente estado.',
    estado: 'PENDIENTE_REVISION'
  },
  {
    id: 2,
    usuarioId: 11,
    categoriaNombre: 'Hogar',
    subcategoriaNombre: 'Muebles',
    precio: 15000, // $150.00
    stock: 1,
    descripcion: 'Silla ergonómica para oficina de color negro.',
    estado: 'PENDIENTE_REVISION'
  }
];

/**
 * Componente PanelModeracion (PHA02TSK13).
 * Panel de moderación para administradores que permite aprobar, solicitar cambios o rechazar publicaciones según Story 2 de spec.md y DESIGN.md.
 *
 * @param props Props del componente {@link PanelModeracionProps}
 * @returns Elemento JSX con el panel de administración para moderación de publicaciones
 */
export default function PanelModeracion({ publicacionesIniciales }: PanelModeracionProps) {
  const [publicaciones, setPublicaciones] = useState<PublicacionPendiente[]>(
    publicacionesIniciales || PUBLICACIONES_PENDIENTES_MOCK
  );

  const [motivos, setMotivos] = useState<{ [pubId: number]: string }>({});
  const [errors, setErrors] = useState<{ [pubId: number]: string }>({});
  const [generalMessage, setGeneralMessage] = useState<{ text: string; isError: boolean } | null>(null);
  const [submittingId, setSubmittingId] = useState<number | null>(null);

  /**
   * Actualiza el motivo ingresado por el administrador para una publicación específica.
   */
  const handleMotivoChange = (pubId: number, value: string) => {
    setMotivos((prev) => ({ ...prev, [pubId]: value }));
    setErrors((prev) => ({ ...prev, [pubId]: '' }));
  };

  /**
   * Ejecuta la moderaciones enviando la solicitud PATCH /publicaciones/{id}/moderar al backend.
   * Valida en el cliente que si la acción es "rechazar" o "solicitar-cambios", exista un motivo no vacío.
   */
  const handleModerar = async (pubId: number, accion: 'aprobar' | 'solicitar-cambios' | 'rechazar') => {
    setErrors((prev) => ({ ...prev, [pubId]: '' }));
    setGeneralMessage(null);

    const motivoText = (motivos[pubId] || '').trim();

    // Validación de cliente: exige motivo para solicitar-cambios y rechazar (Story 2)
    if ((accion === 'solicitar-cambios' || accion === 'rechazar') && !motivoText) {
      setErrors((prev) => ({ ...prev, [pubId]: 'El motivo es obligatorio para esta acción' }));
      return;
    }

    setSubmittingId(pubId);

    const payload: { accion: string; motivo?: string } = { accion };
    if (accion === 'solicitar-cambios' || accion === 'rechazar') {
      payload.motivo = motivoText;
    }

    const baseUrl = process.env.NEXT_PUBLIC_API_URL || '';
    const endpoint = `${baseUrl}/publicaciones/${pubId}/moderar`;

    try {
      const response = await fetch(endpoint, {
        method: 'PATCH',
        headers: {
          'Content-Type': 'application/json'
        },
        credentials: 'include',
        body: JSON.stringify(payload)
      });

      if (!response.ok) {
        const errorData = await response.json().catch(() => null);
        let errorMsg = errorData?.mensaje || `Error al moderar publicación (código ${response.status})`;

        if (response.status === 409) {
          errorMsg = 'Esta publicación ya fue moderada previa o no se encuentra pendiente de revisión';
        }

        setGeneralMessage({ text: errorMsg, isError: true });
      } else {
        setGeneralMessage({ text: 'Publicación moderada exitosamente', isError: false });
        // Remover la publicación moderada de la lista
        setPublicaciones((prev) => prev.filter((p) => p.id !== pubId));
      }
    } catch (err) {
      setGeneralMessage({ text: 'Error de red al conectar con el servidor', isError: true });
    } finally {
      setSubmittingId(null);
    }
  };

  return (
    <div className="max-w-4xl mx-auto p-6 space-y-6">
      <div className="border-b border-slate-200 pb-4">
        <h1 className="text-3xl font-bold text-[#0F172A] tracking-tight">Panel de Moderaciones (Admin)</h1>
        <p className="text-sm text-slate-600 mt-1">
          Revisa y modera las publicaciones pendientes en el marketplace.
        </p>
      </div>

      {generalMessage && (
        <div
          className={`p-3 rounded text-sm font-medium border ${
            generalMessage.isError
              ? 'bg-[#ffdad6] border-[#ba1a1a] text-[#93000a]'
              : 'bg-[#d1fae5] border-[#10B981] text-[#065f46]'
          }`}
        >
          {generalMessage.text}
        </div>
      )}

      {publicaciones.length === 0 ? (
        <div className="p-8 text-center bg-white border border-slate-200 rounded-lg text-slate-600">
          No hay publicaciones pendientes de revisión.
        </div>
      ) : (
        <div className="space-y-6">
          {publicaciones.map((pub) => (
            <div key={pub.id} className="bg-white border border-slate-200 rounded-lg p-6 space-y-4 shadow-none">
              <div className="flex justify-between items-start">
                <div>
                  <span className="inline-block px-2.5 py-0.5 text-xs font-semibold uppercase tracking-wider bg-slate-100 text-slate-800 rounded">
                    {pub.categoriaNombre} / {pub.subcategoriaNombre}
                  </span>
                  <h3 className="text-lg font-semibold text-[#0F172A] mt-2">Publicación #{pub.id}</h3>
                </div>
                <div className="text-right">
                  <div className="text-lg font-bold font-mono text-[#0F172A]">
                    ${(pub.precio / 100).toFixed(2)} USD
                  </div>
                  <div className="text-xs font-mono text-slate-500">Stock: {pub.stock} unidades</div>
                </div>
              </div>

              <p className="text-sm text-slate-700 bg-slate-50 p-3 border border-slate-100 rounded">
                {pub.descripcion}
              </p>

              {/* Campo Motivo */}
              <div>
                <label
                  htmlFor={`motivo-${pub.id}`}
                  className="block text-xs font-semibold uppercase tracking-wider text-slate-700 mb-1"
                >
                  Motivo (Obligatorio para Rechazar / Solicitar Cambios)
                </label>
                <input
                  id={`motivo-${pub.id}`}
                  type="text"
                  placeholder="Motivo de rechazo o solicitud de cambio..."
                  value={motivos[pub.id] || ''}
                  onChange={(e) => handleMotivoChange(pub.id, e.target.value)}
                  className={`w-full px-3 py-2 bg-white border rounded text-sm text-[#0F172A] focus:outline-none focus:ring-2 focus:ring-slate-200 focus:border-[#0F172A] ${
                    errors[pub.id] ? 'border-red-500' : 'border-slate-300'
                  }`}
                />
                {errors[pub.id] && <p className="mt-1 text-xs text-red-600">{errors[pub.id]}</p>}
              </div>

              {/* Acciones de Moderación */}
              <div className="flex flex-wrap gap-3 pt-2">
                {/* Botón Aprobar: Emerald (#10B981) exclusivo para éxito/confirmación positiva */}
                <button
                  type="button"
                  disabled={submittingId === pub.id}
                  onClick={() => handleModerar(pub.id, 'aprobar')}
                  className="px-4 py-2 bg-[#10B981] hover:bg-emerald-600 text-white font-medium rounded text-sm border border-emerald-700 transition-colors disabled:opacity-50"
                >
                  Aprobar
                </button>

                {/* Botón Solicitar Cambios: Ambar/Slate neutral de advertencia */}
                <button
                  type="button"
                  disabled={submittingId === pub.id}
                  onClick={() => handleModerar(pub.id, 'solicitar-cambios')}
                  className="px-4 py-2 bg-amber-600 hover:bg-amber-700 text-white font-medium rounded text-sm border border-amber-800 transition-colors disabled:opacity-50"
                >
                  Solicitar Cambios
                </button>

                {/* Botón Rechazar: Rojo/Error (#ba1a1a) */}
                <button
                  type="button"
                  disabled={submittingId === pub.id}
                  onClick={() => handleModerar(pub.id, 'rechazar')}
                  className="px-4 py-2 bg-[#ba1a1a] hover:bg-red-800 text-white font-medium rounded text-sm border border-red-900 transition-colors disabled:opacity-50"
                >
                  Rechazar
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
