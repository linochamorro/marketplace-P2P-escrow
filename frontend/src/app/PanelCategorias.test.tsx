import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import PanelCategorias from './PanelCategorias';

const categoriasIniciales = [
  {
    id: 1,
    nombre: 'Casa',
    subcategorias: [
      { id: 101, nombre: 'Muebles' },
    ],
  },
];

/**
 * Suite de PHA14TSK01: garantiza que el label "Nueva subcategoría de"
 * del panel de catálogo use fuente negra (`text-black`) según la story de UI.
 */
describe('PanelCategorias - label color', () => {
  beforeEach(() => {
    render(<PanelCategorias categoriasIniciales={categoriasIniciales} />);
  });

  /**
   * Localiza el `<label>` dueño del texto "Nueva subcategoría de Casa" con
   * `closest('label')`: `getByText` devuelve el elemento que contiene el
   * texto y `closest('label')` (auto-inclusivo) llega al label con la clase
   * aunque haya wrappers intermedios; `getByLabelText` no aplica porque el
   * `<input>` está anidado en el label y esa query devolvería el input, no
   * el label con `text-black`.
   */
  it('debe tener la clase text-black en el label "Nueva subcategoría de"', () => {
    const label = screen.getByText(/Nueva subcategoría de Casa/).closest('label');
    expect(label).not.toBeNull();
    expect((label as HTMLLabelElement).className).toContain('text-black');
  });
});