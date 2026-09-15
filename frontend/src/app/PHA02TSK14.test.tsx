import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import PanelCategorias from './PanelCategorias';
import EditarPublicacionForm from './EditarPublicacionForm';

/** Árbol inyectado para conservar las regresiones sin depender de mocks de producción. */
const CATEGORIAS = [{ id: 1, nombre: 'Electrónica', subcategorias: [{ id: 101, nombre: 'Smartphones' }] }];

/**
 * @file PHA02TSK14.test.tsx
 * @description Pruebas de componente para PHA02TSK14:
 * Parte A: PanelCategorias (gestión de categorías/subcategorías por admin).
 * Parte B: EditarPublicacionForm (edición de publicación propia por vendedor).
 *
 * Cobertura TDD:
 * Parte A:
 * 1. Envía creación de categoría a POST {NEXT_PUBLIC_API_URL}/categorias.
 * 2. Maneja 409 NombreCategoriaDuplicadoException.
 * 3. Acción de eliminar maneja 409 CategoriaConPublicacionesException.
 * Parte B (Criterio explícito de tasks.md):
 * 1. Bloquea edición de categoría/subcategoría en publicación aprobada (campos de categoría/subcategoría son de solo lectura).
 * 2. Envía edición de precio/stock/descripción a PATCH {NEXT_PUBLIC_API_URL}/publicaciones/{id}.
 * 3. Maneja 409 EstadoPublicacionNoEditableException o 403 NoEsElPropietarioException.
 */

describe('PHA02TSK14 - Parte A: PanelCategorias', () => {
  const originalEnv = process.env.NEXT_PUBLIC_API_URL;

  beforeEach(() => {
    vi.restoreAllMocks();
    process.env.NEXT_PUBLIC_API_URL = 'http://localhost:8080';
  });

  afterEach(() => {
    process.env.NEXT_PUBLIC_API_URL = originalEnv;
  });

  it('envía creación de categoría exitosamente a POST /categorias', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 201,
      json: async () => ({ id: 3, nombre: 'Deportes', subcategorias: [] })
    });
    global.fetch = fetchMock;

    render(<PanelCategorias categoriasIniciales={CATEGORIAS} />);

    const nombreInput = screen.getByLabelText(/nombre de la nueva categoría/i);
    const submitBtn = screen.getByRole('button', { name: /crear categoría/i });

    fireEvent.change(nombreInput, { target: { value: 'Deportes' } });
    fireEvent.click(submitBtn);

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/categorias', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ nombre: 'Deportes' })
      });
    });

    expect(await screen.findByText(/categoría creada exitosamente/i)).toBeInTheDocument();
  });

  it('muestra mensaje de error 409 cuando se intenta eliminar una categoría con publicaciones asociadas', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 409,
      json: async () => ({
        mensaje: 'CategoriaConPublicacionesException: No se puede eliminar la categoría porque tiene publicaciones asociadas'
      })
    });
    global.fetch = fetchMock;

    render(<PanelCategorias categoriasIniciales={CATEGORIAS} />);

    const eliminarBtn = screen.getByRole('button', { name: /eliminar categoría Electrónica/i });
    fireEvent.click(eliminarBtn);

    expect(await screen.findByText(/No se puede eliminar la categoría porque tiene publicaciones asociadas/i)).toBeInTheDocument();
  });
});

describe('PHA02TSK14 - Parte B: EditarPublicacionForm', () => {
  const originalEnv = process.env.NEXT_PUBLIC_API_URL;

  const publicacionAprobadaMock = {
    id: 5,
    usuarioId: 1,
    categoriaId: 1,
    categoriaNombre: 'Electrónica',
    subcategoriaId: 101,
    subcategoriaNombre: 'Smartphones',
    precio: 20000, // $200.00
    stock: 10,
    descripcion: 'Teléfono celular en perfectas condiciones',
    estado: 'APROBADA',
    imagenFilename: ''
  };

  beforeEach(() => {
    vi.restoreAllMocks();
    process.env.NEXT_PUBLIC_API_URL = 'http://localhost:8080';
  });

  afterEach(() => {
    process.env.NEXT_PUBLIC_API_URL = originalEnv;
  });

  it('bloquea la edición de categoría y subcategoría mostrando datos como solo lectura en publicación APROBADA (criterio de acceptance tasks.md)', () => {
    render(<EditarPublicacionForm publicacionInicial={publicacionAprobadaMock} />);

    // Verificar que categoría y subcategoría NO se muestran como inputs o selects editables
    expect(screen.queryByLabelText(/^categoría$/i)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/^subcategoría$/i)).not.toBeInTheDocument();

    // Confirmar que se muestran en badges/elementos de texto de solo lectura
    expect(screen.getByText(/^categoría \(inmutable\):$/i)).toBeInTheDocument();
    expect(screen.getByText('Electrónica')).toBeInTheDocument();
    expect(screen.getByText(/^subcategoría \(inmutable\):$/i)).toBeInTheDocument();
    expect(screen.getByText('Smartphones')).toBeInTheDocument();

    // Confirmar que precio, stock y descripción SÍ son editables
    expect(screen.getByLabelText(/precio/i)).toBeEnabled();
    expect(screen.getByLabelText(/stock/i)).toBeEnabled();
    expect(screen.getByLabelText(/descripción/i)).toBeEnabled();
  });

  it('envía los cambios de precio, stock y descripción a PATCH /publicaciones/{id} con datos en centavos', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({
        ...publicacionAprobadaMock,
        precio: 18000, // $180.00
        stock: 8,
        descripcion: 'Teléfono celular con descuento'
      })
    });
    global.fetch = fetchMock;

    const onSuccessMock = vi.fn();
    render(<EditarPublicacionForm publicacionInicial={publicacionAprobadaMock} onSuccess={onSuccessMock} />);

    fireEvent.change(screen.getByLabelText(/precio/i), { target: { value: '180.00' } });
    fireEvent.change(screen.getByLabelText(/stock/i), { target: { value: '8' } });
    fireEvent.change(screen.getByLabelText(/descripción/i), { target: { value: 'Teléfono celular con descuento' } });

    fireEvent.click(screen.getByRole('button', { name: /guardar cambios/i }));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/publicaciones/5', {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({
          precio: 18000,
          stock: 8,
          descripcion: 'Teléfono celular con descuento',
          imagenFilename: ''
        })
      });
    });

    expect(onSuccessMock).toHaveBeenCalled();
  });

  it('muestra mensaje de error 409 cuando la publicación se encuentra en estado no editable', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 409,
      json: async () => ({
        mensaje: 'EstadoPublicacionNoEditableException: La publicación no se encuentra en estado editable (APROBADA u OCULTA)'
      })
    });
    global.fetch = fetchMock;

    render(<EditarPublicacionForm publicacionInicial={publicacionAprobadaMock} />);

    fireEvent.click(screen.getByRole('button', { name: /guardar cambios/i }));

    expect(await screen.findByText(/EstadoPublicacionNoEditableException/i)).toBeInTheDocument();
  });
});

