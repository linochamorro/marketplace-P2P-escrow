'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import PanelCategorias, { type CategoriaItem } from '../PanelCategorias';
import PanelDisputas, { type DisputaPanel } from '../PanelDisputas';
import PanelModeracion, { type PublicacionPendiente } from '../PanelModeracion';
import { useUsuarioSesion } from '../Shell';
import { formatearPrecioSoles, type CategoriaListado, type PublicacionListado } from '../publicaciones/publicaciones-utils';

/** Estados compartidos por las lecturas administrativas remotas. */
type EstadoCarga = 'cargando' | 'listo' | 'error';

/** Seis métricas del contrato `GET /admin/tablero`. */
export interface TableroAdmin {
  /** Publicaciones pendientes de revisión. */
  publicacionesPendientes: number;
  /** Transacciones actualmente en disputa. */
  disputasAbiertas: number;
  /** Cuentas con bloqueo permanente. */
  cuentasBloqueadas: number;
  /** Suma entera en centavos retenida en escrow. */
  volumenEscrowCentavos: number;
  /** Suma entera en centavos de movimientos positivos del ledger. */
  fondosLiberadosCentavos: number;
  /** Transacciones en los tres estados finales definidos. */
  transaccionesFinalizadas: number;
}

/** Usuario bloqueado devuelto por `GET /admin/usuarios/bloqueados`. */
export interface UsuarioBloqueado {
  /** ID requerido por la ruta de desbloqueo. */
  usuarioId: number;
  /** Email real visible para identificar la cuenta. */
  email: string;
}

/** Resultado interno de una lectura administrativa. */
interface ResultadoCarga<T> {
  /** Estado del ciclo remoto. */
  estado: EstadoCarga;
  /** Datos validados, ausentes mientras carga o ante error. */
  datos: T | null;
  /** Mensaje real del backend o fallback de red. */
  error: string | null;
}

/**
 * Extrae el mensaje común `{mensaje}` sin asumir que toda respuesta trae JSON.
 *
 * @param response respuesta HTTP no exitosa
 * @param fallback texto contextual usado si no existe mensaje remoto
 * @returns mensaje seguro para presentación
 */
async function extraerError(response: Response, fallback: string): Promise<string> {
  const body: unknown = await response.json().catch(() => null);
  if (body && typeof body === 'object' && 'mensaje' in body && typeof (body as { mensaje?: unknown }).mensaje === 'string') {
    return (body as { mensaje: string }).mensaje;
  }
  return fallback;
}

/**
 * Ejecuta un GET autenticado y devuelve el JSON sin fabricar valores por defecto.
 *
 * @param ruta ruta absoluta respecto de `NEXT_PUBLIC_API_URL`
 * @param signal señal que permite cancelar la lectura al desmontar la vista
 * @returns cuerpo JSON remoto
 * @throws Error con mensaje HTTP o de red
 */
async function obtenerAdmin(ruta: string, signal: AbortSignal): Promise<unknown> {
  const url = `${process.env.NEXT_PUBLIC_API_URL ?? ''}${ruta}`;
  let response: Response;
  try {
    response = await fetch(url, { method: 'GET', credentials: 'include', signal });
  } catch {
    throw new Error('Error de red al conectar con el servidor');
  }
  if (!response.ok) throw new Error(await extraerError(response, `Error al cargar datos (código ${response.status})`));
  return response.json();
}

/**
 * Hook común de carga que solo consulta cuando la identidad compartida es ADMIN.
 * Ignora resultados tardíos después de unmount o cambio de ruta.
 *
 * <p>El reset a {@code 'cargando'} se realiza durante el render únicamente en la
 * transición deshabilitado → habilitado (patrón documentado de ajuste de estado ante
 * cambio de props), nunca dentro del efecto: así se cumple la regla
 * {@code react-hooks/set-state-in-effect}. El estado inicial ya es {@code 'cargando'},
 * por lo que la primera carga y cada habilitación posterior muestran el indicador
 * accesible antes de los datos o del error.</p>
 *
 * @param cargar operación remota cancelable que produce datos validados
 * @param habilitado indica si el rol ya fue comprobado como ADMIN
 * @returns estado, datos y mensaje de error de la lectura
 */
