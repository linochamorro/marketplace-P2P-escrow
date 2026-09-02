import { render, screen, waitFor } from '@testing-library/react';
import { act } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import DetallePublicacion from './DetallePublicacion';

/**
 * @file DetallePublicacion.test.tsx
 * @description Pruebas de componente del detalle real e integración con CompraButton (PHA06TSK11).
 */

/** Props mínimas observadas por el doble de prueba de CompraButton. */
interface CompraButtonMockProps {
  /** Identificador numérico entregado por el detalle al flujo de compra. */
  publicacionId: number;
}

const compraButtonMock = vi.hoisted(() => {
  /**
   * Renderiza un doble observable de la frontera pública de CompraButton sin ejecutar Stripe.
   *
   * @param props propiedades mínimas entregadas por el detalle
   * @returns marcador accesible que permite comprobar el ID recibido
   */
  function CompraButtonMock({ publicacionId }: CompraButtonMockProps) {
    return <div data-testid="compra-button">Compra para {publicacionId}</div>;
  }

  return vi.fn(CompraButtonMock);
});

vi.mock('../CompraButton', () => ({ default: compraButtonMock }));

/**
 * Construye una respuesta HTTP simulada con cuerpo JSON controlado.
 *
 * @param body cuerpo que devolverá la respuesta
 * @param ok indica si el transporte debe tratarla como exitosa
 * @param status código HTTP observable por el transporte
 * @returns porción de Response consumida por el detalle
 */
function respuesta(body: unknown, ok = true, status = 200) {
  return { ok, status, json: async () => body };
}

describe('DetallePublicacion (PHA06TSK11)', () => {
  const originalApiUrl = process.env.NEXT_PUBLIC_API_URL;

  beforeEach(() => {
    vi.restoreAllMocks();
    compraButtonMock.mockClear();
    process.env.NEXT_PUBLIC_API_URL = 'http://localhost:8080';
  });

  afterEach(() => {
    process.env.NEXT_PUBLIC_API_URL = originalApiUrl;
  });

  it('muestra carga, consulta el detalle real con cookie y entrega el ID numérico a CompraButton', async () => {
    let resolver!: (value: ReturnType<typeof respuesta>) => void;
    const pendiente = new Promise<ReturnType<typeof respuesta>>((resolve) => {
      resolver = resolve;
    });
    const fetchMock = vi.fn().mockReturnValueOnce(pendiente);
    global.fetch = fetchMock;

    await act(async () => {
      render(<DetallePublicacion params={Promise.resolve({ id: '73' })} />);
    });

    expect(screen.getByRole('status')).toHaveTextContent('Cargando publicación...');
    resolver(respuesta({
      id: 73,
      precio: 125050,
      stock: 4,
      estado: 'APROBADA',
      descripcion: 'Laptop profesional',
      categoriaId: 10,
      subcategoriaId: 101,
      usuarioId: 8,
      imagenFilename: 'laptop73.jpg',
      codigoProducto: '2026ELE00006'
    }));

    expect(await screen.findByRole('heading', { name: 'Laptop profesional' })).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/publicaciones/73', {
      method: 'GET',
      credentials: 'include'
    });
    expect(screen.getByText('S/ 1250.50')).toBeInTheDocument();
    expect(screen.getByText('Stock disponible: 4')).toBeInTheDocument();
    expect(compraButtonMock).toHaveBeenCalledWith(
      expect.objectContaining({ publicacionId: 73 }),
      undefined
    );
    expect(screen.getByTestId('compra-button')).toHaveTextContent('Compra para 73');
    expect(screen.queryByText(/ID publicación/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/Categoría:/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/Subcategoría:/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/Vendedor:/i)).not.toBeInTheDocument();
  });

  it('muestra un error honesto cuando el endpoint de detalle falla y no monta CompraButton', async () => {
    const fetchMock = vi.fn().mockResolvedValue(respuesta(
      { mensaje: 'La publicación no está disponible' },
      false,
      404
    ));
    global.fetch = fetchMock;

    await act(async () => {
      render(<DetallePublicacion params={Promise.resolve({ id: '19' })} />);
    });

    expect(await screen.findByRole('alert')).toHaveTextContent('La publicación no está disponible');
    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/publicaciones/19', {
      method: 'GET',
      credentials: 'include'
    });
    expect(compraButtonMock).not.toHaveBeenCalled();
  });

  it('limpia A y muestra carga al navegar a B antes de renderizar los datos y CompraButton de B', async () => {
    let resolverA!: (value: ReturnType<typeof respuesta>) => void;
    let resolverB!: (value: ReturnType<typeof respuesta>) => void;
    const respuestaA = new Promise<ReturnType<typeof respuesta>>((resolve) => {
      resolverA = resolve;
    });
    const respuestaB = new Promise<ReturnType<typeof respuesta>>((resolve) => {
      resolverB = resolve;
    });
    const fetchMock = vi.fn()
      .mockReturnValueOnce(respuestaA)
      .mockReturnValueOnce(respuestaB);
    global.fetch = fetchMock;

    let rerender!: ReturnType<typeof render>['rerender'];
    await act(async () => {
      ({ rerender } = render(<DetallePublicacion params={Promise.resolve({ id: '41' })} />));
    });
    resolverA(respuesta({
      id: 41,
      precio: 4100,
      stock: 1,
      estado: 'APROBADA',
      descripcion: 'Publicación A',
      categoriaId: 1,
      subcategoriaId: 2,
      usuarioId: 3,
      imagenFilename: 'publicacionA.jpg',
      codigoProducto: '2026ELE00007'
    }));
    expect(await screen.findByRole('heading', { name: 'Publicación A' })).toBeInTheDocument();
    expect(screen.getByTestId('compra-button')).toHaveTextContent('Compra para 41');

    await act(async () => {
      rerender(<DetallePublicacion params={Promise.resolve({ id: '52' })} />);
    });

    expect(screen.getByRole('status')).toHaveTextContent('Cargando publicación...');
    expect(screen.queryByRole('heading', { name: 'Publicación A' })).not.toBeInTheDocument();
    expect(screen.queryByText('S/ 41.00')).not.toBeInTheDocument();
    expect(screen.queryByTestId('compra-button')).not.toBeInTheDocument();

    resolverB(respuesta({
      id: 52,
      precio: 5200,
      stock: 2,
      estado: 'APROBADA',
      descripcion: 'Publicación B',
      categoriaId: 1,
      subcategoriaId: 2,
      usuarioId: 4,
      imagenFilename: 'publicacionB.jpg',
      codigoProducto: '2026ELE00008'
    }));
    expect(await screen.findByRole('heading', { name: 'Publicación B' })).toBeInTheDocument();
    expect(screen.getByText('S/ 52.00')).toBeInTheDocument();
    expect(screen.getByTestId('compra-button')).toHaveTextContent('Compra para 52');
    expect(fetchMock).toHaveBeenNthCalledWith(2, 'http://localhost:8080/publicaciones/52', {
      method: 'GET',
      credentials: 'include'
    });
  });

  it.each(['0', '-4', '7.5', '12abc', ''])('rechaza el segmento inválido %j sin truncarlo ni consultar o comprar', async (id) => {
    const fetchMock = vi.fn();
    global.fetch = fetchMock;

    await act(async () => {
      render(<DetallePublicacion params={Promise.resolve({ id })} />);
    });

    expect(await screen.findByRole('alert')).toHaveTextContent('El identificador de la publicación no es válido');
    await waitFor(() => expect(fetchMock).not.toHaveBeenCalled());
    expect(compraButtonMock).not.toHaveBeenCalled();
  });
});
