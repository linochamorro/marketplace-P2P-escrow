'use client';

import Link from 'next/link';
import { Suspense, use, useEffect, useState } from 'react';
import PanelTransaccion, { type RolPanelTransaccion, type TransaccionPanel } from '../PanelTransaccion';

/** Códigos admitidos por la máquina de estados canónica. */
const ESTADOS_TRANSACCION = new Set([
  'reservada', 'enviado', 'entregado', 'recibido', 'recibido_sin_respuesta',
  'disputa', 'cancelada', 'completada'
]);

/** DTO de lectura de transacción expuesto por PHA06TSK06. */
export interface TransaccionLectura extends TransaccionPanel {
  /** Descripción canónica de la publicación. */
  publicacionDescripcion: string;
  /** Fecha de reserva obligatoria del DTO. */
  fechaReservada: string;
  /** Fecha de envío nullable en el contrato JSON. */
  fechaEnviado?: string;
  /** Fecha de entrega nullable en el contrato JSON. */
  fechaEntregado?: string;
}

/** Flujo canónico desde el que se deriva el rol del panel. */
export type FlujoTransaccion = 'compras' | 'ventas';

/**
 * Firma mínima de las lecturas HTTP, inyectable en pruebas.
 *
 * @param url endpoint absoluto o relativo que se debe consultar
 * @returns Promise con un cuerpo todavía desconocido que la frontera debe validar
 */
export type ObtenerJson = (url: string) => Promise<unknown>;

/** Propiedades de la lista real de compras o ventas. */
export interface ListadoTransaccionesProps {
  /** Flujo que fija endpoint, título y enlaces. */
  flujo: FlujoTransaccion;
  /** Transporte opcional; por defecto ejecuta GET autenticado. */
  obtenerJson?: ObtenerJson;
}

/** Parámetros prometidos de las rutas dinámicas de transacción. */
export interface DetalleTransaccionParams {
  /** Segmento dinámico sin validar. */
  id: string;
}

/** Propiedades del detalle real de una transacción. */
export interface DetalleTransaccionProps {
  /** Parámetros entregados como Promise por Next.js 16. */
  params: Promise<DetalleTransaccionParams>;
  /** Flujo canónico que determina pertenencia y rol. */
  flujo: FlujoTransaccion;
  /** Transporte opcional; por defecto ejecuta GET autenticado. */
  obtenerJson?: ObtenerJson;
}

/** Propiedades internas con parámetros ya resueltos. */
interface DetalleContenidoProps {
  /** Segmento dinámico literal. */
  id: string;
  /** Flujo canónico del detalle. */
  flujo: FlujoTransaccion;
  /** Transporte seleccionado. */
  obtenerJson: ObtenerJson;
}

/** Propiedades internas para resolver la Promise de App Router. */
interface DetalleConParamsProps extends DetalleTransaccionProps {
  /** Transporte obligatorio ya resuelto. */
  obtenerJson: ObtenerJson;
}

/**
 * Extrae un mensaje remoto sin inventar información ausente.
 *
 * @param response respuesta HTTP fallida
 * @param fallback mensaje honesto cuando el cuerpo no contiene `mensaje`
 * @returns mensaje utilizable por la interfaz
 */
async function mensajeError(response: Response, fallback: string): Promise<string> {
  const cuerpo = await response.json().catch(() => null) as { mensaje?: unknown } | null;
  return typeof cuerpo?.mensaje === 'string' && cuerpo.mensaje.trim() ? cuerpo.mensaje : fallback;
}

/**
 * Ejecuta una lectura JSON autenticada sin cuerpo.
 *
 * @param url endpoint absoluto o relativo
 * @returns cuerpo JSON sin asumir todavía su forma
 * @throws Error con mensaje backend o fallback si HTTP/red falla
 */
export async function obtenerJsonAutenticado(url: string): Promise<unknown> {
  const response = await fetch(url, { method: 'GET', credentials: 'include' });
  if (!response.ok) {
    throw new Error(await mensajeError(response, `No se pudo cargar la información (código ${response.status}).`));
  }
  return response.json();
}

/**
 * Valida una fecha obligatoria o nullable del DTO sin transformarla.
 *
 * @param valor valor remoto
 * @param nullable indica si `null` es admisible
 * @returns `true` si el valor conserva el contrato textual esperado
 */
