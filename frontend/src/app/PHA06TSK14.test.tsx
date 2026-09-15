import { render, screen, waitFor } from '@testing-library/react';
import { readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';
import ts from 'typescript';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import NotificacionesPage from './notificaciones/page';
import SaldoPage from './saldo/page';

// Mock next/navigation for components that use useRouter (PanelCentroNotificaciones)
const mockPush = vi.fn();
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: mockPush })
}));

/**
 * @file PHA06TSK14.test.tsx
 * @description Pruebas de integración de las rutas `/notificaciones` y `/saldo`,
 * más una auditoría estática del copy de todas las fuentes TypeScript de producción.
 * El análisis usa el AST de TypeScript para ignorar comentarios e interpolaciones
 * técnicas; revisa `.ts` y `.tsx` sin confundir `${...}` con USD.
 */

/** URL determinista del backend usada para comprobar los contratos reales. */
const BASE = 'http://localhost:8080';

/** Monedas y símbolos no autorizados en copy estático visible o potencialmente visible. */
const MONEDA_NO_PEN = /\b(?:USD|EUR|GBP|JPY|CNY|CAD|AUD)\b|\bUS\s*\$|\bd[oó]lares?\b|[$€£¥₹₽₩]/iu;

/**
 * Estado mutable global que cada prueba debe restaurar tras usar red determinista.
 */
interface EstadoGlobalPrueba {
  /** Valor previo de la base API; `undefined` significa que la variable no existía. */
  apiUrl: string | undefined;
  /** Implementación de fetch presente antes de iniciar la prueba. */
  fetch: typeof global.fetch;
}

/**
 * Recorre recursivamente un directorio y devuelve todas sus fuentes `.ts`/`.tsx` de
 * producción. Excluye únicamente tests/specs y declaraciones `.d.ts`, que no son código
 * ejecutable ni copy entregado al usuario.
 *
 * @param directorio carpeta que se inspecciona
 * @returns rutas absolutas de fuentes TypeScript ejecutables que no son tests ni specs
 */
function listarFuentesProduccion(directorio: string): string[] {
  return readdirSync(directorio, { withFileTypes: true }).flatMap((entrada) => {
    const ruta = path.join(directorio, entrada.name);
    if (entrada.isDirectory()) return listarFuentesProduccion(ruta);
    if (!/\.tsx?$/u.test(entrada.name)
      || /\.(?:test|spec)\.tsx?$/u.test(entrada.name)
      || entrada.name.endsWith('.d.ts')) return [];
    return [ruta];
  });
}

/**
 * Extrae texto JSX y segmentos literales del AST. TypeScript descarta comentarios y
 * entrega las partes estáticas de templates por separado, por lo que URLs e
 * interpolaciones `${...}` no se confunden con símbolos monetarios renderizados.
 *
 * @param ruta ruta absoluta de la fuente TypeScript
 * @returns literales estáticos auditables junto con su archivo de origen
 */