function useCargaAdmin<T>(cargar: (signal: AbortSignal) => Promise<T>, habilitado: boolean): ResultadoCarga<T> {
  const [resultado, setResultado] = useState<ResultadoCarga<T>>({ estado: 'cargando', datos: null, error: null });
  const [habilitadoPrevio, setHabilitadoPrevio] = useState<boolean>(habilitado);
  if (habilitado !== habilitadoPrevio) {
    setHabilitadoPrevio(habilitado);
    if (habilitado) setResultado({ estado: 'cargando', datos: null, error: null });
  }
  useEffect(() => {
    if (!habilitado) return;
    let activo = true;
    const controller = new AbortController();
    cargar(controller.signal)
      .then((datos) => { if (activo) setResultado({ estado: 'listo', datos, error: null }); })
      .catch((error: unknown) => {
        if (activo) setResultado({ estado: 'error', datos: null, error: error instanceof Error ? error.message : 'Error inesperado' });
      });
    return () => { activo = false; controller.abort(); };
  }, [cargar, habilitado]);
  return resultado;
}

/**
 * Valida que un valor sea un entero seguro, incluso para montos monetarios.
 *
 * @param value valor remoto desconocido
 * @returns `true` si es entero seguro
 */
function esEntero(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value);
}

/**
 * Valida estrictamente las seis métricas del tablero.
 *
 * @param value cuerpo remoto desconocido
 * @returns DTO validado sin transformación monetaria
 * @throws Error si falta una métrica o no es entera
 */
function validarTablero(value: unknown): TableroAdmin {
  if (!value || typeof value !== 'object') throw new Error('El tablero no tiene un formato válido');
  const dto = value as Record<string, unknown>;
  const keys: Array<keyof TableroAdmin> = ['publicacionesPendientes', 'disputasAbiertas', 'cuentasBloqueadas', 'volumenEscrowCentavos', 'fondosLiberadosCentavos', 'transaccionesFinalizadas'];
  if (!keys.every((key) => esEntero(dto[key]))) throw new Error('El tablero no tiene un formato válido');
  return value as TableroAdmin;
}

/**
 * Valida una lista remota con un predicado por fila.
 *
 * @param value cuerpo remoto desconocido
 * @param predicate validador de cada elemento
 * @param message mensaje lanzado ante forma inválida
 * @returns arreglo validado en orden canónico
 * @throws Error si el cuerpo o una fila no cumplen el contrato
 */
function validarLista<T>(value: unknown, predicate: (item: unknown) => item is T, message: string): T[] {
  if (!Array.isArray(value) || !value.every(predicate)) throw new Error(message);
  return value;
}

/**
 * Comprueba la forma mínima del árbol de categorías.
 *
 * @param item fila remota desconocida
 * @returns `true` si IDs, nombres y subcategorías son válidos
 */
function esCategoria(item: unknown): item is CategoriaItem {
  if (!item || typeof item !== 'object') return false;
  const categoria = item as Record<string, unknown>;
  return esEntero(categoria.id) && typeof categoria.nombre === 'string' && Array.isArray(categoria.subcategorias)
    && categoria.subcategorias.every((sub) => !!sub && typeof sub === 'object' && esEntero((sub as Record<string, unknown>).id) && typeof (sub as Record<string, unknown>).nombre === 'string');
}

/**
 * Comprueba la forma completa usada de una publicación pendiente.
 *
 * @param item fila remota desconocida
   * @returns `true` si conserva IDs, precio entero, email e imagen del DTO real
 */
