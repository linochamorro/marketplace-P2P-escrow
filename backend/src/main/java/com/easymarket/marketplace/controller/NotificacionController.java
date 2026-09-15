package com.easymarket.marketplace.controller;

import com.easymarket.marketplace.dto.CantidadNoLeidasResponseDto;
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
 * <p>Expone tres operaciones, todas delegadas en {@link NotificacionService} — el controlador no
 * toca repositorios ni aplica lógica de filtrado por su cuenta:</p>
 * <ul>
 *   <li>{@code GET /notificaciones}: lista las notificaciones VISIBLES del usuario autenticado
 *       según su rol en el JWT (desde PHA15TSK06, decisión de Lino 2026-08-30: el listado es un
 *       SUPERCONJUNTO del badge — ADMIN: moderación/disputas más {@code NUEVA_PUBLICACION_PENDIENTE};
 *       USER: compra/venta/envío/disputa más los recordatorios periódicos del propio rol)
 *       mediante {@code listarPorUsuarioYRol}. El filtro por rol se aplica en backend (decisión 3
 *       de PHA09TSK05-L01); la identidad proviene exclusivamente de {@link UsuarioPrincipal},
 *       sin filtros, paginación ni parámetros de consulta en el contrato.</li>
 *   <li>{@code PATCH /notificaciones/{id}/leer}: marca una notificación como leída mediante
 *       {@code marcarComoLeida(notificacionId, usuarioId)} con validación de propiedad — 404 si
 *       la notificación no existe ({@code NotificacionNoEncontradaException}) y 403 si pertenece
 *       a otro usuario ({@code UsuarioNoAutorizadoException}), ambos mapeados en
 *       {@code GlobalExceptionHandler}. La operación es idempotente: marcar una notificación ya
 *       leída retorna 200 con la misma entidad.</li>
 *   <li>{@code GET /notificaciones/no-leidas/count} (PHA15TSK05): contador {@code {"cantidad": N}}
 *       de notificaciones accionables no leídas del rol del JWT, con el criterio accionable
 *       puro — un SUBCONJUNTO del listado visible desde PHA15TSK06 (plan.md §Notificaciones,
 *       fila "Visibilidad del panel (listado vs badge)"); sin parámetros del cliente
 *       (constitution, principio 7).</li>
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
     * Endpoint REST {@code GET /notificaciones} para listar las notificaciones visibles del
     * usuario autenticado según su rol (Story 7b; PHA09TSK05 recuperado en PHA12TSK04;
     * superconjunto del badge desde PHA15TSK06 por decisión de Lino 2026-08-30).
     *
     * <p>Devuelve un array JSON (posiblemente vacío) con SOLO las notificaciones cuyo tipo
     * pertenece a la lista VISIBLE del rol del JWT, ordenadas por fecha de creación descendente
     * (con el ID como desempate). No expone el destinatario porque la identidad se resuelve
     * exclusivamente desde {@code principal.id()} y el filtro de tipos se resuelve
     * exclusivamente desde {@code principal.rol()}.</p>
     *
     * @param principal identidad y rol del usuario autenticado mediante JWT
     * @return {@link ResponseEntity} con código HTTP 200 OK y lista de DTOs visibles del rol
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

    /**
     * Endpoint REST {@code GET /notificaciones/no-leidas/count} para el contador de notificaciones
     * accionables no leídas del usuario autenticado (PHA15TSK05; plan.md §Notificaciones, fila
     * "Contador de pendientes").
     *
     * <p>Devuelve la forma exacta {@code {"cantidad": N}} exigida por el contrato, contando
     * EXCLUSIVAMENTE las notificaciones accionables del rol del JWT con {@code leida=false}
     * mediante {@code NotificacionService.contarNoLeidasPorUsuarioYRol} — un SUBCONJUNTO del
     * listado visible de {@code GET /notificaciones} desde PHA15TSK06 (decisión de Lino
     * 2026-08-30): los recordatorios periódicos del USUARIO y {@code NUEVA_PUBLICACION_PENDIENTE}
     * del ADMIN son visibles en el panel pero NO inflan el contador. No acepta ni lee parámetro
     * alguno: usuario y rol se resuelven exclusivamente desde {@code principal.id()} y
     * {@code principal.rol()} (constitution, principio 7), y el conteo de otro usuario jamás
     * puede solicitarse desde el cliente. La ruta queda protegida por
     * {@code anyRequest().authenticated()} de {@code SecurityConfig} (sin requestMatcher nuevo).</p>
     *
     * @param principal identidad y rol del usuario autenticado mediante JWT
     * @return {@link ResponseEntity} con código HTTP 200 OK y el DTO {@code {"cantidad": N}};
     *         {@code cantidad} es {@code 0} cuando el autenticado no tiene ninguna
     */
    @GetMapping("/no-leidas/count")
    public ResponseEntity<CantidadNoLeidasResponseDto> contarNoLeidas(
            @AuthenticationPrincipal UsuarioPrincipal principal
    ) {
        long cantidad = notificacionService.contarNoLeidasPorUsuarioYRol(principal.id(), principal.rol());
        return ResponseEntity.ok(new CantidadNoLeidasResponseDto(cantidad));
    }
}
