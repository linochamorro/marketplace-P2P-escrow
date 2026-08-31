package com.easymarket.marketplace.service;

import com.easymarket.marketplace.exception.AdministradorNoEncontradoException;
import com.easymarket.marketplace.exception.NotificacionNoEncontradaException;
import com.easymarket.marketplace.exception.UsuarioNoAutorizadoException;
import com.easymarket.marketplace.model.Notificacion;
import com.easymarket.marketplace.model.Publicacion;
import com.easymarket.marketplace.model.Rol;
import com.easymarket.marketplace.model.Transaccion;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.NotificacionRepository;
import com.easymarket.marketplace.repository.UsuarioRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

/**
 * Servicio de dominio para la gestión de notificaciones in-app accionables por rol (PHA09TSK05).
 *
 * <p>Centraliza la creación de notificaciones con tipos estables y la operación de marcar como
 * leída con validación de propiedad. Los tipos de notificación se dividen por rol de destino:</p>
 * <ul>
 *   <li><strong>ADMIN</strong>: {@code PUBLICACION_PENDIENTE_APROBAR}, {@code DISPUTA_PENDIENTE_RESOLVER}</li>
 *   <li><strong>USER (comprador/vendedor)</strong>: {@code RESPUESTA_USUARIO_PENDIENTE},
 *       {@code PUBLICACION_APROBADA_RECHAZADA}, {@code COMPRA_CONFIRMADA}, {@code ENVIO_MARCADO},
 *       {@code ENTREGA_MARCADA}, {@code DISPUTA_ABIERTA}, {@code DISPUTA_RESUELTA}</li>
 * </ul>
 *
 * <p>La creación de notificaciones ocurre dentro de la misma transacción que el evento de
 * negocio que la origina (moderación, disputa, compra, envío, entrega), garantizando atomicidad
 * (constitución, principio 1). No se emiten notificaciones desde jobs preexistentes — solo
 * desde los servicios de dominio que ejecutan las transiciones de estado.</p>
 *
 * <p>Las consultas de lectura conviven con dos criterios deliberados (PHA15TSK06, plan.md
 * §Notificaciones, fila "Visibilidad del panel (listado vs badge)", decisión de Lino
 * 2026-08-30):</p>
 * <ul>
 *   <li>El <strong>badge</strong> ({@code contarNoLeidasPorUsuarioYRol}, PHA15TSK05) cuenta
 *       EXCLUSIVAMENTE los tipos accionables del rol: los recordatorios periódicos no inflan
 *       el contador de pendientes.</li>
 *   <li>El <strong>listado</strong> del panel ({@code listarPorUsuarioYRol}) devuelve un
 *       SUPERCONJUNTO del badge: además de los accionables del rol, incluye los recordatorios
 *       periódicos del USUARIO ({@code COMPRA_PENDIENTE_DIARIA}, {@code VENTA_POR_ENTREGAR_DIARIA},
 *       {@code ENVIO_PENDIENTE_48H}) y {@code NUEVA_PUBLICACION_PENDIENTE} para el ADMIN, para
 *       que el historial de recordatorios sea visible sin inflar el contador.</li>
 * </ul>
 */
@Service
public class NotificacionService {

    /** Daily notification types that intentionally retain one row per eligible interval. */
    private static final Set<String> TIPOS_PERIODICOS = Set.of(
            "COMPRA_PENDIENTE_DIARIA", "VENTA_POR_ENTREGAR_DIARIA");

    /**
     * Tipos accionables del rol ADMIN (moderación y disputas). Fuente única del criterio
     * ACCIONABLE por rol: la usa el contador {@code contarNoLeidasPorUsuarioYRol} (PHA15TSK05)
     * para que el badge cuente exclusivamente pendientes de acción. Los avisos
     * diarios/periódicos NO pertenecen a este set (plan.md §Notificaciones, fila "Unicidad por
     * elemento pendiente": conservan historial propio). Desde PHA15TSK06 el LISTADO usa la
     * lista visible (superconjunto de esta) en {@link #TIPOS_VISIBLES_ADMIN}.
     */
    private static final List<String> TIPOS_ACCIONABLES_ADMIN = List.of(
            "PUBLICACION_PENDIENTE_APROBAR", "DISPUTA_PENDIENTE_RESOLVER");