function esPublicacion(item: unknown): item is PublicacionListado {
  if (!item || typeof item !== 'object') return false;
  const dto = item as Record<string, unknown>;
  return esEntero(dto.id) && esEntero(dto.usuarioId) && esEntero(dto.categoriaId) && esEntero(dto.subcategoriaId)
    && esEntero(dto.precio) && esEntero(dto.stock) && typeof dto.estado === 'string' && typeof dto.descripcion === 'string'
    && typeof dto.usuarioEmail === 'string' && (typeof dto.imagenFilename === 'string' || dto.imagenFilename === null);
}

/**
 * Comprueba la forma del DTO de transacción administrativa.
 *
 * @param item fila remota desconocida
 * @returns `true` si contiene los campos reales necesarios por PanelDisputas
 */
function esDisputa(item: unknown): item is DisputaPanel {
  if (!item || typeof item !== 'object') return false;
  const dto = item as Record<string, unknown>;
  return esEntero(dto.id) && esEntero(dto.precioSnapshot) && typeof dto.estado === 'string'
    && typeof dto.fechaReservada === 'string' && typeof dto.publicacionDescripcion === 'string';
}

/**
 * Comprueba la forma mínima de una cuenta bloqueada.
 *
 * @param item fila remota desconocida
 * @returns `true` si tiene ID entero y email no vacío
 */
function esUsuarioBloqueado(item: unknown): item is UsuarioBloqueado {
  if (!item || typeof item !== 'object') return false;
  const dto = item as Record<string, unknown>;
  return esEntero(dto.usuarioId) && typeof dto.email === 'string' && dto.email.length > 0;
}

/**
 * Presenta la barrera visual común para una ruta administrativa.
 *
 * @param props contenido administrativo a ocultar a no-admin
 * @returns aviso sin acciones para USUARIO o contenido para ADMIN
 */
function SoloAdmin({ children }: { children: React.ReactNode }) {
  const usuario = useUsuarioSesion();
  if (usuario?.rol !== 'ADMIN') {
    return <div className="mx-auto max-w-[1280px] p-6"><div role="alert" className="rounded-lg border border-[#ba1a1a] bg-[#ffdad6] p-6 text-[#93000a]">No tienes permisos para acceder a esta sección.</div></div>;
  }
  return <>{children}</>;
}

/**
 * Renderiza el estado uniforme de carga o error de una lectura administrativa.
 *
 * @param props estado y mensaje remoto
 * @returns indicador accesible o banner de error
 */
function EstadoLectura({ estado, error }: { estado: EstadoCarga; error: string | null }) {
  if (estado === 'cargando') return <div role="status" aria-busy="true" className="rounded-lg border border-slate-200 bg-white p-6 text-slate-600">Cargando datos administrativos...</div>;
  if (estado === 'error') return <div role="alert" className="rounded-lg border border-[#ba1a1a] bg-[#ffdad6] p-6 text-[#93000a]">{error}</div>;
  return null;
}

/**
 * Página cliente del tablero con las seis métricas reales de Story 13.
 *
 * @returns grid responsive de indicadores o estados de carga/error
 */
export function DashboardAdmin() {
  const admin = useUsuarioSesion()?.rol === 'ADMIN';
  const cargar = useCallback(async (signal: AbortSignal) => validarTablero(await obtenerAdmin('/admin/tablero', signal)), []);
  const resultado = useCargaAdmin(cargar, admin);
  return <SoloAdmin><section className="mx-auto max-w-[1280px] space-y-6 p-4 sm:p-6"><div><h1 className="text-3xl font-bold tracking-tight text-[#0F172A]">Administración operativa</h1><p className="mt-1 text-slate-600">Indicadores reales para priorizar moderación y supervisar el escrow.</p></div>{resultado.estado !== 'listo' || !resultado.datos ? <EstadoLectura estado={resultado.estado} error={resultado.error} /> : <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">{[
    ['Publicaciones pendientes', String(resultado.datos.publicacionesPendientes)],
    ['Disputas abiertas', String(resultado.datos.disputasAbiertas)],
    ['Cuentas bloqueadas', String(resultado.datos.cuentasBloqueadas)],
    ['Volumen en escrow', formatearPrecioSoles(resultado.datos.volumenEscrowCentavos)],
    ['Fondos liberados', formatearPrecioSoles(resultado.datos.fondosLiberadosCentavos)],
    ['Transacciones finalizadas', String(resultado.datos.transaccionesFinalizadas)],
  ].map(([label, value]) => <article key={label} className="rounded-lg border border-slate-200 bg-white p-6"><h2 className="text-xs font-semibold uppercase tracking-wider text-slate-600">{label}</h2><p className="mt-3 font-mono text-2xl font-semibold text-[#0F172A]">{value}</p></article>)}</div>}</section></SoloAdmin>;
}