function extraerLiteralesEstaticos(ruta: string): Array<{ ruta: string; texto: string }> {
  const fuente = ts.createSourceFile(
    ruta,
    readFileSync(ruta, 'utf8'),
    ts.ScriptTarget.Latest,
    true,
    ruta.endsWith('.tsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS
  );
  const textos: Array<{ ruta: string; texto: string }> = [];

  /**
   * Visita cada nodo y conserva únicamente contenido literal, nunca comentarios ni
   * expresiones interpoladas.
   *
   * @param nodo nodo actual del AST TypeScript
   * @returns nada; acumula los literales encontrados en `textos`
   */
  function visitar(nodo: ts.Node): void {
    if (ts.isJsxText(nodo) || ts.isStringLiteral(nodo) || ts.isNoSubstitutionTemplateLiteral(nodo)) {
      textos.push({ ruta, texto: nodo.text });
    } else if (ts.isTemplateHead(nodo) || ts.isTemplateMiddle(nodo) || ts.isTemplateTail(nodo)) {
      textos.push({ ruta, texto: nodo.text });
    }
    ts.forEachChild(nodo, visitar);
  }

  visitar(fuente);
  return textos;
}

/**
 * Captura los globales que las pruebas de rutas reemplazan para aislar la red.
 *
 * @returns snapshot de la base API y de fetch previo a cada prueba
 */
function capturarEstadoGlobal(): EstadoGlobalPrueba {
  return {
    apiUrl: process.env.NEXT_PUBLIC_API_URL,
    fetch: global.fetch
  };
}

/**
 * Restaura exactamente los globales capturados, incluida la ausencia original de la
 * variable de entorno. No depende de mocks de Vitest ni del orden de ejecución.
 *
 * @param estado snapshot que debe volver a quedar activo
 * @returns nada; restaura `NEXT_PUBLIC_API_URL` y `global.fetch` por efecto lateral
 */
function restaurarEstadoGlobal(estado: EstadoGlobalPrueba): void {
  if (estado.apiUrl === undefined) {
    delete process.env.NEXT_PUBLIC_API_URL;
  } else {
    process.env.NEXT_PUBLIC_API_URL = estado.apiUrl;
  }
  global.fetch = estado.fetch;
}

let estadoGlobal: EstadoGlobalPrueba;

beforeEach(() => {
  estadoGlobal = capturarEstadoGlobal();
});

afterEach(() => {
  restaurarEstadoGlobal(estadoGlobal);
  vi.restoreAllMocks();
});

describe('PHA06TSK14 — rutas reales y moneda PEN/Soles', () => {
  it('/notificaciones monta PanelCentroNotificaciones y consume su contrato real', async () => {
    process.env.NEXT_PUBLIC_API_URL = BASE;
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => [{
        id: 91,
        mensaje: 'VENTAS POR ENTREGAR',
        tipo: 'VENTA_POR_ENTREGAR_DIARIA',
        leida: false,
        createdAt: '2026-08-15T10:00:00-05:00',
        transaccionId: 73
      }]
    });
    global.fetch = fetchMock;

    render(<NotificacionesPage />);

    expect(await screen.findByText('VENTAS POR ENTREGAR')).toBeInTheDocument();
    expect(screen.getByText('No leída')).toBeInTheDocument();
    expect(screen.getByText('Transacción #73')).toBeInTheDocument();
    expect(screen.queryByText(/sección en construcción/i)).not.toBeInTheDocument();
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(`${BASE}/notificaciones`, {
      method: 'GET',
      credentials: 'include'
    }));
  });

  it('/saldo monta PanelSaldo con centavos enteros, detalle real y presentación S/', async () => {
    process.env.NEXT_PUBLIC_API_URL = BASE;
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({
        saldoDisponible: 12345,
        movimientos: [{
          id: 17,
          monto: 2345,
          createdAt: '2026-08-15T11:00:00-05:00',
          transaccionId: 81
        }]
      })
    });
    global.fetch = fetchMock;

    render(<SaldoPage />);

    expect(await screen.findByText('S/ 123.45')).toBeInTheDocument();
    expect(screen.getByText('S/ 23.45')).toBeInTheDocument();
    expect(screen.getByText('Transacción #81')).toBeInTheDocument();
    expect(screen.queryByText(/sección en construcción/i)).not.toBeInTheDocument();
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(`${BASE}/usuarios/me/saldo`, {
      method: 'GET',
      credentials: 'include'
    }));
  });

  it('caracterización: toda fuente .ts/.tsx de producción excluye copy USD y símbolos ambiguos', () => {
    const raizApp = path.resolve(process.cwd(), 'src/app');
    const fuentes = listarFuentesProduccion(raizApp).sort();
    const rutasRelativas = fuentes.map((ruta) => path.relative(raizApp, ruta).replaceAll('\\', '/'));
    const hallazgos = fuentes
      .flatMap(extraerLiteralesEstaticos)
      .filter(({ texto }) => MONEDA_NO_PEN.test(texto))
      .map(({ ruta, texto }) => `${path.relative(raizApp, ruta)}: ${JSON.stringify(texto)}`);

    expect(rutasRelativas).toContain('saldo-utils.ts');
    expect(rutasRelativas).toContain('notificaciones-utils.ts');
    expect(rutasRelativas).toContain('publicaciones/publicaciones-utils.ts');
    expect(hallazgos).toEqual([]);
  });

  it('cleanup global restaura fetch y API definida o ausente sin depender del orden', () => {
    const fetchOriginal = global.fetch;
    const fetchTemporal = vi.fn() as unknown as typeof global.fetch;
    process.env.NEXT_PUBLIC_API_URL = 'http://api-previa.test';
    global.fetch = fetchOriginal;
    const estadoDefinido = capturarEstadoGlobal();

    process.env.NEXT_PUBLIC_API_URL = BASE;
    global.fetch = fetchTemporal;
    restaurarEstadoGlobal(estadoDefinido);

    expect(process.env.NEXT_PUBLIC_API_URL).toBe('http://api-previa.test');
    expect(global.fetch).toBe(fetchOriginal);

    process.env.NEXT_PUBLIC_API_URL = BASE;
    global.fetch = fetchTemporal;
    restaurarEstadoGlobal({ apiUrl: undefined, fetch: fetchOriginal });

    expect(process.env.NEXT_PUBLIC_API_URL).toBeUndefined();
    expect(global.fetch).toBe(fetchOriginal);
  });
});
