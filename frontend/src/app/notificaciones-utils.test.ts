import { describe, expect, it } from 'vitest';
import { rutaDestino } from './notificaciones-utils';

/**
 * @file notificaciones-utils.test.ts
 * @description Tests unitarios de `rutaDestino` (PHA16TSK07, hallazgo A4).
 *
 * Cubre el ruteo de `COMPRA_CONFIRMADA` con el mensaje literal real emitido al
 * vendedor por `ProcesadorEventosWebhookService` (PHA15TSK04):
 * "Nueva compra confirmada en tu publicación #N: transacción #M" → `/ventas/{id}`.
 * Incluye regresión de las dos ramas vigentes ("tu compra" → `/compras`,
 * "tu venta" → `/ventas`), del fallback `null` y del matching insensible a
 * mayúsculas.
 */

/**
 * Mensaje literal real del vendedor con IDs concretos (N=12, M=77).
 *
 * @returns el copy exacto emitido por el backend para el vendedor tras una compra nueva
 */
function mensajeVendedorLiteral(): string {
  return 'Nueva compra confirmada en tu publicación #12: transacción #77';
}

describe('rutaDestino (PHA16TSK07 — hallazgo A4)', () => {
  /**
   * Caso principal de la tarea: el aviso real al vendedor debe enrutar a ventas.
   *
   * @returns promesa resuelta cuando la aserción de ruteo pasa
   */
  it('COMPRA_CONFIRMADA con "tu publicación" (mensaje literal real) resuelve /ventas/{id}', () => {
    expect(rutaDestino('COMPRA_CONFIRMADA', 77, mensajeVendedorLiteral())).toBe('/ventas/77');
  });

  /**
   * El matching es insensible a mayúsculas como el existente.
   *
   * @returns promesa resuelta cuando la variante en mayúsculas enruta igual
   */
  it('matchea "tu publicación" de forma insensible a mayúsculas', () => {
    expect(
      rutaDestino('COMPRA_CONFIRMADA', 77, 'NUEVA COMPRA CONFIRMADA EN TU PUBLICACIÓN #12: TRANSACCIÓN #77')
    ).toBe('/ventas/77');
  });

  /**
   * Regresión: las dos ramas vigentes siguen intactas.
   *
   * @returns promesa resuelta cuando comprador y vendedor históricos enrutan igual
   */
  it('conserva las ramas vigentes "tu compra" → /compras y "tu venta" → /ventas', () => {
    expect(rutaDestino('COMPRA_CONFIRMADA', 41, 'Confirmaste la recepción de tu compra #41')).toBe(
      '/compras/41'
    );
    expect(
      rutaDestino('COMPRA_CONFIRMADA', 41, 'El comprador confirmó la recepción de tu venta #41; el saldo fue acreditado')
    ).toBe('/ventas/41');
  });

  /**
   * Regresión: el fallback `null` se conserva para mensajes sin expresión conocida
   * y para `transaccionId` nulo.
   *
   * @returns promesa resuelta cuando ambos fallbacks devuelven null
   */
  it('conserva el fallback null sin expresión conocida o sin transaccionId', () => {
    expect(rutaDestino('COMPRA_CONFIRMADA', 77, 'Aviso sin expresión de lado conocida')).toBeNull();
    expect(rutaDestino('COMPRA_CONFIRMADA', null, mensajeVendedorLiteral())).toBeNull();
  });
});