/**
 * Página cliente de moderación: carga publicaciones y árbol, y resuelve nombres por IDs.
 *
 * @returns PanelModeracion alimentado por contratos reales
 */
export function ModeracionAdmin() {
  const admin = useUsuarioSesion()?.rol === 'ADMIN';
  const cargar = useCallback(async (signal: AbortSignal): Promise<PublicacionPendiente[]> => {
    const [publicacionesRaw, categoriasRaw] = await Promise.all([obtenerAdmin('/publicaciones?estado=PENDIENTE_REVISION', signal), obtenerAdmin('/categorias', signal)]);
    const publicaciones = validarLista(publicacionesRaw, esPublicacion, 'La lista de publicaciones no tiene un formato válido');
    const categorias = validarLista(categoriasRaw, esCategoria, 'El catálogo no tiene un formato válido') as CategoriaListado[];
    return publicaciones.map((publicacion) => {
      const categoria = categorias.find((item) => item.id === publicacion.categoriaId);
      const subcategoria = categoria?.subcategorias.find((item) => item.id === publicacion.subcategoriaId);
      if (!categoria || !subcategoria) throw new Error('La clasificación de una publicación pendiente no existe en el catálogo');
      return { ...publicacion, categoriaNombre: categoria.nombre, subcategoriaNombre: subcategoria.nombre };
    });
  }, []);
  const resultado = useCargaAdmin(cargar, admin);
  return <SoloAdmin>{resultado.estado !== 'listo' || !resultado.datos ? <div className="mx-auto max-w-[1280px] p-6"><EstadoLectura estado={resultado.estado} error={resultado.error} /></div> : <PanelModeracion publicacionesIniciales={resultado.datos} />}</SoloAdmin>;
}

/**
 * Página cliente del catálogo con árbol canónico y CRUD de ambos niveles.
 *
 * @returns PanelCategorias con datos reales o estados de carga/error
 */
export function CategoriasAdmin() {
  const admin = useUsuarioSesion()?.rol === 'ADMIN';
  const cargar = useCallback(async (signal: AbortSignal) => validarLista(await obtenerAdmin('/categorias', signal), esCategoria, 'El catálogo no tiene un formato válido'), []);
  const resultado = useCargaAdmin(cargar, admin);
  return <SoloAdmin>{resultado.estado !== 'listo' || !resultado.datos ? <div className="mx-auto max-w-[1280px] p-6"><EstadoLectura estado={resultado.estado} error={resultado.error} /></div> : <PanelCategorias categoriasIniciales={resultado.datos} />}</SoloAdmin>;
}

/**
 * Página cliente de disputas que entrega lista y rol ADMIN reales al panel heredado.
 *
 * @returns PanelDisputas o estado de lectura
 */
export function DisputasAdmin() {
  const admin = useUsuarioSesion()?.rol === 'ADMIN';
  const cargar = useCallback(async (signal: AbortSignal) => validarLista(await obtenerAdmin('/admin/disputas', signal), esDisputa, 'La lista de disputas no tiene un formato válido'), []);
  const resultado = useCargaAdmin(cargar, admin);
  return <SoloAdmin>{resultado.estado !== 'listo' || !resultado.datos ? <div className="mx-auto max-w-[1280px] p-6"><EstadoLectura estado={resultado.estado} error={resultado.error} /></div> : <PanelDisputas disputasIniciales={resultado.datos} rol="ADMIN" />}</SoloAdmin>;
}

