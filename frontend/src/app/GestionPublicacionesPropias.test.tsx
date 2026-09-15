import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import GestionPublicacionesPropias from './GestionPublicacionesPropias';

/** Catálogo con IDs no correlativos para impedir dependencia de mocks históricos. */
const CATEGORIAS = [
  { id: 7, nombre: 'Servicios', subcategorias: [{ id: 71, nombre: 'Clases' }] },
  { id: 9, nombre: 'Hogar', subcategorias: [{ id: 91, nombre: 'Muebles' }, { id: 92, nombre: 'Decoración' }] }
];

/**
 * Crea una publicación propia mínima para una rama de estado.
 *
 * @param id identificador de prueba
 * @param estado estado API cuya acción se verifica
 * @param categoriaId categoría de prueba, real o inconsistente
 * @param subcategoriaId subcategoría de prueba, real o inconsistente
 * @param imagenFilename nombre de archivo de imagen opcional
 * @returns publicación compatible con `GET /publicaciones/mias`
 */
function publicacion(id: number, estado: string, categoriaId = 7, subcategoriaId = 71, imagenFilename = '') {
  return {
    id,
    precio: 1509,
    stock: estado === 'OCULTA' ? 0 : 2,
    estado,
    descripcion: `Oferta ${id}`,
    categoriaId,
    subcategoriaId,
    usuarioId: 3,
    usuarioEmail: 'vendedor@example.com',
    imagenFilename,
    codigoProducto: `2026ELE${String(id).padStart(5, '0')}`
  };
}

/**
 * Construye una respuesta fetch compatible con los transportes bajo prueba.
 *
 * @param data cuerpo JSON simulado
 * @param status código HTTP simulado
 * @returns respuesta mínima con `ok`, `status` y lector JSON
 */
function respuesta(data: unknown, status = 200) {
  return { ok: status >= 200 && status < 300, status, json: async () => data };
}

/**
 * Pruebas del panel inline de publicaciones propias de Stories 1, 3 y 10.
 * Cubren las cinco ramas de estado, contratos HTTP, confirmación y refetch canónico.
 */
