import { render, screen } from '@testing-library/react';
import { expect, test } from 'vitest';
import Home from './page';

/**
 * Pruebas unitarias para el componente de página de inicio (Home).
 * Verifica que los elementos principales de la interfaz se rendericen correctamente.
 */
test('renders Home page with title text', () => {
  render(<Home />);
  const heading = screen.getByRole('heading', { level: 1 });
  expect(heading).toBeInTheDocument();
  expect(heading).toHaveTextContent(/To get started, edit the page\.tsx file\./i);
});
