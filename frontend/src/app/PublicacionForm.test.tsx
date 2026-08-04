import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import PublicacionForm from './PublicacionForm';

/**
 * @file PublicacionForm.test.tsx
 * @description Pruebas de componente para PublicacionForm (PHA02TSK12).
 * Cobertura TDD:
 * 1. Validación de precio <= 0 antes de enviar (bloquea submit y muestra error inline).
 * 2. Validación de stock < 1 antes de enviar (bloquea submit y muestra error inline).
 * 3. Envío exitoso cuando los datos son válidos (llama POST {NEXT_PUBLIC_API_URL}/publicaciones con credentials include y precio en centavos).
 * 4. Manejo de error 400 devuelto por el backend (muestra mensaje explícito de la excepción).
 */

describe('PublicacionForm (PHA02TSK12)', () => {
  const originalEnv = process.env.NEXT_PUBLIC_API_URL;

  beforeEach(() => {
    vi.restoreAllMocks();
    process.env.NEXT_PUBLIC_API_URL = 'http://localhost:8080';
  });

  afterEach(() => {
    process.env.NEXT_PUBLIC_API_URL = originalEnv;
  });

  it('bloquea envío y muestra error si el precio es <= 0 o stock < 1', async () => {
    const fetchMock = vi.fn();
    global.fetch = fetchMock;

    render(<PublicacionForm />);

    // Llenamos datos inválidos
    const precioInput = screen.getByLabelText(/precio/i);
    const stockInput = screen.getByLabelText(/stock/i);
    const submitButton = screen.getByRole('button', { name: /publicar/i });

    fireEvent.change(precioInput, { target: { value: '0' } });
    fireEvent.change(stockInput, { target: { value: '0' } });

    fireEvent.click(submitButton);

    // Debe mostrar mensajes de error inline y NO llamar a fetch
    expect(await screen.findByText(/el precio debe ser mayor a 0/i)).toBeInTheDocument();
    expect(await screen.findByText(/el stock debe ser al menos 1/i)).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('envía la publicación exitosamente a POST {NEXT_PUBLIC_API_URL}/publicaciones si los datos son válidos', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 201,
      json: async () => ({
        id: 10,
        usuarioId: 1,
        categoriaId: 1,
        subcategoriaId: 101,
        precio: 1500,
        stock: 5,
        estado: 'PENDIENTE_REVISION',
        descripcion: 'Producto de prueba'
      })
    });
    global.fetch = fetchMock;

    const onSuccessMock = vi.fn();
    render(<PublicacionForm onSuccess={onSuccessMock} />);

    // Completar el formulario con datos válidos
    fireEvent.change(screen.getByLabelText(/precio/i), { target: { value: '15.00' } });
    fireEvent.change(screen.getByLabelText(/stock/i), { target: { value: '5' } });
    fireEvent.change(screen.getByLabelText(/descripción/i), { target: { value: 'Producto de prueba' } });

    fireEvent.click(screen.getByRole('button', { name: /publicar/i }));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/publicaciones', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        credentials: 'include',
        body: JSON.stringify({
          precio: 1500, // 15.00 -> 1500 centavos
          stock: 5,
          categoriaId: 1,
          subcategoriaId: 101,
          descripcion: 'Producto de prueba'
        })
      });
    });

    expect(onSuccessMock).toHaveBeenCalled();
  });

  it('muestra mensaje de error del backend cuando responde 400', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 400,
      json: async () => ({
        mensaje: 'SubcategoriaNoPerteneceACategoriaException: La subcategoría no pertenece a la categoría seleccionada'
      })
    });
    global.fetch = fetchMock;

    render(<PublicacionForm />);

    fireEvent.change(screen.getByLabelText(/precio/i), { target: { value: '20' } });
    fireEvent.change(screen.getByLabelText(/stock/i), { target: { value: '2' } });

    fireEvent.click(screen.getByRole('button', { name: /publicar/i }));

    expect(await screen.findByText(/SubcategoriaNoPerteneceACategoriaException/i)).toBeInTheDocument();
  });
});
