/**
 * Tipos y utilidades de presentación del Panel de Saldo (PHA04TSK21).
 *
 * <p>Modela el contrato de {@code SaldoResponseDto} del endpoint
 * {@code GET /usuarios/me/saldo} (PHA04TSK17, cerrado) y centraliza las decisiones de
 * presentación puras (formato de monto en soles, formato de fecha) para que el
 * componente y su test compartan exactamente el mismo modelo, sin duplicar lógica
 * en el render. Constitucion, principio 3: el dinero viaja SIEMPRE como entero en
 * centavos en el modelo; la conversión a soles (/{@code 100}) es exclusivamente
 * visual, nunca de datos.</p>
 */

/**
 * Movimiento del detalle de saldo tal como llega del contrato
 * {@code GET /usuarios/me/saldo} (PHA04TSK17, ya cerrado).
 *
 * <p>Modela literalmente el JSON de {@code MovimientoSaldoResponseDto}: monto entero
 * en centavos (crédito positivo), {@code createdAt} ISO8601+offset (ZonedDateTime)
 * y {@code transaccionId} SIEMPRE no nulo — la columna {@code movimientos_saldo.
 * transaccion_id} es {@code NOT NULL} (migración V9) y cada movimiento proviene de
 * una transacción de origen. El detalle no declara orden (JavaDoc del repositorio);
 * el componente renderiza el array tal como llega.</p>
 */
export interface MovimientoSaldoUI {
  /** Identificador persistente del movimiento (clave de render) */
  id: number;
  /** Monto entero en centavos del crédito (constitución, principio 3) */
  monto: number;
  /** Timestamp ISO8601+offset (ZonedDateTime del backend) de creación del registro append-only */
  createdAt: string;
  /** ID de la transacción que origina el movimiento — siempre no nulo (V9) */
  transaccionId: number;
}

/**
 * Respuesta de saldo del usuario autenticado tal como llega del contrato
 * {@code GET /usuarios/me/saldo} (PHA04TSK17, ya cerrado).
 *
 * <p>Modela literalmente el JSON de {@code SaldoResponseDto}: {@code saldoDisponible}
 * es el valor cacheado en centavos en {@code usuarios.saldo_disponible} leído O(1)
 * por {@code ConsultaSaldoService} (PHA04TSK11) sin recalcular ni reconciliar desde
 * el ledger; {@code movimientos} es el detalle append-only (posiblemente vacío).</p>
 */
export interface SaldoUI {
  /** Saldo disponible cacheado en centavos (entero — constitución, principio 3) */
  saldoDisponible: number;
  /** Detalle de movimientos append-only que componen el saldo (posiblemente vacío) */
  movimientos: MovimientoSaldoUI[];
}

/**
 * Formatea un monto en centavos a la presentación monetaria en soles (PEN) del
 * proyecto: {@code S/ X.XX} con dos decimales fijos.
 *
 * <p>Replica exactamente el patrón de presentación de {@code PanelTransaccion} y
 * {@code PanelDisputas} ({@code S/ {(monto / 100).toFixed(2)}} en JetBrains Mono —
 * DESIGN.md). La división por 100 es SOLO visual (constitución, principio 3: el
 * dinero se modela entero en centavos).</p>
 *
 * @param centavos monto entero en centavos (p. ej. 12345)
 * @returns cadena {@code S/ 123.45} lista para el usuario final
 */
export function formatearMontoSoles(centavos: number): string {
  return `S/ ${(centavos / 100).toFixed(2)}`;
}

/**
 * Formatea el timestamp ISO de un movimiento a una fecha/hora legible en locale
 * {@code es-PE} (hora de Perú, UTC-5 — decisión del proyecto en spec.md:35).
 *
 * <p>Mismo patrón de {@code formatearFechaNotificacion} de PHA04TSK20
 * (Intl.DateTimeFormat con {@code dateStyle: 'medium'} + {@code timeStyle: 'short'}
 * sin variantes {@code hour12} frágiles entre runtimes y {@code timeZone:
 * 'America/Lima'} explícito). No se importa desde {@code notificaciones-utils.ts}
 * porque ese archivo es de la feature de notificaciones; la presentación de fechas
 * del saldo vive en esta capa (decisión declarada en el Artifact PHA04TSK21-L01).
 * Si la fecha es inválida devuelve la cadena ISO cruda (fallback defensivo: no
 * romper el detalle por un timestamp corrupto).</p>
 *
 * @param isoFecha timestamp ISO8601 del backend (p. ej. "2026-08-10T17:00:00-05:00")
 * @returns fecha/hora legible en es-PE, o el valor crudo si no es una fecha válida
 */
