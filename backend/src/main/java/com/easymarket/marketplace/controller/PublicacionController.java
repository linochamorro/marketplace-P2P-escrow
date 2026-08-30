package com.easymarket.marketplace.controller;

import com.easymarket.marketplace.dto.CorregirPublicacionRequestDto;
import com.easymarket.marketplace.dto.PublicacionRequestDto;
import com.easymarket.marketplace.dto.PublicacionResponseDto;
import com.easymarket.marketplace.model.EstadoPublicacion;
import com.easymarket.marketplace.model.Publicacion;
import com.easymarket.marketplace.model.Rol;
import com.easymarket.marketplace.security.UsuarioPrincipal;
import com.easymarket.marketplace.service.ListadoPublicacionesService;
import com.easymarket.marketplace.service.OrdenListadoPublicaciones;
import com.easymarket.marketplace.service.PublicacionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Controller REST para la gestión de publicaciones de productos en el marketplace (Story 1, spec.md).
 *
 * <p>Permite a cualquier usuario autenticado (vendedor) crear publicaciones de venta.
 * El ID del vendedor se determina de forma segura a través de la identidad del usuario autenticado en el JWT.</p>
 */
@RestController
@RequestMapping("/publicaciones")
public class PublicacionController {

    private final PublicacionService publicacionService;
    private final com.easymarket.marketplace.repository.AdminAccionRepository adminAccionRepository;
    private final ListadoPublicacionesService listadoPublicacionesService;

    /**
     * Construye el controlador inyectando los servicios de publicación y listado, y el repositorio de auditoría de admin.
     *
     * @param publicacionService servicio de dominio para publicaciones
     * @param adminAccionRepository repositorio JPA de acciones administrativas
     * @param listadoPublicacionesService servicio de lectura filtrada de publicaciones aprobadas
     */
    public PublicacionController(PublicacionService publicacionService,
                                  com.easymarket.marketplace.repository.AdminAccionRepository adminAccionRepository,
                                  ListadoPublicacionesService listadoPublicacionesService) {
        this.publicacionService = publicacionService;
        this.adminAccionRepository = adminAccionRepository;
        this.listadoPublicacionesService = listadoPublicacionesService;
    }