    /**
     * Tipos accionables del rol USUARIO (compra/venta/envío/disputa). Misma fuente única y misma
     * exclusión de diarios que {@link #TIPOS_ACCIONABLES_ADMIN}. Desde PHA15TSK06 el LISTADO usa
     * la lista visible (superconjunto de esta) en {@link #TIPOS_VISIBLES_USUARIO}.
     */
    private static final List<String> TIPOS_ACCIONABLES_USUARIO = List.of(
            "RESPUESTA_USUARIO_PENDIENTE",
            "PUBLICACION_APROBADA_RECHAZADA",
            "COMPRA_CONFIRMADA",
            "ENVIO_MARCADO",
            "ENTREGA_MARCADA",
            "DISPUTA_ABIERTA",
            "DISPUTA_RESUELTA"
    );

    /**
     * Tipos VISIBLES en el listado del panel para el rol ADMIN (PHA15TSK06, plan.md §Notificaciones
     * fila "Visibilidad del panel (listado vs badge)", decisión de Lino 2026-08-30): los 2
     * accionables del rol MÁS {@code NUEVA_PUBLICACION_PENDIENTE}. El listado es un SUPERCONJUNTO
     * del badge; el contador conserva la lista accionable pura.
     */
    private static final List<String> TIPOS_VISIBLES_ADMIN = List.of(
            "PUBLICACION_PENDIENTE_APROBAR",
            "DISPUTA_PENDIENTE_RESOLVER",
            "NUEVA_PUBLICACION_PENDIENTE");

    /**
     * Tipos VISIBLES en el listado del panel para el rol USUARIO (PHA15TSK06, plan.md
     * §Notificaciones fila "Visibilidad del panel (listado vs badge)", decisión de Lino
     * 2026-08-30): los 7 accionables del rol MÁS los 3 recordatorios periódicos del propio rol
     * ({@code COMPRA_PENDIENTE_DIARIA}, {@code VENTA_POR_ENTREGAR_DIARIA},
     * {@code ENVIO_PENDIENTE_48H}) para que su historial sea visible en el panel sin inflar el
     * badge. El listado es un SUPERCONJUNTO del badge; el contador conserva la lista accionable
     * pura.
     */
    private static final List<String> TIPOS_VISIBLES_USUARIO = List.of(
            "RESPUESTA_USUARIO_PENDIENTE",
            "PUBLICACION_APROBADA_RECHAZADA",
            "COMPRA_CONFIRMADA",
            "ENVIO_MARCADO",
            "ENTREGA_MARCADA",
            "DISPUTA_ABIERTA",
            "DISPUTA_RESUELTA",
            "COMPRA_PENDIENTE_DIARIA",
            "VENTA_POR_ENTREGAR_DIARIA",
            "ENVIO_PENDIENTE_48H"
    );

    private final NotificacionRepository notificacionRepository;
    private final UsuarioRepository usuarioRepository;

    /**
     * Construye el servicio inyectando los repositorios necesarios.
     *
     * @param notificacionRepository repositorio JPA de notificaciones in-app
     * @param usuarioRepository repositorio JPA de usuarios (para obtener admin único)
     */
    public NotificacionService(NotificacionRepository notificacionRepository,
                                UsuarioRepository usuarioRepository) {
        this.notificacionRepository = notificacionRepository;
        this.usuarioRepository = usuarioRepository;
    }

    /**
     * Crea una notificación accionable para el administrador único (rol ADMIN).
     *
     * <p>Se invoca cuando una publicación pasa a {@code PENDIENTE_REVISION} (creación o
     * corrección) o cuando se abre una disputa que requiere resolución administrativa.
     * La notificación se dirige al único usuario con {@link Rol#ADMIN}.</p>
     *
     * @param tipo tipo de notificación para admin ({@code PUBLICACION_PENDIENTE_APROBAR} o
     *             {@code DISPUTA_PENDIENTE_RESOLVER})
     * @param mensaje contenido literal del aviso
     * @param transaccion transacción asociada opcional (null para moderación de publicación)
     * @param ahora instante de creación (usar {@code ZonedDateTime.now()} en producción)
     * @return notificación creada y persistida
     * @throws AdministradorNoEncontradoException si no existe la cuenta ADMIN única
     */
    @Transactional
    public Notificacion crearNotificacionAdmin(String tipo, String mensaje, Transaccion transaccion,
                                                 ZonedDateTime ahora) {
        Usuario admin = usuarioRepository.findByRol(Rol.ADMIN)
                .orElseThrow(() -> new AdministradorNoEncontradoException(
                        "No existe la cuenta ADMIN única requerida para notificar acción administrativa"));
        return guardarReutilizando(admin, mensaje, tipo, null, transaccion, ahora);
    }