function esFechaDto(valor: unknown, nullable: boolean): boolean {
  return (nullable && valor === null) || typeof valor === 'string';
}

/**
 * Valida y normaliza exclusivamente el DTO real de PHA06TSK06.
 *
 * @param valor cuerpo remoto desconocido
 * @returns DTO seguro para renderizar
 * @throws Error si faltan campos o violan enteros/estados/fechas del contrato
 */
export function validarTransaccion(valor: unknown): TransaccionLectura {
  if (typeof valor !== 'object' || valor === null) throw new Error('La respuesta de transacción no tiene un formato válido.');
  const dto = valor as Record<string, unknown>;
  if (!Number.isSafeInteger(dto.id) || (dto.id as number) <= 0
      || typeof dto.estado !== 'string' || !ESTADOS_TRANSACCION.has(dto.estado)
      || !Number.isSafeInteger(dto.precioSnapshot) || (dto.precioSnapshot as number) <= 0
      || !esFechaDto(dto.fechaReservada, false)
      || !esFechaDto(dto.fechaEnviado, true)
      || !esFechaDto(dto.fechaEntregado, true)
      || typeof dto.publicacionDescripcion !== 'string') {
    throw new Error('La respuesta de transacción no tiene un formato válido.');
  }
  return {
    id: dto.id as number,
    estado: dto.estado,
    precioSnapshot: dto.precioSnapshot as number,
    fechaReservada: dto.fechaReservada as string,
    fechaEnviado: dto.fechaEnviado === null ? undefined : dto.fechaEnviado as string,
    fechaEntregado: dto.fechaEntregado === null ? undefined : dto.fechaEntregado as string,
    publicacionDescripcion: dto.publicacionDescripcion
  };
}

/**
 * Valida una lista remota conservando estrictamente el orden del backend.
 *
 * @param valor cuerpo remoto desconocido
 * @returns DTO validados en el mismo orden recibido
 * @throws Error si el cuerpo no es arreglo o alguna fila es inválida
 */
export function validarListaTransacciones(valor: unknown): TransaccionLectura[] {
  if (!Array.isArray(valor)) throw new Error('La lista de transacciones no tiene un formato válido.');
  return valor.map(validarTransaccion);
}

/**
 * Convierte centavos enteros a PEN textual sin división ni punto flotante.
 *
 * @param centavos monto entero seguro ya validado
 * @returns monto `S/` con dos posiciones decimales
 */
export function formatearCentavos(centavos: number): string {
  const digitos = String(centavos).padStart(3, '0');
  return `S/ ${digitos.slice(0, -2)}.${digitos.slice(-2)}`;
}

/**
 * Acepta únicamente una representación decimal positiva y segura completa.
 *
 * @param segmento valor capturado por `[id]`
 * @returns ID numérico o `null`, sin truncar sufijos ni decimales
 */
export function validarTransaccionId(segmento: string): number | null {
  if (!/^[1-9]\d*$/.test(segmento)) return null;
  const id = Number(segmento);
  return Number.isSafeInteger(id) ? id : null;
}

/**
 * Lista transacciones reales con loading, error y vacío explícitos.
 *
 * <p>El reset al estado de carga ocurre durante el render únicamente cuando cambia el
 * flujo (patrón documentado de ajuste ante cambio de props), nunca dentro del efecto:
 * así se cumple {@code react-hooks/set-state-in-effect}. El estado inicial ya es
 * {@code cargando}, por lo que la primera carga y los cambios de flujo posteriores
 * muestran {@code role="status"}/{@code aria-busy} y luego los datos o el error.</p>
 *
 * @param props flujo y transporte opcional
 * @returns cards en orden backend enlazadas al detalle de su mismo flujo
 */
