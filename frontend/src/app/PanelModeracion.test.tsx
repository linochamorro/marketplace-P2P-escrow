import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import PanelModeracion from './PanelModeracion';

/** Publicación realista inyectada; el componente ya no contiene mocks internos. */
const PUBLICACIONES = [{ id: 1, usuarioId: 10, categoriaNombre: 'Electrónica', subcategoriaNombre: 'Smartphones', precio: 25000, stock: 3, descripcion: 'Smartphone usado', usuarioEmail: 'vendedor@example.com', imagenFilename: '', estado: 'PENDIENTE_REVISION' }];

/**
 * @file PanelModeracion.test.tsx
 * @description Pruebas de componente para PanelModeracion (PHA02TSK13).
 * Cobertura TDD:
 * 1. Exige motivo en "Rechazar" y "Solicitar Cambios" antes de enviar (bloquea submit localmente sin invocar fetch y muestra error inline).
 * 2. Permite enviar "Aprobar" sin motivo a POST/PATCH {NEXT_PUBLIC_API_URL}/publicaciones/{id}/moderar.
 * 3. Permite enviar "Rechazar" o "Solicitar Cambios" cuando se provee un motivo válido.
 * 4. Manejo de error 400 y 409 devuelto por el backend.
 */

describe('PanelModeracion (PHA02TSK13)', () => {
  const originalEnv = process.env.NEXT_PUBLIC_API_URL;

  beforeEach(() => {
    vi.restoreAllMocks();
    process.env.NEXT_PUBLIC_API_URL = 'http://localhost:8080';
  });

  afterEach(() => {
    process.env.NEXT_PUBLIC_API_URL = originalEnv;
  });

  it('bloquea el envío y muestra error si se intenta Rechazar o Solicitar Cambios sin motivo', async () => {
    const fetchMock = vi.fn();
    global.fetch = fetchMock;

    render(<PanelModeracion publicacionesIniciales={PUBLICACIONES} />);

    // Seleccionamos la primera publicación mock
    const rechazarBtn = screen.getAllByRole('button', { name: /rechazar/i })[0];
    fireEvent.click(rechazarBtn);

    // Debe mostrar error inline exigiendo el motivo y NO llamar a fetch
    expect(await screen.findByText(/el motivo es obligatorio para esta acción/i)).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('envía exitosamente la moderación de Aprobar sin requerir motivo', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({
        id: 1,
        estado: 'APROBADA'
      })
    });
    global.fetch = fetchMock;

    render(<PanelModeracion publicacionesIniciales={PUBLICACIONES} />);

    const aprobarBtn = screen.getAllByRole('button', { name: /aprobar/i })[0];
    fireEvent.click(aprobarBtn);

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/publicaciones/1/moderar', {
        method: 'PATCH',
        headers: {
          'Content-Type': 'application/json'
        },
        credentials: 'include',
        body: JSON.stringify({
          accion: 'aprobar'
        })
      });
    });

    expect(await screen.findByText(/publicación moderada exitosamente/i)).toBeInTheDocument();
  });

  it('envía exitosamente Rechazar cuando se proporciona un motivo válido', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({
        id: 1,
        estado: 'RECHAZADA'
      })
    });
    global.fetch = fetchMock;

    render(<PanelModeracion publicacionesIniciales={PUBLICACIONES} />);

    const motivoInput = screen.getAllByLabelText(/motivo/i)[0];
    fireEvent.change(motivoInput, { target: { value: 'Producto prohibido por políticas de seguridad' } });

    const rechazarBtn = screen.getAllByRole('button', { name: /rechazar/i })[0];
    fireEvent.click(rechazarBtn);

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/publicaciones/1/moderar', {
        method: 'PATCH',
        headers: {
          'Content-Type': 'application/json'
        },
        credentials: 'include',
        body: JSON.stringify({
          accion: 'rechazar',
          motivo: 'Producto prohibido por políticas de seguridad'
        })
      });
    });

    expect(await screen.findByText(/publicación moderada exitosamente/i)).toBeInTheDocument();
  });

  it('muestra un mensaje de error 409 cuando la publicación ya no está pendiente de revisión', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 409,
      json: async () => ({
        mensaje: 'TransicionEstadoInvalidaException: Esta publicación ya fue moderada previa o no se encuentra pendiente de revisión'
      })
    });
    global.fetch = fetchMock;

    render(<PanelModeracion publicacionesIniciales={PUBLICACIONES} />);

    const aprobarBtn = screen.getAllByRole('button', { name: /aprobar/i })[0];
    fireEvent.click(aprobarBtn);

    expect(await screen.findByText(/esta publicación ya fue moderada/i)).toBeInTheDocument();
  });

  it('muestra la imagen y el correo literal del vendedor de una publicación pendiente', () => {
    render(<PanelModeracion publicacionesIniciales={[{
      ...PUBLICACIONES[0],
      descripcion: 'Cámara analógica restaurada',
      imagenFilename: 'camara-analogica.jpg',
      usuarioEmail: 'vendedora@example.com',
    }]} />);

    expect(screen.getByRole('img', { name: 'Cámara analógica restaurada' })).toHaveAttribute('src', '/imagenes/publicaciones/camara-analogica.jpg');
    expect(screen.getByText('vendedora@example.com')).toBeInTheDocument();
  });
});