    /**
     * Creates or reactivates the ADMIN slot associated with a pending publication.
     *
     * @param tipo stable notification type
     * @param mensaje literal content
     * @param publicacion pending publication
     * @param transaccion optional transaction association
     * @param ahora timestamp for creation or reactivation
     * @return inserted or reactivated notification
     */
    @Transactional
    public Notificacion crearNotificacionAdmin(String tipo, String mensaje, Publicacion publicacion,
                                                Transaccion transaccion, ZonedDateTime ahora) {
        Usuario admin = usuarioRepository.findByRol(Rol.ADMIN)
                .orElseThrow(() -> new AdministradorNoEncontradoException(
                        "No existe la cuenta ADMIN única requerida para notificar acción administrativa"));
        return guardarReutilizando(admin, mensaje, tipo, publicacion, transaccion, ahora);
    }

    /**
     * Crea una notificación accionable para un usuario específico (comprador o vendedor).
     *
     * <p>Se invoca desde servicios de dominio cuando ocurren eventos que afectan al usuario:
     * confirmación de compra, marcado de envío/entrega, apertura/resolución de disputa,
     * aprobación/rechazo de publicación propia, respuesta de usuario pendiente.</p>
     *
     * @param destinatario usuario que recibe la notificación
     * @param tipo tipo de notificación para usuario (ver lista en JavaDoc de la clase)
     * @param mensaje contenido literal del aviso
     * @param transaccion transacción asociada (puede ser null para notificaciones sin transacción)
     * @param ahora instante de creación
     * @return notificación creada y persistida
     */
    @Transactional
    public Notificacion crearNotificacionUsuario(Usuario destinatario, String tipo, String mensaje,
                                                   Transaccion transaccion, ZonedDateTime ahora) {
        return guardarReutilizando(destinatario, mensaje, tipo, null, transaccion, ahora);
    }

    /**
     * Inserts a recurrent daily transaction notification without consulting the idempotent slot.
     *
     * <p>This explicit route is reserved for the two daily reminder types. Their rows form
     * notification history and therefore must not be reused by the normal pending-element route.
     * The database partial unique index in V20 excludes exactly these types.</p>
     *
     * @param destinatario recipient of the daily reminder
     * @param tipo daily reminder type
     * @param mensaje literal reminder content
     * @param transaccion open transaction associated with the reminder
     * @param ahora creation timestamp
     * @return newly inserted daily notification
     * @throws IllegalArgumentException when {@code tipo} is not a supported daily type
     */
    @Transactional
    public Notificacion crearNotificacionDiaria(Usuario destinatario, String tipo, String mensaje,
                                                 Transaccion transaccion, ZonedDateTime ahora) {
        if (!TIPOS_PERIODICOS.contains(tipo)) {
            throw new IllegalArgumentException("Tipo no periódico para la ruta diaria: " + tipo);
        }
        return notificacionRepository.save(new Notificacion(
                destinatario, transaccion, mensaje, tipo, ahora));
    }

    /**
     * Reads the latest historical row for a supported daily reminder.
     *
     * @param transaccionId transaction identifier
     * @param usuarioId recipient identifier
     * @param tipo supported daily reminder type
     * @return latest matching row, or empty when no reminder exists
     * @throws IllegalArgumentException when {@code tipo} is not a supported daily type
     */
    @Transactional(readOnly = true)
    public java.util.Optional<Notificacion> ultimoAvisoDiario(Long transaccionId, Long usuarioId,
                                                               String tipo) {
        if (!TIPOS_PERIODICOS.contains(tipo)) {
            throw new IllegalArgumentException("Tipo no periódico para la ruta diaria: " + tipo);
        }
        return notificacionRepository.findFirstByTransaccion_IdAndUsuario_IdAndTipoOrderByCreatedAtDesc(
                transaccionId, usuarioId, tipo);
    }

    /**
     * Creates or reactivates the unique user slot for a pending element.
     *
     * @param destinatario recipient user
     * @param tipo stable notification type
     * @param mensaje literal content
     * @param publicacion optional pending publication
     * @param transaccion optional transaction association
     * @param ahora timestamp for creation or reactivation
     * @return inserted or reactivated notification
     */
    @Transactional
    public Notificacion crearNotificacionUsuario(Usuario destinatario, String tipo, String mensaje,
                                                  Publicacion publicacion, Transaccion transaccion,
                                                  ZonedDateTime ahora) {
        return guardarReutilizando(destinatario, mensaje, tipo, publicacion, transaccion, ahora);
    }