export function ListadoTransacciones({ flujo, obtenerJson = obtenerJsonAutenticado }: ListadoTransaccionesProps) {
  const [transacciones, setTransacciones] = useState<TransaccionLectura[]>([]);
  const [cargando, setCargando] = useState(true);
  const [errorMensaje, setErrorMensaje] = useState<string | null>(null);
  const titulo = flujo === 'compras' ? 'Compras' : 'Ventas';
  const [flujoPrevio, setFlujoPrevio] = useState<FlujoTransaccion>(flujo);
  if (flujo !== flujoPrevio) {
    setFlujoPrevio(flujo);
    setCargando(true);
    setErrorMensaje(null);
    setTransacciones([]);
  }

  useEffect(() => {
    let activo = true;
    const url = `${process.env.NEXT_PUBLIC_API_URL || ''}/transacciones/${flujo}`;

    /**
     * Ejecuta el ciclo asíncrono de la lista: obtiene el JSON, valida todos los DTO y
     * publica el resultado únicamente mientras el efecto siga activo. Convierte cualquier
     * fallo de transporte o contrato en el estado de error visible y finaliza el indicador
     * de carga; el error queda contenido en el componente y no se propaga al caller.
     *
     * @returns Promise que se resuelve cuando la carga, validación y actualización permitida
     *   del estado han terminado
     */
    async function cargar(): Promise<void> {
      try {
        const resultado = validarListaTransacciones(await obtenerJson(url));
        if (activo) setTransacciones(resultado);
      } catch (error: unknown) {
        if (activo) setErrorMensaje(error instanceof Error ? error.message : `No se pudieron cargar tus ${flujo}.`);
      } finally {
        if (activo) setCargando(false);
      }
    }
    void cargar();
    return () => { activo = false; };
  }, [flujo, obtenerJson]);

  return (
    <main className="min-h-screen bg-[#F8FAFC] px-4 py-8 sm:px-6">
      <div className="mx-auto max-w-[1200px] space-y-6">
        <header><h1 className="text-3xl font-bold text-[#0F172A]">{titulo}</h1></header>
        {cargando ? <div role="status" aria-busy="true" className="rounded-lg border border-slate-200 bg-white p-6 text-slate-600">Cargando {flujo}...</div>
          : errorMensaje ? <div role="alert" className="rounded border border-[#ba1a1a] bg-[#ffdad6] p-4 text-[#93000a]">{errorMensaje}</div>
          : transacciones.length === 0 ? <div className="rounded-lg border border-slate-200 bg-white p-6 text-slate-600">Aún no tienes {flujo}.</div>
          : <ul className="grid grid-cols-1 gap-6 md:grid-cols-2">{transacciones.map((transaccion) => (
            <li key={transaccion.id} className="rounded-lg border border-slate-200 bg-white p-6">
              <article className="space-y-3">
                <h2 className="text-lg font-semibold text-[#0F172A]">{transaccion.publicacionDescripcion}</h2>
                <p className="font-mono font-semibold text-[#0F172A]">{formatearCentavos(transaccion.precioSnapshot)}</p>
                <p className="text-sm font-semibold uppercase text-slate-600">{transaccion.estado}</p>
                <div className="space-y-1 text-xs text-slate-500">
                  <p>Reservada: {transaccion.fechaReservada}</p>
                  {transaccion.fechaEnviado && <p>Enviado: {transaccion.fechaEnviado}</p>}
                  {transaccion.fechaEntregado && <p>Entregado: {transaccion.fechaEntregado}</p>}
                </div>
                <Link href={`/${flujo}/${transaccion.id}`} aria-label={`Ver ${flujo === 'compras' ? 'compra' : 'venta'} ${transaccion.publicacionDescripcion}`} className="inline-block rounded border border-[#0F172A] bg-[#0F172A] px-4 py-2 text-sm font-semibold text-white">Ver detalle</Link>
              </article>
            </li>
          ))}</ul>}
      </div>
    </main>
  );
}

/**
 * Resuelve ambas fuentes requeridas y monta acciones solo tras confirmar pertenencia.
 *
 * <p>La rama de identificador inválido se deriva durante el render con un early return
 * (mismo mensaje, rol y marcado accesible que verifica el test previo), por lo que el
 * efecto solo consulta IDs válidos. Todos sus setState ocurren tras el primer await o en
 * callbacks asíncronos, y el estado inicial ya es {@code cargando}; el remount por
 * {@code key} en {@link DetalleConParams} limpia datos previos al navegar de A a B. No
 * se viola la regla {@code react-hooks/set-state-in-effect}.</p>
 *
 * @param props segmento, flujo y transporte
 * @returns panel real o estado explícito de carga/error
 */