describe('GestionPublicacionesPropias (PHA06TSK10 + PHA09TSK04)', () => {
  const originalEnv = process.env.NEXT_PUBLIC_API_URL;

  beforeEach(() => {
    vi.restoreAllMocks();
    process.env.NEXT_PUBLIC_API_URL = 'http://localhost:8080';
  });

  afterEach(() => {
    process.env.NEXT_PUBLIC_API_URL = originalEnv;
  });

  it('carga catálogo y publicaciones propias con cookie, muestra loading y lista vacía', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(respuesta(CATEGORIAS))
      .mockResolvedValueOnce(respuesta([]));
    global.fetch = fetchMock;
    render(<GestionPublicacionesPropias />);
    expect(screen.getByRole('status')).toHaveTextContent('Cargando mis publicaciones');
    expect(await screen.findByText('Aún no tienes publicaciones.')).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/categorias', { method: 'GET', credentials: 'include' });
    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/publicaciones/mias', { method: 'GET', credentials: 'include' });
  });

  it('respeta la matriz exacta de acciones de los cinco estados y resuelve nombres por IDs', async () => {
    global.fetch = vi.fn()
      .mockResolvedValueOnce(respuesta(CATEGORIAS))
      .mockResolvedValueOnce(respuesta([
        publicacion(1, 'PENDIENTE_REVISION'), publicacion(2, 'APROBADA'), publicacion(3, 'OCULTA'),
        publicacion(4, 'CAMBIOS_SOLICITADOS'), publicacion(5, 'RECHAZADA'), publicacion(6, 'APROBADA', 999, 998)
      ]));
    render(<GestionPublicacionesPropias />);
    const lista = await screen.findByRole('list', { name: 'Publicaciones propias' });
    const items = within(lista).getAllByRole('listitem');

    // PENDIENTE_REVISION: Editar + Eliminar (PHA15TSK01: ahora permite editar pendientes)
    expect(within(items[0]).getByRole('button', { name: /editar publicación 1/i })).toBeInTheDocument();
    expect(within(items[0]).getByRole('button', { name: /eliminar publicación 1/i })).toBeInTheDocument();
    expect(within(items[0]).queryByRole('button', { name: /corregir/i })).not.toBeInTheDocument();
    // APROBADA: Editar + Eliminar
    expect(within(items[1]).getByRole('button', { name: /editar publicación 2/i })).toBeInTheDocument();
    expect(within(items[1]).getByRole('button', { name: /eliminar publicación 2/i })).toBeInTheDocument();
    // OCULTA: Editar + Eliminar
    expect(within(items[2]).getByRole('button', { name: /editar publicación 3/i })).toBeInTheDocument();
    expect(within(items[2]).getByRole('button', { name: /eliminar publicación 3/i })).toBeInTheDocument();
    // CAMBIOS_SOLICITADOS: Corregir + Eliminar
    expect(within(items[3]).getByRole('button', { name: /corregir publicación 4/i })).toBeInTheDocument();
    expect(within(items[3]).getByRole('button', { name: /eliminar publicación 4/i })).toBeInTheDocument();
    // RECHAZADA: Corregir + Eliminar
    expect(within(items[4]).getByRole('button', { name: /corregir publicación 5/i })).toBeInTheDocument();
    expect(within(items[4]).getByRole('button', { name: /eliminar publicación 5/i })).toBeInTheDocument();
    // Sin clasificación: solo Eliminar
    expect(within(items[5]).getByRole('button', { name: /eliminar publicación 6/i })).toBeInTheDocument();
    expect(within(items[5]).queryByRole('button', { name: /editar/i })).not.toBeInTheDocument();
    expect(within(items[5]).queryByRole('button', { name: /corregir/i })).not.toBeInTheDocument();

    expect(within(items[0]).getByText(/Servicios · Clases/)).toBeInTheDocument();
    expect(within(items[5]).getByText(/Clasificación no disponible/)).toBeInTheDocument();
  });

  it('APROBADA edita precio/stock/descripción y luego hace refetch real', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(respuesta(CATEGORIAS))
      .mockResolvedValueOnce(respuesta([publicacion(2, 'APROBADA')]))
      .mockResolvedValueOnce(respuesta(publicacion(2, 'APROBADA')))
      .mockResolvedValueOnce(respuesta([publicacion(2, 'APROBADA')]));
    global.fetch = fetchMock;
    render(<GestionPublicacionesPropias />);
    fireEvent.click(await screen.findByRole('button', { name: /editar publicación 2/i }));
    fireEvent.change(screen.getByLabelText(/precio \(S\/\)/i), { target: { value: '20.07' } });
    fireEvent.change(screen.getByLabelText(/stock/i), { target: { value: '4' } });
    fireEvent.change(screen.getByLabelText(/descripción/i), { target: { value: 'Oferta editada' } });
    fireEvent.click(screen.getByRole('button', { name: /guardar cambios/i }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/publicaciones/2', {
      method: 'PATCH', headers: { 'Content-Type': 'application/json' }, credentials: 'include',
      body: JSON.stringify({ precio: 2007, stock: 4, descripcion: 'Oferta editada', imagenFilename: '' })
    }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(4));
    expect(fetchMock).toHaveBeenLastCalledWith('http://localhost:8080/publicaciones/mias', { method: 'GET', credentials: 'include' });
  });

  it('OCULTA permite reponer stock >=1 mediante el mismo formulario de edición', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(respuesta(CATEGORIAS))
      .mockResolvedValueOnce(respuesta([publicacion(3, 'OCULTA')]))
      .mockResolvedValueOnce(respuesta(publicacion(3, 'APROBADA')))
      .mockResolvedValueOnce(respuesta([publicacion(3, 'APROBADA')]));
    global.fetch = fetchMock;
    render(<GestionPublicacionesPropias />);
    fireEvent.click(await screen.findByRole('button', { name: /editar publicación 3/i }));
    fireEvent.change(screen.getByLabelText(/stock/i), { target: { value: '1' } });
    fireEvent.click(screen.getByRole('button', { name: /guardar cambios/i }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/publicaciones/3', expect.objectContaining({
      method: 'PATCH', credentials: 'include', body: JSON.stringify({ precio: 1509, stock: 1, descripcion: 'Oferta 3', imagenFilename: '' })
    })));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(4));
  });

  it('CAMBIOS_SOLICITADOS corrige con body exclusivo y refetch', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(respuesta(CATEGORIAS))
      .mockResolvedValueOnce(respuesta([publicacion(4, 'CAMBIOS_SOLICITADOS')]))
      .mockResolvedValueOnce(respuesta(publicacion(4, 'PENDIENTE_REVISION')))
      .mockResolvedValueOnce(respuesta([publicacion(4, 'PENDIENTE_REVISION', 9, 92)]));
    global.fetch = fetchMock;
    render(<GestionPublicacionesPropias />);
    fireEvent.click(await screen.findByRole('button', { name: /corregir publicación 4/i }));
    fireEvent.change(screen.getByLabelText(/^categoría de corrección$/i), { target: { value: '9' } });
    fireEvent.change(screen.getByLabelText(/^subcategoría de corrección$/i), { target: { value: '92' } });
    fireEvent.click(screen.getByRole('button', { name: /enviar corrección/i }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/publicaciones/4/corregir', {
      method: 'PATCH', headers: { 'Content-Type': 'application/json' }, credentials: 'include',
      body: JSON.stringify({ categoriaId: 9, subcategoriaId: 92 })
    }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(4));
  });

  it('RECHAZADA no elimina antes de confirmar, confirma inline, elimina y refetch', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(respuesta(CATEGORIAS))
      .mockResolvedValueOnce(respuesta([publicacion(5, 'RECHAZADA')]))
      .mockResolvedValueOnce(respuesta(null, 204))
      .mockResolvedValueOnce(respuesta([]));
    global.fetch = fetchMock;
    render(<GestionPublicacionesPropias />);
    fireEvent.click(await screen.findByRole('button', { name: /eliminar publicación 5/i }));
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(screen.getByText(/esta acción es definitiva/i)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /confirmar eliminación/i }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/publicaciones/5', {
      method: 'DELETE', credentials: 'include'
    }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(4));
    expect(await screen.findByText('Aún no tienes publicaciones.')).toBeInTheDocument();
  });

  it('la confirmación de eliminación usa texto negro solo en su botón Cancelar', async () => {
    global.fetch = vi.fn()
      .mockResolvedValueOnce(respuesta(CATEGORIAS))
      .mockResolvedValueOnce(respuesta([publicacion(5, 'RECHAZADA')]));
    render(<GestionPublicacionesPropias />);

    fireEvent.click(await screen.findByRole('button', { name: /eliminar publicación 5/i }));
    const confirmacion = screen.getByRole('group', { name: 'Confirmar eliminación de publicación 5' });

    expect(within(confirmacion).getByRole('button', { name: 'Cancelar' })).toHaveClass('text-black');
  });

  it('no expone acciones sin catálogo y muestra mensaje backend o fallback honesto', async () => {
    global.fetch = vi.fn()
      .mockResolvedValueOnce(respuesta([], 500))
      .mockResolvedValueOnce(respuesta([publicacion(2, 'APROBADA')]));
    render(<GestionPublicacionesPropias />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Error al cargar categorías (código 500)');
    expect(screen.queryByRole('button', { name: /editar/i })).not.toBeInTheDocument();
  });

  // ============================================================
  // PHA09TSK04 - Nuevas aserciones (deben fallar en Red phase)
  // ============================================================

  it('cada card muestra imagen si imagenFilename presente y placeholder si no', async () => {
    global.fetch = vi.fn()
      .mockResolvedValueOnce(respuesta(CATEGORIAS))
      .mockResolvedValueOnce(respuesta([
        publicacion(1, 'APROBADA', 7, 71, 'producto-1.jpg'),
        publicacion(2, 'APROBADA', 7, 71, ''),
        publicacion(3, 'RECHAZADA', 7, 71, 'producto-3.png')
      ]));
    render(<GestionPublicacionesPropias />);
    const lista = await screen.findByRole('list', { name: 'Publicaciones propias' });
    const items = within(lista).getAllByRole('listitem');

    // Card 1: tiene imagen - Next.js Image transforma la URL
    const img1 = within(items[0]).getByAltText(/producto-1\.jpg/i);
    expect(img1).toHaveAttribute('src', expect.stringContaining('%2Fimagenes%2Fpublicaciones%2Fproducto-1.jpg'));
    // Card 2: sin imagen - no debería haber img
    expect(within(items[1]).queryByAltText('')).not.toBeInTheDocument();
    // Card 3: tiene imagen en estado RECHAZADA
    const img3 = within(items[2]).getByAltText(/producto-3\.png/i);
    expect(img3).toHaveAttribute('src', expect.stringContaining('%2Fimagenes%2Fpublicaciones%2Fproducto-3.png'));
  });

  it('botón Eliminar visible en todos los estados (no solo RECHAZADA) y confirma antes de borrar', async () => {
    global.fetch = vi.fn()
      .mockResolvedValueOnce(respuesta(CATEGORIAS))
      .mockResolvedValueOnce(respuesta([
        publicacion(1, 'PENDIENTE_REVISION'),
        publicacion(2, 'APROBADA'),
        publicacion(3, 'OCULTA'),
        publicacion(4, 'CAMBIOS_SOLICITADOS'),
        publicacion(5, 'RECHAZADA')
      ]));
    render(<GestionPublicacionesPropias />);
    const lista = await screen.findByRole('list', { name: 'Publicaciones propias' });
    const items = within(lista).getAllByRole('listitem');

    // Eliminar debe aparecer en TODOS los estados
    for (let i = 0; i < 5; i++) {
      expect(within(items[i]).getByRole('button', { name: new RegExp(`eliminar publicación ${i + 1}`, 'i') })).toBeInTheDocument();
    }

    // Confirmación inline antes de borrar (ejemplo en APROBADA)
    const fetchMock2 = vi.fn()
      .mockResolvedValueOnce(respuesta(CATEGORIAS))
      .mockResolvedValueOnce(respuesta([publicacion(2, 'APROBADA')]))
      .mockResolvedValueOnce(respuesta(null, 204))
      .mockResolvedValueOnce(respuesta([]));
    global.fetch = fetchMock2;
    render(<GestionPublicacionesPropias />);
    fireEvent.click(await screen.findByRole('button', { name: /eliminar publicación 2/i }));
    expect(screen.getByText(/esta acción es definitiva/i)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /confirmar eliminación/i }));
    await waitFor(() => expect(fetchMock2).toHaveBeenCalledWith('http://localhost:8080/publicaciones/2', {
      method: 'DELETE', credentials: 'include'
    }));
    // Backend devolverá 409 si no es RECHAZADA - el test verifica que el frontend intenta el DELETE
  });

  it('formulario de edición incluye input imagenFilename y botón Cancelar que cierra panel', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(respuesta(CATEGORIAS))
      .mockResolvedValueOnce(respuesta([publicacion(2, 'APROBADA', 7, 71, 'imagen-actual.jpg')]))
      .mockResolvedValueOnce(respuesta(publicacion(2, 'APROBADA', 7, 71, 'imagen-nueva.jpg')))
      .mockResolvedValueOnce(respuesta([publicacion(2, 'APROBADA', 7, 71, 'imagen-nueva.jpg')]));
    global.fetch = fetchMock;
    render(<GestionPublicacionesPropias />);
    fireEvent.click(await screen.findByRole('button', { name: /editar publicación 2/i }));

    // Input imagenFilename presente
    expect(screen.getByLabelText(/imagen \(archivo\)/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/imagen \(archivo\)/i)).toHaveValue('imagen-actual.jpg');

    // Botón Cancelar presente y cierra panel sin enviar PATCH
    expect(screen.getByRole('button', { name: /cancelar/i })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /cancelar/i }));
    // Panel debe cerrarse
    expect(screen.queryByLabelText(/imagen \(archivo\)/i)).not.toBeInTheDocument();
    // No se debe haber llamado a PATCH (solo las 2 llamadas iniciales: categorias + mis-publicaciones)
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('editar con imagenFilename envía el campo en PATCH', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(respuesta(CATEGORIAS))
      .mockResolvedValueOnce(respuesta([publicacion(2, 'APROBADA', 7, 71, 'imagen-actual.jpg')]))
      .mockResolvedValueOnce(respuesta(publicacion(2, 'APROBADA', 7, 71, 'imagen-nueva.jpg')))
      .mockResolvedValueOnce(respuesta([publicacion(2, 'APROBADA', 7, 71, 'imagen-nueva.jpg')]));
    global.fetch = fetchMock;
    render(<GestionPublicacionesPropias />);
    fireEvent.click(await screen.findByRole('button', { name: /editar publicación 2/i }));
    fireEvent.change(screen.getByLabelText(/imagen \(archivo\)/i), { target: { value: 'imagen-nueva.jpg' } });
    fireEvent.click(screen.getByRole('button', { name: /guardar cambios/i }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/publicaciones/2', expect.objectContaining({
      method: 'PATCH', credentials: 'include',
      body: JSON.stringify({ precio: 1509, stock: 2, descripcion: 'Oferta 2', imagenFilename: 'imagen-nueva.jpg' })
    })));
  });

  // =========================================================================
  // PHA15TSK01 - Tests Red phase para edición de PENDIENTE_REVISION
  // =========================================================================

  it('muestra botón Editar para publicaciones en PENDIENTE_REVISION (PHA15TSK01)', async () => {
    global.fetch = vi.fn()
      .mockResolvedValueOnce(respuesta(CATEGORIAS))
      .mockResolvedValueOnce(respuesta([
        publicacion(1, 'PENDIENTE_REVISION', 7, 71, 'producto-pendiente.jpg')
      ]));
    render(<GestionPublicacionesPropias />);
    const lista = await screen.findByRole('list', { name: 'Publicaciones propias' });
    const items = within(lista).getAllByRole('listitem');

    // PENDIENTE_REVISION: debe mostrar botón Editar (nuevo comportamiento PHA15TSK01)
    expect(within(items[0]).getByRole('button', { name: /editar publicación 1/i })).toBeInTheDocument();
    expect(within(items[0]).getByRole('button', { name: /eliminar publicación 1/i })).toBeInTheDocument();
    expect(within(items[0]).queryByRole('button', { name: /corregir/i })).not.toBeInTheDocument();
  });
});