    /**
     * Reuses the database slot identified by the non-null business association, or inserts it.
     * A notification is active while its caller's business element remains pending; {@code leida}
     * is only a presentation flag, so reactivation clears it without creating an audit event.
     *
     * @param destinatario recipient user
     * @param mensaje literal content
     * @param tipo stable type
     * @param publicacion optional publication key
     * @param transaccion optional transaction key
     * @param ahora creation/reactivation time
     * @return persisted notification
     */
    private Notificacion guardarReutilizando(Usuario destinatario, String mensaje, String tipo,
                                              Publicacion publicacion, Transaccion transaccion,
                                              ZonedDateTime ahora) {
        if (TIPOS_PERIODICOS.contains(tipo)) {
            throw new IllegalArgumentException("Los avisos diarios requieren la ruta histórica explícita");
        }
        if (transaccion != null) {
            notificacionRepository.upsertTransaccion(destinatario.getId(), transaccion.getId(), tipo, mensaje, ahora);
            Notificacion notificacion = notificacionRepository.findByUsuario_IdAndTransaccion_IdAndTipo(
                    destinatario.getId(), transaccion.getId(), tipo).orElseThrow();
            return reactivar(notificacion, publicacion, mensaje, ahora);
        }
        if (publicacion != null) {
            notificacionRepository.upsertPublicacion(destinatario.getId(), publicacion.getId(), tipo, mensaje, ahora);
            Notificacion notificacion = notificacionRepository.findByUsuario_IdAndPublicacion_IdAndTipo(
                    destinatario.getId(), publicacion.getId(), tipo).orElseThrow();
            return reactivar(notificacion, publicacion, mensaje, ahora);
        }
        return notificacionRepository.save(new Notificacion(
                destinatario, publicacion, transaccion, mensaje, tipo, ahora));
    }

    /**
     * Synchronizes the entity returned after an atomic upsert with the values of the winning slot.
     *
     * @param notificacion row returned by the repository
     * @param publicacion optional publication association
     * @param mensaje current literal message
     * @param ahora current creation/reactivation timestamp
     * @return the reactivated notification entity
     */
    private Notificacion reactivar(Notificacion notificacion, Publicacion publicacion,
                                   String mensaje, ZonedDateTime ahora) {
        if (publicacion != null) {
            notificacion.setPublicacion(publicacion);
        }
        notificacion.setLeida(false);
        notificacion.setMensaje(mensaje);
        notificacion.setCreatedAt(ahora);
        return notificacion;
    }

    /**
     * Marca una notificación como leída validando que pertenece al usuario solicitante.
     *
     * <p>Corresponde al endpoint {@code PATCH /notificaciones/{id}/leer}. La validación de
     * propiedad garantiza que un usuario no puede marcar como leídas las notificaciones de
     * otro usuario (constitución, principio 1 — operación atómica sobre dato propio).</p>
     *
     * @param notificacionId ID de la notificación a marcar
     * @param usuarioId ID del usuario autenticado (desde JWT)
     * @return notificación actualizada con {@code leida = true}
     * @throws NotificacionNoEncontradaException si la notificación no existe
     * @throws UsuarioNoAutorizadoException si la notificación pertenece a otro usuario
     */
    @Transactional
    public Notificacion marcarComoLeida(Long notificacionId, Long usuarioId) {
        Notificacion notificacion = notificacionRepository.findById(notificacionId)
                .orElseThrow(() -> new NotificacionNoEncontradaException(
                        "Notificación con ID " + notificacionId + " no encontrada"));

        if (!notificacion.getUsuario().getId().equals(usuarioId)) {
            throw new UsuarioNoAutorizadoException(
                    "El usuario " + usuarioId + " no es el destinatario de la notificación " + notificacionId);
        }

        notificacion.setLeida(true);
        return notificacionRepository.save(notificacion);
    }