function DetalleContenido({ id, flujo, obtenerJson }: DetalleContenidoProps) {
  const transaccionId = validarTransaccionId(id);
  const [transaccion, setTransaccion] = useState<TransaccionLectura | null>(null);
  const [cargando, setCargando] = useState(transaccionId !== null);
  const [errorMensaje, setErrorMensaje] = useState<string | null>(null);

  useEffect(() => {
    if (transaccionId === null) return;
    let activo = true;
    const base = process.env.NEXT_PUBLIC_API_URL || '';

    /**
     * Ejecuta en paralelo el detalle y la lista canónica, valida ambos contratos y confirma
     * pertenencia numérica exacta antes de publicar una transacción operable. Cualquier
     * fallo HTTP, DTO inválido, ID divergente o ausencia en la lista se contiene como error
     * visible; nunca se usa una lectura exitosa como sustituto de la otra.
     *
     * @returns Promise que se resuelve tras completar ambas lecturas y dejar establecido
     *   exclusivamente el panel autorizado o su error honesto
     */
    async function cargar(): Promise<void> {
      try {
        const [detalleRemoto, listaRemota] = await Promise.all([
          obtenerJson(`${base}/transacciones/${transaccionId}`),
          obtenerJson(`${base}/transacciones/${flujo}`)
        ]);
        const detalle = validarTransaccion(detalleRemoto);
        const lista = validarListaTransacciones(listaRemota);
        if (!lista.some((item) => item.id === transaccionId)) {
          throw new Error(`La transacción no pertenece a tus ${flujo}.`);
        }
        if (detalle.id !== transaccionId) throw new Error('La respuesta de transacción no coincide con el identificador solicitado.');
        if (activo) setTransaccion(detalle);
      } catch (error: unknown) {
        if (activo) setErrorMensaje(error instanceof Error ? error.message : 'No se pudo cargar la transacción.');
      } finally {
        if (activo) setCargando(false);
      }
    }
    void cargar();
    return () => { activo = false; };
  }, [flujo, obtenerJson, transaccionId]);

  if (transaccionId === null) {
    return <div role="alert" className="rounded border border-[#ba1a1a] bg-[#ffdad6] p-4 text-[#93000a]">El identificador de la transacción no es válido.</div>;
  }
  if (cargando) return <div role="status" aria-busy="true" className="rounded-lg border border-slate-200 bg-white p-6 text-slate-600">Cargando transacción...</div>;
  if (errorMensaje || !transaccion) return <div role="alert" className="rounded border border-[#ba1a1a] bg-[#ffdad6] p-4 text-[#93000a]">{errorMensaje || 'No se pudo cargar la transacción.'}</div>;
  const rol: RolPanelTransaccion = flujo === 'compras' ? 'COMPRADOR' : 'VENDEDOR';
  return <PanelTransaccion transaccion={transaccion} rol={rol} />;
}

/**
 * Detalle cliente compatible con params prometidos de Next.js 16.
 *
 * @param props parámetros, flujo y transporte opcional
 * @returns composición con Suspense y detalle aislado por identidad
 */
export default function DetalleTransaccion({ params, flujo, obtenerJson = obtenerJsonAutenticado }: DetalleTransaccionProps) {
  return (
    <main className="min-h-screen bg-[#F8FAFC] px-4 py-8 sm:px-6">
      <div className="mx-auto max-w-[1200px]">
        <Suspense fallback={<div role="status" aria-busy="true" className="rounded-lg border border-slate-200 bg-white p-6 text-slate-600">Cargando transacción...</div>}>
          <DetalleConParams params={params} flujo={flujo} obtenerJson={obtenerJson} />
        </Suspense>
      </div>
    </main>
  );
}

/**
 * Resuelve params dentro de Suspense y fuerza remount ante cambio de flujo o ID.
 *
 * @param props parámetros prometidos, flujo y transporte
 * @returns detalle cuya identidad evita commits mezclados A/B
 */
function DetalleConParams({ params, flujo, obtenerJson }: DetalleConParamsProps) {
  const { id } = use(params);
  return <DetalleContenido key={`${flujo}-${id}`} id={id} flujo={flujo} obtenerJson={obtenerJson} />;
}
