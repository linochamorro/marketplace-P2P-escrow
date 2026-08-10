package com.easymarket.marketplace.controller;

import com.easymarket.marketplace.dto.NotificacionResponseDto;
import com.easymarket.marketplace.repository.NotificacionRepository;
import com.easymarket.marketplace.security.UsuarioPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Endpoint REST de consulta de notificaciones in-app del usuario autenticado (Story 7b, spec.md).
 *
 * <p>Expone {@code GET /notificaciones}: un usuario (comprador o vendedor) recibe avisos in-app
 * por transacción abierta, y este listado devuelve SOLO las notificaciones del usuario
 * autenticado — nunca las de otro destinatario. La identidad proviene exclusivamente de
 * {@link UsuarioPrincipal} autenticado por JWT; el contrato no acepta filtros, paginación ni
 * parámetros de consulta. La ruta queda protegida por {@code anyRequest().authenticated()} de
 * {@code SecurityConfig} (sin requestMatcher nuevo). La lectura es directa del repositorio
 * (sin filtro de {@code leida} ni de {@code tipo}, sin marcar leídas y sin jobs), igual que la
 * familia de controladores de lectura existentes.</p>
 */
@RestController
@RequestMapping("/notificaciones")
public class NotificacionController {

    private final NotificacionRepository notificacionRepository;

    /**
     * Construye el controlador inyectando el repositorio de notificaciones in-app.
     *
     * @param notificacionRepository repositorio JPA de la proyección {@code notificaciones}
     */
    public NotificacionController(NotificacionRepository notificacionRepository) {
        this.notificacionRepository = notificacionRepository;
    }

    /**
     * Endpoint REST {@code GET /notificaciones} para listar las notificaciones del usuario
     * autenticado (Story 7b, spec.md).
     *
     * <p>Devuelve un array JSON (posiblemente vacío) con todas las notificaciones del usuario,
     * ordenadas por fecha de creación descendente (con el ID como desempate). No expone el
     * destinatario porque la identidad se resuelve exclusivamente desde {@code principal.id()};
     * tampoco aplica filtros por {@code leida} ni {@code tipo} — el criterio de la story no los
     * define.</p>
     *
     * @param principal identidad del usuario autenticado mediante JWT
     * @return {@link ResponseEntity} con código HTTP 200 OK y lista de DTOs de sus notificaciones
     */
    @GetMapping
    public ResponseEntity<List<NotificacionResponseDto>> listarPorUsuario(
            @AuthenticationPrincipal UsuarioPrincipal principal
    ) {
        List<NotificacionResponseDto> resultado = notificacionRepository
                .findByUsuarioIdOrderByCreatedAtDescIdDesc(principal.id())
                .stream()
                .map(NotificacionResponseDto::fromEntity)
                .toList();
        return ResponseEntity.ok(resultado);
    }
}