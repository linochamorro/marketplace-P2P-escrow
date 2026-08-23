import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import PublicarPage from './page';

const replace = vi.fn();

vi.mock('next/navigation', () => ({ useRouter: () => ({ replace }) }));

/** Verifica que la ruta navegable de creación reemplaza el historial tras crear. */
describe('/publicar (PHA06TSK10)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    replace.mockReset();
  });

  it("redirige con router.replace('/mis-publicaciones') tras POST exitoso", async () => {
    global.fetch = vi.fn()
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => [{ id: 7, nombre: 'Servicios', subcategorias: [{ id: 71, nombre: 'Clases' }] }] })
      .mockResolvedValueOnce({ ok: true, status: 201, json: async () => ({ id: 1 }) });
    render(<PublicarPage />);
    await screen.findByRole('option', { name: 'Servicios' });
    fireEvent.change(screen.getByLabelText(/precio/i), { target: { value: '10.00' } });
    fireEvent.change(screen.getByLabelText(/descripción/i), { target: { value: 'Clase' } });
    fireEvent.click(screen.getByRole('button', { name: 'Publicar' }));
    await waitFor(() => expect(replace).toHaveBeenCalledWith('/mis-publicaciones'));
  });
});
