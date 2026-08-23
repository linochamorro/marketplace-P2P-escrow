package com.easymarket.marketplace.service;

import com.easymarket.marketplace.exception.AdministradorNoEncontradoException;
import com.easymarket.marketplace.exception.NotificacionNoEncontradaException;
import com.easymarket.marketplace.exception.UsuarioNoAutorizadoException;
import com.easymarket.marketplace.model.Notificacion;
import com.easymarket.marketplace.model.Rol;
import com.easymarket.marketplace.model.Transaccion;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.NotificacionRepository;
import com.easymarket.marketplace.repository.UsuarioRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;

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
 */
@Service
public class NotificacionService {

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
        Notificacion notificacion = new Notificacion(admin, transaccion, mensaje, tipo, ahora);
        return notificacionRepository.save(notificacion);
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
        Notificacion notificacion = new Notificacion(destinatario, transaccion, mensaje, tipo, ahora);
        return notificacionRepository.save(notificacion);
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
     * Lista notificaciones del usuario filtradas por rol (backend filtra, no frontend).
     *
     * <p>El filtro por rol se aplica en la capa de dominio/servicio, no en el controlador,
     * para que la lógica sea reutilizable y testeable. ADMIN ve solo notificaciones de
     * moderación/disputas; USER ve notificaciones de su proceso compra/venta/envío/disputa.</p>
     *
     * @param usuarioId ID del usuario autenticado
     * @param rol rol del usuario ({@link Rol#ADMIN} o {@link Rol#USUARIO})
     * @return lista de notificaciones filtradas por rol, ordenadas por creación descendente
     */
    @Transactional(readOnly = true)
    public List<Notificacion> listarPorUsuarioYRol(Long usuarioId, Rol rol) {
        if (rol == Rol.ADMIN) {
            return notificacionRepository.findByUsuarioIdAndTipoInOrderByCreatedAtDescIdDesc(
                    usuarioId,
                    List.of("PUBLICACION_PENDIENTE_APROBAR", "DISPUTA_PENDIENTE_RESOLVER")
            );
        }
        // USUARIO: compra/venta/envío/disputa
        return notificacionRepository.findByUsuarioIdAndTipoInOrderByCreatedAtDescIdDesc(
                usuarioId,
                List.of(
                        "RESPUESTA_USUARIO_PENDIENTE",
                        "PUBLICACION_APROBADA_RECHAZADA",
                        "COMPRA_CONFIRMADA",
                        "ENVIO_MARCADO",
                        "ENTREGA_MARCADA",
                        "DISPUTA_ABIERTA",
                        "DISPUTA_RESUELTA"
                )
        );
    }
}