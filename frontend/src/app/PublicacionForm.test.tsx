import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import PublicacionForm from './PublicacionForm';

/** Árbol real usado para verificar IDs y dependencia entre selectores. */
const CATEGORIAS = [
  { id: 7, nombre: 'Servicios', subcategorias: [{ id: 71, nombre: 'Clases' }] },
  { id: 9, nombre: 'Hogar', subcategorias: [{ id: 91, nombre: 'Muebles' }, { id: 92, nombre: 'Decoración' }] }
];

/**
 * Pruebas del formulario navegable de creación integrado en PHA06TSK10.
 * Verifican catálogo real, IDs reales, centavos por cadenas, cookie y errores honestos.
 */
describe('PublicacionForm (PHA06TSK10)', () => {
  const originalEnv = process.env.NEXT_PUBLIC_API_URL;

  beforeEach(() => {
    vi.restoreAllMocks();
    process.env.NEXT_PUBLIC_API_URL = 'http://localhost:8080';
  });

  afterEach(() => {
    process.env.NEXT_PUBLIC_API_URL = originalEnv;
  });

  it('carga GET /categorias y actualiza la subcategoría dependiente con IDs reales', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => CATEGORIAS });
    global.fetch = fetchMock;
    render(<PublicacionForm />);

    expect(screen.getByRole('status')).toHaveTextContent('Cargando categorías');
    expect(await screen.findByRole('option', { name: 'Servicios' })).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/categorias', { method: 'GET', credentials: 'include' });

    fireEvent.change(screen.getByLabelText(/^categoría$/i), { target: { value: '9' } });
    expect(screen.getByRole('option', { name: 'Muebles' })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: 'Clases' })).not.toBeInTheDocument();
  });

  it('salta una primera categoría vacía, selecciona la primera pareja real y publica sus IDs', async () => {
    const categoriasConPrimeraVacia = [
      { id: 4, nombre: 'Sin clasificación', subcategorias: [] },
      { id: 9, nombre: 'Hogar', subcategorias: [{ id: 91, nombre: 'Muebles' }] }
    ];
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => categoriasConPrimeraVacia })
      .mockResolvedValueOnce({ ok: true, status: 201, json: async () => ({ id: 31 }) });
    global.fetch = fetchMock;
    render(<PublicacionForm />);

    await screen.findByRole('option', { name: 'Hogar' });
    expect(screen.getByLabelText(/^categoría$/i)).toHaveValue('9');
    expect(screen.getByLabelText(/^subcategoría$/i)).toHaveValue('91');

    fireEvent.change(screen.getByLabelText(/precio \(S\/\)/i), { target: { value: '12.34' } });
    fireEvent.change(screen.getByLabelText(/stock/i), { target: { value: '2' } });
    fireEvent.change(screen.getByLabelText(/descripción/i), { target: { value: 'Mesa' } });
    fireEvent.click(screen.getByRole('button', { name: 'Publicar' }));

    await waitFor(() => expect(fetchMock).toHaveBeenLastCalledWith('http://localhost:8080/publicaciones', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'include',
      body: JSON.stringify({ precio: 1234, stock: 2, categoriaId: 9, subcategoriaId: 91, descripcion: 'Mesa', imagenFilename: '' })
    }));
  });

  it('POST usa IDs reales, credentials include y convierte 15.09 a 1509 sin punto flotante', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => CATEGORIAS })
      .mockResolvedValueOnce({ ok: true, status: 201, json: async () => ({ id: 30 }) });
    global.fetch = fetchMock;
    const onSuccess = vi.fn();
    render(<PublicacionForm onSuccess={onSuccess} />);

    await screen.findByRole('option', { name: 'Servicios' });
    fireEvent.change(screen.getByLabelText(/^categoría$/i), { target: { value: '9' } });
    fireEvent.change(screen.getByLabelText(/^subcategoría$/i), { target: { value: '92' } });
    fireEvent.change(screen.getByLabelText(/precio \(S\/\)/i), { target: { value: '15.09' } });
    fireEvent.change(screen.getByLabelText(/stock/i), { target: { value: '5' } });
    fireEvent.change(screen.getByLabelText(/descripción/i), { target: { value: 'Lámpara' } });
    fireEvent.click(screen.getByRole('button', { name: 'Publicar' }));

    await waitFor(() => expect(fetchMock).toHaveBeenLastCalledWith('http://localhost:8080/publicaciones', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'include',
      body: JSON.stringify({ precio: 1509, stock: 5, categoriaId: 9, subcategoriaId: 92, descripcion: 'Lámpara', imagenFilename: '' })
    }));
    expect(onSuccess).toHaveBeenCalledOnce();
  });

  it('bloquea precio/stock inválidos y catálogo sin subcategorías sin fabricar IDs', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: true, status: 200, json: async () => [{ id: 4, nombre: 'Vacía', subcategorias: [] }]
    });
    render(<PublicacionForm />);
    await screen.findByRole('option', { name: 'Vacía' });
    fireEvent.change(screen.getByLabelText(/precio/i), { target: { value: '1.001' } });
    fireEvent.change(screen.getByLabelText(/stock/i), { target: { value: '0' } });
    fireEvent.click(screen.getByRole('button', { name: 'Publicar' }));
    expect(await screen.findByText(/precio válido en soles/i)).toBeInTheDocument();
    expect(screen.getByText(/stock debe ser al menos 1/i)).toBeInTheDocument();
    expect(screen.getByText(/categoría con subcategorías disponibles/i)).toBeInTheDocument();
    expect(global.fetch).toHaveBeenCalledTimes(1);
  });

  it('muestra mensaje backend y fallback honesto ante fallas del catálogo', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: false, status: 503, json: async () => ({ mensaje: 'Catálogo temporalmente fuera de servicio' }) });
    global.fetch = fetchMock;
    render(<PublicacionForm />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Catálogo temporalmente fuera de servicio');
    expect(screen.getByRole('button', { name: 'Publicar' })).toBeDisabled();
  });

  // =========================================================================
  // PHA15TSK01 - Tests Red phase para imagenFilename en creación
  // =========================================================================

  it('formulario de creación incluye input imagenFilename con placeholder y aria-label (PHA15TSK01)', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => CATEGORIAS })
      .mockResolvedValueOnce({ ok: true, status: 201, json: async () => ({ id: 99, imagenFilename: 'nueva-imagen.jpg' }) });
    global.fetch = fetchMock;
    render(<PublicacionForm />);

    await screen.findByRole('option', { name: 'Servicios' });
    // Verificar que existe el input imagenFilename
    expect(screen.getByLabelText(/imagen \(archivo\)/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/imagen \(archivo\)/i)).toHaveAttribute('placeholder', 'producto.jpg');
    expect(screen.getByLabelText(/imagen \(archivo\)/i)).toHaveAttribute('aria-label', 'Imagen (archivo)');

    // Rellenar formulario incluyendo imagenFilename
    fireEvent.change(screen.getByLabelText(/precio \(S\/\)/i), { target: { value: '10.00' } });
    fireEvent.change(screen.getByLabelText(/stock/i), { target: { value: '1' } });
    fireEvent.change(screen.getByLabelText(/^categoría$/i), { target: { value: '9' } });
    fireEvent.change(screen.getByLabelText(/^subcategoría$/i), { target: { value: '91' } });
    fireEvent.change(screen.getByLabelText(/descripción/i), { target: { value: 'Producto con imagen' } });
    fireEvent.change(screen.getByLabelText(/imagen \(archivo\)/i), { target: { value: 'nueva-imagen.jpg' } });
    fireEvent.click(screen.getByRole('button', { name: 'Publicar' }));

    await waitFor(() => expect(fetchMock).toHaveBeenLastCalledWith('http://localhost:8080/publicaciones', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'include',
      body: JSON.stringify({ precio: 1000, stock: 1, categoriaId: 9, subcategoriaId: 91, descripcion: 'Producto con imagen', imagenFilename: 'nueva-imagen.jpg' })
    }));
  });

  it('formulario de creación funciona sin imagenFilename (campo opcional)', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => CATEGORIAS })
      .mockResolvedValueOnce({ ok: true, status: 201, json: async () => ({ id: 100 }) });
    global.fetch = fetchMock;
    render(<PublicacionForm />);

    await screen.findByRole('option', { name: 'Servicios' });
    // No rellenar imagenFilename (debe ser opcional)
    fireEvent.change(screen.getByLabelText(/precio \(S\/\)/i), { target: { value: '10.00' } });
    fireEvent.change(screen.getByLabelText(/stock/i), { target: { value: '1' } });
    fireEvent.change(screen.getByLabelText(/^categoría$/i), { target: { value: '9' } });
    fireEvent.change(screen.getByLabelText(/^subcategoría$/i), { target: { value: '91' } });
    fireEvent.change(screen.getByLabelText(/descripción/i), { target: { value: 'Producto sin imagen' } });
    fireEvent.click(screen.getByRole('button', { name: 'Publicar' }));

    await waitFor(() => expect(fetchMock).toHaveBeenLastCalledWith('http://localhost:8080/publicaciones', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'include',
      body: JSON.stringify({ precio: 1000, stock: 1, categoriaId: 9, subcategoriaId: 91, descripcion: 'Producto sin imagen', imagenFilename: '' })
    }));
  });
});
