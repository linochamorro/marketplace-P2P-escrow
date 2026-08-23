import PanelCentroNotificaciones from '../PanelCentroNotificaciones';

/**
 * @file notificaciones/page.tsx
 * @description Ruta `/notificaciones` del mapa de PHA06 que integra el centro de
 * notificaciones real de Story 7b.
 */

/**
 * Presenta el centro de notificaciones in-app dentro del shell existente. La página
 * permanece como Server Component y delega estado, fetch al cargar/enfocar y cleanup
 * al Client Component {@link PanelCentroNotificaciones}; no vuelve a consultar la
 * identidad ni duplica navegación.
 *
 * @returns panel funcional que consume `GET /notificaciones`
 */
export default function NotificacionesPage() {
  return <PanelCentroNotificaciones />;
}