    /**
     * Lista las notificaciones VISIBLES del usuario filtradas por rol (backend filtra, no
     * frontend).
     *
     * <p>Desde PHA15TSK06 (plan.md §Notificaciones, fila "Visibilidad del panel (listado vs
     * badge)", decisión de Lino 2026-08-30) el listado devuelve un SUPERCONJUNTO del badge: usa
     * la lista de tipos visibles del rol ({@link #tiposVisiblesPorRol(Rol)}) — los accionables
     * del rol MÁS, para USER, los recordatorios periódicos de su propio rol
     * ({@code COMPRA_PENDIENTE_DIARIA}, {@code VENTA_POR_ENTREGAR_DIARIA},
     * {@code ENVIO_PENDIENTE_48H}) y, para ADMIN, {@code NUEVA_PUBLICACION_PENDIENTE} — de modo
     * que el historial de recordatorios es visible en el panel. Los tipos del OTRO rol y los
     * tipos sin rol asignado siguen excluidos. El filtro por rol se aplica en la capa de
     * dominio/servicio, no en el controlador, para que la lógica sea reutilizable y testeable.</p>
     *
     * @param usuarioId ID del usuario autenticado
     * @param rol rol del usuario ({@link Rol#ADMIN} o {@link Rol#USUARIO})
     * @return lista de notificaciones visibles según el rol, ordenadas por creación descendente
     */
    @Transactional(readOnly = true)
    public List<Notificacion> listarPorUsuarioYRol(Long usuarioId, Rol rol) {
        return notificacionRepository.findByUsuarioIdAndTipoInOrderByCreatedAtDescIdDesc(
                usuarioId, tiposVisiblesPorRol(rol));
    }

    /**
     * Cuenta las notificaciones accionables NO leídas del usuario autenticado según su rol
     * (PHA15TSK05, plan.md §Notificaciones fila "Contador de pendientes").
     *
     * <p>El conteo usa EXCLUSIVAMENTE la lista de tipos ACCIONABLES del rol
     * ({@link #tiposAccionablesPorRol(Rol)}) filtrado por {@code leida=false}: los recordatorios
     * periódicos del USUARIO y {@code NUEVA_PUBLICACION_PENDIENTE} del ADMIN — que SÍ son
     * visibles en el listado desde PHA15TSK06 (el listado es un superconjunto del badge,
     * decisión de Lino 2026-08-30) — NO inflan el contador de pendientes. La identidad proviene
     * exclusivamente del JWT — el método no recibe ni considera dato alguno enviado por el
     * cliente (constitution, principio 7). Consulta de agregación de solo lectura en un único
     * acceso a base de datos, sin hidratación de entidades.</p>
     *
     * @param usuarioId ID del usuario autenticado (desde JWT)
     * @param rol rol del usuario autenticado ({@link Rol#ADMIN} o {@link Rol#USUARIO}, desde JWT)
     * @return cantidad de notificaciones accionables no leídas del usuario; {@code 0} cuando no
     *         tiene ninguna
     */
    @Transactional(readOnly = true)
    public long contarNoLeidasPorUsuarioYRol(Long usuarioId, Rol rol) {
        return notificacionRepository.countByUsuarioIdAndTipoInAndLeidaFalse(
                usuarioId, tiposAccionablesPorRol(rol));
    }

    /**
     * Resuelve la lista de tipos VISIBLES de un rol para el LISTADO del panel (PHA15TSK06):
     * superconjunto de la lista accionable del mismo rol — incluye los recordatorios periódicos
     * del USUARIO y {@code NUEVA_PUBLICACION_PENDIENTE} para el ADMIN, según plan.md
     * §Notificaciones fila "Visibilidad del panel (listado vs badge)", decisión de Lino
     * 2026-08-30. Definición única compartida por el listado; el contador NO la usa (cuenta con
     * la lista accionable pura de {@link #tiposAccionablesPorRol(Rol)}).
     *
     * @param rol rol del usuario autenticado
     * @return lista inmutable de tipos visibles para el rol
     */
    private static List<String> tiposVisiblesPorRol(Rol rol) {
        if (rol == Rol.ADMIN) {
            return TIPOS_VISIBLES_ADMIN;
        }
        // USUARIO: compra/venta/envío/disputa + recordatorios periódicos propios
        return TIPOS_VISIBLES_USUARIO;
    }

    /**
     * Resuelve la lista de tipos ACCIONABLES de un rol, criterio exclusivo del BADGE
     * ({@code contarNoLeidasPorUsuarioYRol}, PHA15TSK05): los recordatorios periódicos y
     * {@code NUEVA_PUBLICACION_PENDIENTE} quedan fuera del conteo. Desde PHA15TSK06 el listado
     * usa su propia lista visible ({@link #tiposVisiblesPorRol(Rol)}) — las dos familias de
     * listas conviven deliberadamente (plan.md §Notificaciones, fila "Visibilidad del panel
     * (listado vs badge)").
     *
     * @param rol rol del usuario autenticado
     * @return lista inmutable de tipos accionables para el rol
     */
    private static List<String> tiposAccionablesPorRol(Rol rol) {
        if (rol == Rol.ADMIN) {
            return TIPOS_ACCIONABLES_ADMIN;
        }
        // USUARIO: compra/venta/envío/disputa
        return TIPOS_ACCIONABLES_USUARIO;
    }
}
