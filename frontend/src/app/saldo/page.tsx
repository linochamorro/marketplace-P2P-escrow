import PanelSaldo from '../PanelSaldo';

/**
 * @file saldo/page.tsx
 * @description Ruta `/saldo` del mapa de PHA06 que integra el panel real de Story
 * 12. Los montos permanecen enteros en centavos hasta su presentación en soles.
 */

/**
 * Presenta el saldo contable y sus movimientos dentro del shell existente. La página
 * permanece como Server Component y delega estado, transporte y cleanup al Client
 * Component {@link PanelSaldo}; no agrega identidad, retiro ni navegación duplicada.
 *
 * @returns panel funcional que consume `GET /usuarios/me/saldo`
 */
export default function SaldoPage() {
  return <PanelSaldo />;
}
