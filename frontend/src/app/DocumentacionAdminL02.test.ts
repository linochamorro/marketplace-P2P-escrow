import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/** Función interna de PanelDisputas que debe satisfacer el principio 9. */
interface FuncionDocumentada {
  /** Nombre exacto de la función en el fuente. */
  nombre: string;
  /** Parámetros que deben figurar en el bloque TSDoc. */
  parametros: string[];
}

/** Casos concretos rechazados por el Artifact Tester L01. */
const FUNCIONES: FuncionDocumentada[] = [
  { nombre: 'handleMotivoChange', parametros: ['disputaId', 'value'] },
  { nombre: 'resolverDisputa', parametros: ['disputaId', 'decision'] },
  { nombre: 'resolucionHabilitada', parametros: ['disputaId'] },
];

/**
 * Extrae el bloque TSDoc inmediatamente anterior a una función concreta.
 *
 * @param source contenido completo del archivo TypeScript
 * @param nombre nombre de la función asignada a una constante
 * @returns bloque documental adyacente o cadena vacía si no existe
 */
function bloqueAnterior(source: string, nombre: string): string {
  const nombreEscapado = nombre.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const declaracion = new RegExp(`\\bconst\\s+${nombreEscapado}\\b`).exec(source);
  if (!declaracion) return '';
  const posicion = declaracion.index;
  const prefijo = source.slice(0, posicion);
  const inicio = prefijo.lastIndexOf('/**');
  const fin = prefijo.lastIndexOf('*/');
  if (inicio < 0 || fin <= inicio || !/^\s*$/.test(prefijo.slice(fin + 2))) return '';
  return prefijo.slice(inicio, fin + 2);
}

describe('PHA06TSK13-L02 - documentación obligatoria de PanelDisputas', () => {
  const source = readFileSync(resolve(process.cwd(), 'src/app/PanelDisputas.tsx'), 'utf8');

  it('rechaza el TSDoc completo de otra función cuando hay código entre el bloque y la función objetivo', () => {
    const sourceSintetico = `
/**
 * Documenta exclusivamente otra función.
 * @returns valor de la otra función
 */
const otraFuncion = (): boolean => true;
// La función objetivo carece de bloque propio.
const funcionObjetivo = (): boolean => false;
`;

    expect(bloqueAnterior(sourceSintetico, 'funcionObjetivo')).toBe('');
  });

  it('acepta un TSDoc propio cuando solo existe whitespace antes de la declaración exacta', () => {
    const bloqueEsperado = `/**
 * Documenta la función objetivo.
 * @returns valor de la función objetivo
 */`;
    const sourceSintetico = `${bloqueEsperado}\n\n  const funcionObjetivo = (): boolean => true;`;

    expect(bloqueAnterior(sourceSintetico, 'funcionObjetivo')).toBe(bloqueEsperado);
  });

  it.each(FUNCIONES)('$nombre tiene propósito, parámetros y retorno completos', ({ nombre, parametros }) => {
    const bloque = bloqueAnterior(source, nombre);
    expect(bloque).toMatch(/\*\s+[^@\n][^\n]*/);
    for (const parametro of parametros) expect(bloque).toContain(`@param ${parametro}`);
    expect(bloque).toContain('@returns');
  });
});