    /**
     * Endpoint REST {@code POST /publicaciones} para la creación de una nueva publicación por el usuario autenticado.
     *
     * @param requestDto DTO con los detalles del producto en venta (precio, stock, categorías, descripción, imagenFilename opcional)
     * @param principal identidad del vendedor autenticado mediante JWT
     * @return {@link ResponseEntity} con código HTTP 201 Created y DTO de la publicación creada
     */
    @PostMapping
    public ResponseEntity<PublicacionResponseDto> crearPublicacion(
            @Valid @RequestBody PublicacionRequestDto requestDto,
            @AuthenticationPrincipal UsuarioPrincipal principal
    ) {
        Publicacion creada = publicacionService.crearPublicacion(
                principal.id(),
                requestDto.categoriaId(),
                requestDto.subcategoriaId(),
                requestDto.precio(),
                requestDto.stock(),
                requestDto.descripcion(),
                requestDto.imagenFilename()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(PublicacionResponseDto.fromEntity(creada));
    }

    /**
     * Endpoint REST {@code PATCH /publicaciones/{id}/moderar} para la moderación administrativa de publicaciones (Story 2, spec.md).
     *
     * <p>Protegido por el rol {@code ADMIN}. Traduce la acción enviada ('aprobar', 'solicitar-cambios', 'rechazar')
     * a su correspondiente {@link com.easymarket.marketplace.model.EstadoPublicacion} antes de invocar la máquina de estados.
     * Registra además una marca inmutable de auditoría append-only en la tabla {@code admin_acciones} y, cuando la
     * moderación exige motivo (solicitar-cambios/rechazar), persiste el motivo literal en el histórico append-only
     * {@code publicacion_motivos_historicos} (V17) dentro de la misma transacción del cambio de estado (PHA06TSK05).</p>
     *
     * @param id ID de la publicación a moderar
     * @param requestDto DTO con la acción de moderación y motivo (si aplica)
     * @param principal identidad del administrador autenticado mediante JWT
     * @return {@link ResponseEntity} con código HTTP 200 OK y DTO de la publicación actualizada
     */
    @org.springframework.web.bind.annotation.PatchMapping("/{id}/moderar")
    public ResponseEntity<PublicacionResponseDto> moderarPublicacion(
            @org.springframework.web.bind.annotation.PathVariable("id") Long id,
            @Valid @RequestBody com.easymarket.marketplace.dto.ModeracionPublicacionRequestDto requestDto,
            @AuthenticationPrincipal UsuarioPrincipal principal
    ) {
        com.easymarket.marketplace.model.EstadoPublicacion nuevoEstado = switch (requestDto.accion()) {
            case "aprobar" -> com.easymarket.marketplace.model.EstadoPublicacion.APROBADA;
            case "solicitar-cambios" -> com.easymarket.marketplace.model.EstadoPublicacion.CAMBIOS_SOLICITADOS;
            case "rechazar" -> com.easymarket.marketplace.model.EstadoPublicacion.RECHAZADA;
            default -> throw new IllegalArgumentException("Acción de moderación no reconocida: " + requestDto.accion());
        };

        Publicacion actualizada = publicacionService.cambiarEstadoRegistrandoMotivoHistorico(
                id, nuevoEstado, requestDto.motivo(), principal.id());

        // Auditoría administrativa append-only en tabla admin_acciones (Principio 2 y 7)
        String nombreAccion = "MODERACION_" + requestDto.accion().toUpperCase().replace("-", "_");
        String detalle = "Publicación ID " + id + " moderada a " + nuevoEstado + (requestDto.motivo() != null ? " | Motivo: " + requestDto.motivo() : "");
        adminAccionRepository.save(new com.easymarket.marketplace.model.AdminAccion(
                principal.id(),
                nombreAccion,
                actualizada.getUsuario().getId(),
                detalle
        ));

        return ResponseEntity.ok(PublicacionResponseDto.fromEntity(actualizada));
    }

    /**
     * Endpoint REST {@code PATCH /publicaciones/{id}} para la edición parcial de una publicación por su vendedor propietario (Stories 3 y 10, spec.md).
     *
     * <p>Accesible a cualquier usuario autenticado (autorización por propiedad).
     * Invoca {@link PublicacionService#editarPublicacionDuenio} que garantiza la atomicidad y la regla de ocultamiento automático cuando el stock llega a 0 (Story 10).</p>
     *
     * @param id ID de la publicación a editar
     * @param requestDto DTO con los datos opcionales a modificar (precio, stock, descripción)
     * @param principal identidad del vendedor autenticado mediante JWT
     * @return {@link ResponseEntity} con código HTTP 200 OK y DTO de la publicación actualizada
     */
    @org.springframework.web.bind.annotation.PatchMapping("/{id}")
    public ResponseEntity<PublicacionResponseDto> editarPublicacion(
            @org.springframework.web.bind.annotation.PathVariable("id") Long id,
            @Valid @RequestBody com.easymarket.marketplace.dto.EditarPublicacionRequestDto requestDto,
            @AuthenticationPrincipal UsuarioPrincipal principal
    ) {
        Publicacion actualizada = publicacionService.editarPublicacionDuenio(
                id,
                principal.id(),
                requestDto.precio(),
                requestDto.stock(),
                requestDto.descripcion(),
                requestDto.imagenFilename()
        );
        return ResponseEntity.ok(PublicacionResponseDto.fromEntity(actualizada));
    }

    /**
     * Endpoint REST {@code GET /publicaciones?estado=X} para listar publicaciones filtradas por estado (Story 2, spec.md).
     *
     * <p>El parámetro {@code estado} es obligatorio. Las reglas de acceso son:
     * <ul>
     *   <li>Estado {@code PENDIENTE_REVISION}: exclusivo del rol {@code ADMIN}. Cualquier otro rol recibe HTTP 403.</li>
     *   <li>Resto de estados: accesible a cualquier usuario autenticado.</li>
     * </ul>
     * </p>
     *
     * @param estado   valor del enum {@link EstadoPublicacion} a filtrar; requerido
     * @param principal identidad del usuario autenticado mediante JWT
     * @return {@link ResponseEntity} con código HTTP 200 OK y lista de DTOs de publicaciones en ese estado
     * @throws AccessDeniedException si el estado es {@code PENDIENTE_REVISION} y el usuario no es {@code ADMIN}
     */
    @GetMapping(params = "estado")
    public ResponseEntity<List<PublicacionResponseDto>> listarPorEstado(
            @RequestParam("estado") EstadoPublicacion estado,
            @AuthenticationPrincipal UsuarioPrincipal principal
    ) {
        if (EstadoPublicacion.PENDIENTE_REVISION.equals(estado) && !Rol.ADMIN.equals(principal.rol())) {
            throw new AccessDeniedException("El estado PENDIENTE_REVISION es exclusivo del rol ADMIN");
        }

        List<PublicacionResponseDto> resultado = publicacionService.listarPorEstado(estado)
                .stream()
                .map(PublicacionResponseDto::fromEntity)
                .toList();
        return ResponseEntity.ok(resultado);
    }

    /**
     * Endpoint REST {@code GET /publicaciones} para descubrir publicaciones aprobadas de Story 11.
     *
     * <p>Todos los parámetros son opcionales y se delegan sin transformación semántica al contrato
     * nullable de {@link ListadoPublicacionesService#listar(Long, Long, Long, Long,
     * OrdenListadoPublicaciones)}. La condición {@code !estado} reserva las solicitudes que incluyen
     * {@code estado} para el endpoint histórico de Story 2 y evita ambigüedad entre mappings.</p>
     *
     * @param categoriaId identificador opcional de categoría
     * @param subcategoriaId identificador opcional de subcategoría
     * @param precioMinimo límite inferior inclusivo opcional en centavos enteros
     * @param precioMaximo límite superior inclusivo opcional en centavos enteros
     * @param orden orden explícito opcional del listado
     * @return {@link ResponseEntity} con código HTTP 200 OK y DTOs de publicaciones aprobadas filtradas
     */
    @GetMapping(params = "!estado")
    public ResponseEntity<List<PublicacionResponseDto>> listarPublicaciones(
            @RequestParam(required = false) Long categoriaId,
            @RequestParam(required = false) Long subcategoriaId,
            @RequestParam(required = false) Long precioMinimo,
            @RequestParam(required = false) Long precioMaximo,
            @RequestParam(required = false) OrdenListadoPublicaciones orden
    ) {
        List<PublicacionResponseDto> resultado = listadoPublicacionesService
                .listar(categoriaId, subcategoriaId, precioMinimo, precioMaximo, orden)
                .stream()
                .map(PublicacionResponseDto::fromEntity)
                .toList();
        return ResponseEntity.ok(resultado);
    }

    /**
     * Endpoint REST {@code GET /publicaciones/mias} para listar todas las publicaciones del vendedor autenticado (Stories 1, 3, spec.md).
     *
     * <p>Retorna publicaciones en todos los estados (incluyendo ocultas y rechazadas).
     * Accesible a cualquier usuario autenticado (se filtra utilizando exclusivamente el ID del JWT).</p>
     *
     * @param principal identidad del usuario autenticado mediante JWT
     * @return {@link ResponseEntity} con código HTTP 200 OK y lista de DTOs de sus publicaciones ordenadas por fecha descendente
     */
    @GetMapping("/mias")
    public ResponseEntity<List<PublicacionResponseDto>> listarPorUsuario(
            @AuthenticationPrincipal UsuarioPrincipal principal
    ) {
        List<PublicacionResponseDto> resultado = publicacionService.listarPorUsuario(principal.id())
                .stream()
                .map(PublicacionResponseDto::fromEntity)
                .toList();
        return ResponseEntity.ok(resultado);
    }

    /**
     * Endpoint REST {@code GET /publicaciones/{id}} para el detalle público de una publicación
     * aprobada en el marketplace (PHA06TSK04; Story 11, spec.md).
     *
     * <p>Delega la regla "solo aprobada" en {@link PublicacionService#obtenerPublicacionAprobadaPorId}:
     * la publicación debe existir y estar en estado {@link EstadoPublicacion#APROBADA}; cualquier
     * otro estado o un {@code id} inexistente lanzan {@code PublicacionNoEncontradaException} (ya
     * mapeada a HTTP 404 por {@code GlobalExceptionHandler} — sin modificación), ocultando la
     * existencia al marketplace. La gestión del vendedor continúa usando
     * {@code GET /publicaciones/mias}, que incluye todos sus estados. La ruta convive con el
     * mapping literal {@code /mias} (priorizado por Spring sobre la plantilla {@code /{id}}) y con
     * los mappings por {@code params} de {@code GET /publicaciones}.</p>
     *
     * @param id ID de la publicación a consultar
     * @return {@link ResponseEntity} con código HTTP 200 OK y el DTO {@link PublicacionResponseDto}
     *         de la publicación aprobada
     * @throws com.easymarket.marketplace.exception.PublicacionNoEncontradaException si la
     *         publicación no existe o no está aprobada (traducida a 404 por el manejador global)
     */
    @GetMapping("/{id}")
    public ResponseEntity<PublicacionResponseDto> obtenerPublicacionDetalle(
            @PathVariable("id") Long id
    ) {
        Publicacion publicacion = publicacionService.obtenerPublicacionAprobadaPorId(id);
        return ResponseEntity.ok(PublicacionResponseDto.fromEntity(publicacion));
    }

    /**
     * Endpoint REST {@code PATCH /publicaciones/{id}/corregir} para que el vendedor propietario corrija
     * la clasificación de su publicación rechazada (Story 3, spec.md; PHA06TSK05).
     *
     * <p>Autorización por propiedad, no por rol: cualquier usuario autenticado es admitido y el servicio
     * verifica que {@code principal.id()} sea el vendedor de la publicación (si no, HTTP 403). Acepta
     * únicamente la nueva pareja {@code categoriaId}/{@code subcategoriaId} (dominio cerrado, plan.md,
     * enmendado 2026-08-15); el rechazo de la transición o de la clasificación se traduce en 400/409/404
     * por {@code GlobalExceptionHandler}. La corrección no toca el histórico de motivos (append-only,
     * V17): la moderación previa permanece íntegra en {@code publicacion_motivos_historicos}.</p>
     *
     * @param id ID de la publicación rechazada a corregir
     * @param requestDto DTO con la nueva categoría y subcategoría (ambas obligatorias)
     * @param principal identidad del vendedor autenticado mediante JWT
     * @return {@link ResponseEntity} con código HTTP 200 OK y DTO de la publicación reenviada a revisión
     * @throws com.easymarket.marketplace.exception.PublicacionNoEncontradaException si la publicación no existe (404)
     * @throws org.springframework.security.access.AccessDeniedException si el autenticado no es el dueño (403)
     * @throws com.easymarket.marketplace.exception.TransicionEstadoInvalidaException si la corrección no procede desde el estado actual (409)
     * @throws com.easymarket.marketplace.exception.CategoriaPublicacionInmutableException si la publicación está aprobada o en revisión (400)
     * @throws com.easymarket.marketplace.exception.SubcategoriaNoPerteneceACategoriaException si la subcategoría no pertenece a la categoría (400)
     */
    @PatchMapping("/{id}/corregir")
    public ResponseEntity<PublicacionResponseDto> corregirPublicacion(
            @PathVariable("id") Long id,
            @Valid @RequestBody CorregirPublicacionRequestDto requestDto,
            @AuthenticationPrincipal UsuarioPrincipal principal
    ) {
        Publicacion corregida = publicacionService.corregirPublicacion(
                id,
                principal.id(),
                requestDto.categoriaId(),
                requestDto.subcategoriaId()
        );
        return ResponseEntity.ok(PublicacionResponseDto.fromEntity(corregida));
    }

    /**
     * Endpoint REST {@code DELETE /publicaciones/{id}} para que el vendedor propietario elimine
     * definitivamente su publicación rechazada (Story 3, spec.md; PHA06TSK05).
     *
     * <p>Autorización por propiedad, no por rol. La eliminación es exclusiva del estado
     * {@link EstadoPublicacion#RECHAZADA}: cualquier otro estado lanza
     * {@code PublicacionNoEliminableException}, mapeada por {@code GlobalExceptionHandler} a HTTP 409
     * con el mensaje de la excepción. Como la auditoría es append-only (constitución, principio 2),
     * el log de creación (V16, {@code publicacion_eventos}) y el histórico de motivos de moderación
     * (V17, {@code publicacion_motivos_historicos}) sobreviven a la eliminación con la referencia a la
     * publicación anulada (ON DELETE SET NULL), conservando el registro íntegro del ciclo de vida.</p>
     *
     * @param id ID de la publicación rechazada a eliminar
     * @param principal identidad del vendedor autenticado mediante JWT
     * @return {@link ResponseEntity} con código HTTP 204 No Content
     * @throws com.easymarket.marketplace.exception.PublicacionNoEncontradaException si la publicación no existe (404)
     * @throws org.springframework.security.access.AccessDeniedException si el autenticado no es el dueño (403)
     * @throws com.easymarket.marketplace.exception.PublicacionNoEliminableException si la publicación no está rechazada (409)
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> eliminarPublicacion(
            @PathVariable("id") Long id,
            @AuthenticationPrincipal UsuarioPrincipal principal
    ) {
        publicacionService.eliminarPublicacion(id, principal.id());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
