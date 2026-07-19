/**
 * @file setup.ts
 * @description Configuración inicial para el entorno de pruebas unitarias y de componentes.
 * Este archivo se ejecuta antes de cada prueba de Vitest. Se encarga de importar y extender
 * los matchers de Jest DOM (@testing-library/jest-dom) para proporcionar aserciones personalizadas
 * en el entorno jsdom, tales como 'toBeInTheDocument', 'toHaveTextContent', entre otras.
 */

import '@testing-library/jest-dom';
