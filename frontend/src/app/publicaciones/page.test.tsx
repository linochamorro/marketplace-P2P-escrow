import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import PublicacionesPage from './page';

/**
 * @file page.test.tsx
 * @description Prueba de componente para el listado filtrable de publicaciones (PHA05TSK04).
 *
 * Verifica el criterio previo literal de tasks.md: cambiar los filtros y el orden, y pulsar el
 * botón explícito, emite la consulta con parámetros autorizados y reemplaza los resultados por
 * los devueltos por el backend.
 */

/** URL base determinista del backend para las aserciones del transporte. */
const BASE = 'http://localhost:8080';

/**
 * Construye una respuesta HTTP exitosa simulada para los endpoints de lectura de Story 11.
 *
 * @param body cuerpo JSON que devolverá `response.json()`
 * @returns objeto compatible con la porción de `Response` consumida por la página
 */
function respuestaOk(body: unknown) {
  return { ok: true, status: 200, json: async () => body };
}

describe('PublicacionesPage (PHA05TSK04)', () => {
  const originalApiUrl = process.env.NEXT_PUBLIC_API_URL;

  beforeEach(() => {
    vi.restoreAllMocks();
    process.env.NEXT_PUBLIC_API_URL = BASE;
  });

  afterEach(() => {
    process.env.NEXT_PUBLIC_API_URL = originalApiUrl;
  });

  it('cambia la consulta y los resultados al aplicar categoría, rango de precio y orden explícitamente', async () => {
    const usuario = userEvent.setup();
    const categorias = [
      { id: 10, nombre: 'Tecnología', subcategorias: [{ id: 101, nombre: 'Laptops' }] }
    ];
    const resultadosIniciales = [
      {
        id: 1,
        precio: 99900,
        stock: 2,
        estado: 'APROBADA',
        descripcion: 'Laptop inicial',
        categoriaId: 10,
        subcategoriaId: 101,
        usuarioId: 7,
        usuarioEmail: 'vendedor-inicial@example.com',
        imagenFilename: null
      }
    ];
    const resultadosFiltrados = [
      {
        id: 2,
        precio: 125050,
        stock: 1,
        estado: 'APROBADA',
        descripcion: 'Laptop filtrada',
        categoriaId: 10,
        subcategoriaId: 101,
        usuarioId: 8,
        usuarioEmail: 'vendedor-filtrada@example.com',
        imagenFilename: null
      }
    ];
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(respuestaOk(categorias))
      .mockResolvedValueOnce(respuestaOk(resultadosIniciales))
      .mockResolvedValueOnce(respuestaOk(resultadosFiltrados));
    global.fetch = fetchMock;

    render(<PublicacionesPage />);

    expect(await screen.findByText('Laptop inicial')).toBeInTheDocument();
    await usuario.selectOptions(screen.getByLabelText('Categoría'), '10');
    await usuario.selectOptions(screen.getByLabelText('Subcategoría'), '101');
    await usuario.type(screen.getByLabelText('Precio mínimo (S/)'), '1200.50');
    await usuario.type(screen.getByLabelText('Precio máximo (S/)'), '1300');
    await usuario.selectOptions(screen.getByLabelText('Orden'), 'PRECIO_ASCENDENTE');
    await usuario.click(screen.getByRole('button', { name: 'Aplicar filtros' }));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenLastCalledWith(
        `${BASE}/publicaciones?categoriaId=10&subcategoriaId=101&precioMinimo=120050&precioMaximo=130000&orden=PRECIO_ASCENDENTE`,
        { method: 'GET', credentials: 'include' }
      );
    });
    expect(await screen.findByText('Laptop filtrada')).toBeInTheDocument();
    expect(screen.queryByText('Laptop inicial')).not.toBeInTheDocument();
  });

  it('enlaza una tarjeta real al detalle exacto y no expone identificadores internos decorativos', async () => {
    const publicacion = {
      id: 73,
      precio: 125050,
      stock: 4,
      estado: 'APROBADA',
      descripcion: 'Laptop profesional',
      categoriaId: 10,
      subcategoriaId: 101,
      usuarioId: 8,
      usuarioEmail: 'vendedor@example.com',
      imagenFilename: null
    };
    global.fetch = vi
      .fn()
      .mockResolvedValueOnce(respuestaOk([]))
      .mockResolvedValueOnce(respuestaOk([publicacion]));

    render(<PublicacionesPage />);

    const enlace = await screen.findByRole('link', { name: /ver detalle de laptop profesional/i });
    expect(enlace).toHaveAttribute('href', '/publicaciones/73');
    expect(screen.getByText('S/ 1250.50')).toBeInTheDocument();
    expect(screen.getByText('Stock: 4')).toBeInTheDocument();
    expect(screen.getByText('vendedor@example.com')).toBeInTheDocument();
    expect(screen.queryByText(/ID publicación/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/Categoría:/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/Subcategoría:/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/Vendedor:/i)).not.toBeInTheDocument();
  });
});