/**
 * Página cliente de cuentas bloqueadas con desbloqueo por ID real.
 *
 * @returns listado operativo o estados de carga/error
 */
export function UsuariosBloqueadosAdmin() {
  const admin = useUsuarioSesion()?.rol === 'ADMIN';
  const cargar = useCallback(async (signal: AbortSignal) => validarLista(await obtenerAdmin('/admin/usuarios/bloqueados', signal), esUsuarioBloqueado, 'La lista de cuentas bloqueadas no tiene un formato válido'), []);
  const resultado = useCargaAdmin(cargar, admin);
  const [usuariosRetirados, setUsuariosRetirados] = useState<Set<number>>(() => new Set());
  const [mensaje, setMensaje] = useState<string | null>(null);
  const [errorAccion, setErrorAccion] = useState<string | null>(null);
  const [enCurso, setEnCurso] = useState<number | null>(null);
  const desbloqueoEnCurso = useRef(false);
  const usuarios = resultado.datos?.filter((usuario) => !usuariosRetirados.has(usuario.usuarioId)) ?? null;

  /**
   * Desbloquea una cuenta y elimina exclusivamente la fila confirmada por HTTP 200.
   *
   * @param usuarioId ID real recibido en la lista administrativa
   * @returns promesa que termina al actualizar el estado visible
   */
  const desbloquear = async (usuarioId: number): Promise<void> => {
    if (desbloqueoEnCurso.current) return;
    desbloqueoEnCurso.current = true;
    setEnCurso(usuarioId); setMensaje(null); setErrorAccion(null);
    try {
      const response = await fetch(`${process.env.NEXT_PUBLIC_API_URL ?? ''}/admin/usuarios/${usuarioId}/desbloquear`, { method: 'POST', credentials: 'include' });
      if (!response.ok) throw new Error(await extraerError(response, `Error al desbloquear (código ${response.status})`));
      const body: unknown = await response.json();
      const confirmacion = body && typeof body === 'object' && 'mensaje' in body && typeof (body as { mensaje?: unknown }).mensaje === 'string' ? (body as { mensaje: string }).mensaje : 'Cuenta desbloqueada';
      setUsuariosRetirados((actuales) => new Set(actuales).add(usuarioId));
      setMensaje(confirmacion);
    } catch (error) {
      setErrorAccion(error instanceof Error ? error.message : 'Error inesperado');
    } finally { desbloqueoEnCurso.current = false; setEnCurso(null); }
  };

  return <SoloAdmin><section className="mx-auto max-w-[1280px] space-y-6 p-4 sm:p-6"><h1 className="text-3xl font-bold tracking-tight text-[#0F172A]">Cuentas bloqueadas</h1>{mensaje && <div role="status" className="rounded border border-[#10B981] bg-emerald-50 p-3 text-emerald-800">{mensaje}</div>}{errorAccion && <div role="alert" className="rounded border border-[#ba1a1a] bg-[#ffdad6] p-3 text-[#93000a]">{errorAccion}</div>}{resultado.estado !== 'listo' || usuarios === null ? <EstadoLectura estado={resultado.estado} error={resultado.error} /> : usuarios.length === 0 ? <div className="rounded-lg border border-slate-200 bg-white p-6 text-slate-600">No hay cuentas con bloqueo permanente.</div> : <ul className="space-y-3">{usuarios.map((usuario) => <li key={usuario.usuarioId} className="flex flex-col gap-3 rounded-lg border border-slate-200 bg-white p-5 sm:flex-row sm:items-center sm:justify-between"><span>{usuario.email}</span><button type="button" disabled={enCurso !== null} onClick={() => void desbloquear(usuario.usuarioId)} className="rounded border border-[#0F172A] bg-[#0F172A] px-4 py-2 text-sm font-semibold text-white disabled:opacity-50">Desbloquear</button></li>)}</ul>}</section></SoloAdmin>;
}
