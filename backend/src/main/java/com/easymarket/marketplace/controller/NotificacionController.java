package com.easymarket.marketplace.controller;

import com.easymarket.marketplace.dto.NotificacionResponseDto;
import com.easymarket.marketplace.model.Notificacion;
import com.easymarket.marketplace.security.UsuarioPrincipal;
import com.easymarket.marketplace.service.NotificacionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Endpoint REST de notificaciones in-app del usuario autenticado (Story 7b, spec.md; PHA09TSK05
 * recuperado en PHA12TSK04).
 *
 * <p>Expone dos operaciones, ambas delegadas en {@link NotificacionService} — el controlador no
 * toca repositorios ni aplica lógica de filtrado por su cuenta:</p>
 * <ul>
 *   <li>{@code GET /notificaciones}: lista las notificaciones del usuario autenticado cuyo tipo
 *       pertenece a la lista de SU ROL en el JWT (ADMIN: moderación/disputas; USER:
 *       compra/venta/envío/disputa) mediante {@code listarPorUsuarioYRol}. El filtro por rol se
 *       aplica en backend (decisión 3 de PHA09TSK05-L01); la identidad proviene exclusivamente de
 *       {@link UsuarioPrincipal}, sin filtros, paginación ni parámetros de consulta en el
 *       contrato.</li>
 *   <li>{@code PATCH /notificaciones/{id}/leer}: marca una notificación como leída mediante
 *       {@code marcarComoLeida(notificacionId, usuarioId)} con validación de propiedad — 404 si
 *       la notificación no existe ({@code NotificacionNoEncontradaException}) y 403 si pertenece
 *       a otro usuario ({@code UsuarioNoAutorizadoException}), ambos mapeados en
 *       {@code GlobalExceptionHandler}. La operación es idempotente: marcar una notificación ya
 *       leída retorna 200 con la misma entidad.</li>
 * </ul>
 *
 * <p>Las rutas quedan protegidas por {@code anyRequest().authenticated()} de
 * {@code SecurityConfig} (sin requestMatcher nuevo).</p>
 */
@RestController
@RequestMapping("/notificaciones")
public class NotificacionController {

    private final NotificacionService notificacionService;

    /**
     * Construye el controlador inyectando el servicio de dominio de notificaciones.
     *
     * @param notificacionService servicio que centraliza el listado filtrado por rol y la
     *                            transición de marcaje de lectura
     */
    public NotificacionController(NotificacionService notificacionService) {
        this.notificacionService = notificacionService;
    }

    /**
     * Endpoint REST {@code GET /notificaciones} para listar las notificaciones accionables del
     * usuario autenticado según su rol (Story 7b; PHA09TSK05 recuperado en PHA12TSK04).
     *
     * <p>Devuelve un array JSON (posiblemente vacío) con SOLO las notificaciones cuyo tipo
     * pertenece a la lista del rol del JWT, ordenadas por fecha de creación descendente (con el
     * ID como desempate). No expone el destinatario porque la identidad se resuelve
     * exclusivamente desde {@code principal.id()} y el filtro de tipos se resuelve exclusivamente
     * desde {@code principal.rol()}.</p>
     *
     * @param principal identidad y rol del usuario autenticado mediante JWT
     * @return {@link ResponseEntity} con código HTTP 200 OK y lista de DTOs filtrada por rol
     */
    @GetMapping
    public ResponseEntity<List<NotificacionResponseDto>> listarPorUsuario(
            @AuthenticationPrincipal UsuarioPrincipal principal
    ) {
        List<NotificacionResponseDto> resultado = notificacionService
                .listarPorUsuarioYRol(principal.id(), principal.rol())
                .stream()
                .map(NotificacionResponseDto::fromEntity)
                .toList();
        return ResponseEntity.ok(resultado);
    }

    /**
     * Endpoint REST {@code PATCH /notificaciones/{id}/leer} para marcar como leída una
     * notificación del usuario autenticado (PHA09TSK05 recuperado en PHA12TSK04).
     *
     * <p>Delega en {@code NotificacionService.marcarComoLeida}, que valida propiedad y persiste
     * {@code leida=true} dentro de una transacción atómica. La respuesta porta el DTO completo de
     * la entidad actualizada para que el cliente actualice su estado local sin reconsultar el
     * listado; repetir la llamada sobre una notificación ya leída retorna 200 con esa misma
     * entidad (idempotencia). Los códigos de error los traduce
     * {@code GlobalExceptionHandler}: 404 si la notificación no existe y 403 si el autenticado
     * no es su destinatario.</p>
     *
     * @param notificacionId identificador de la notificación a marcar (path)
     * @param principal identidad del usuario autenticado mediante JWT (fuente única del usuarioId)
     * @return {@link ResponseEntity} con código HTTP 200 OK y el DTO de la notificación actualizada
     */
    @PatchMapping("/{notificacionId}/leer")
    public ResponseEntity<NotificacionResponseDto> marcarComoLeida(
            @PathVariable Long notificacionId,
            @AuthenticationPrincipal UsuarioPrincipal principal
    ) {
        Notificacion actualizada = notificacionService.marcarComoLeida(notificacionId, principal.id());
        return ResponseEntity.ok(NotificacionResponseDto.fromEntity(actualizada));
    }
}