export function formatearFechaMovimiento(isoFecha: string): string {
  const fecha = new Date(isoFecha);
  if (Number.isNaN(fecha.getTime())) {
    return isoFecha;
  }
  return new Intl.DateTimeFormat('es-PE', {
    dateStyle: 'medium',
    timeStyle: 'short',
    timeZone: 'America/Lima'
  }).format(fecha);
}

/**
 * Error tipado de la capa de transporte del saldo: distingue una respuesta HTTP
 * no-ok del backend (con {@code mensaje} extraíble) de un fallo de red que no
 * devolvió respuesta HTTP.
 */
export class ErrorApiSaldo extends Error {
  /** Código HTTP de la respuesta (p. ej. 403 sin sesión) */
  readonly codigoHttp: number;

  /**
   * @param mensaje texto del error (el {@code mensaje} del backend o un texto genérico)
   * @param codigoHttp código HTTP de la respuesta no-ok
   */
  constructor(mensaje: string, codigoHttp: number) {
    super(mensaje);
    this.name = 'ErrorApiSaldo';
    this.codigoHttp = codigoHttp;
  }
}

/**
 * Revalida de forma defensiva la forma del cuerpo de la respuesta 200
 * ({@code SaldoResponseDto}: objeto con {@code saldoDisponible} numérico y
 * {@code movimientos} array) antes de entregarlo al componente.
 *
 * <p>Si la forma no coincide (p. ej. el backend devolviera un array o {@code null}),
 * {@code cargarSaldo} lanza un error en lugar de fabricar un saldo neutro: mostrar
 * un saldo inventado mentiría sobre dinero (constitución, principio 3), así que el
 * contrato roto se muestra como banner de error. Decisión declarada en el Artifact
 * PHA04TSK21-L01 (a diferencia del {@code []} leniente de `cargarNotificaciones`,
 * donde el fallback neutro no representa dinero).</p>
 *
 * @param data cuerpo JSON sin tipar de la respuesta
 * @returns {@code true} si tiene exactamente la forma del contrato de saldo
 */
function esFormaSaldoValida(data: unknown): data is SaldoUI {
  if (data === null || typeof data !== 'object' || Array.isArray(data)) {
    return false;
  }
  const candidato = data as Record<string, unknown>;
  return typeof candidato.saldoDisponible === 'number' && Array.isArray(candidato.movimientos);
}

/**
 * Carga el saldo disponible y su detalle de movimientos del usuario autenticado
 * desde el contrato real {@code GET /usuarios/me/saldo} (PHA04TSK17) con la cookie
 * httpOnly: {@code GET} puro, sin body, path params ni query params (el contrato no
 * los acepta — identidad solo desde {@code @AuthenticationPrincipal}).
 *
 * @param url endpoint completo (base + '/usuarios/me/saldo')
 * @returns el saldo cacheado en centavos y su detalle de movimientos append-only
 * @throws ErrorApiSaldo si la respuesta HTTP no es 200 (con el {@code mensaje} del
 *         backend o fallback con código HTTP), si la forma del cuerpo 200 no
 *         coincide con el contrato, o el error de red original si fetch rechaza
 */
export async function cargarSaldo(url: string): Promise<SaldoUI> {
  const response = await fetch(url, {
    method: 'GET',
    credentials: 'include'
  });

  if (!response.ok) {
    const errorData = await response.json().catch(() => null);
    const mensaje =
      errorData?.mensaje || `Error al cargar el saldo (código ${response.status})`;
    throw new ErrorApiSaldo(mensaje, response.status);
  }

  const data: unknown = await response.json();
  if (!esFormaSaldoValida(data)) {
    throw new ErrorApiSaldo(
      'La respuesta del servidor no tiene el formato esperado',
      response.status
    );
  }
  return data;
}