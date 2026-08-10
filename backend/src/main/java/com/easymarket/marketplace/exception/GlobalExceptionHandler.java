package com.easymarket.marketplace.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

/**
 * Manejador global de excepciones REST para la aplicación EasyMarket.
 *
 * <p>Mapea las excepciones de dominio a códigos de respuesta HTTP y cuerpos JSON
 * consistentes conforme a las especificaciones de {@code spec.md}:
 * <ul>
 *   <li>{@link CredencialesInvalidasException} &rarr; HTTP 401 Unauthorized (mensaje genérico).</li>
 *   <li>{@link CuentaBloqueadaException} &rarr; HTTP 429 Too Many Requests.</li>
 *   <li>{@link EmailYaRegistradoException} &rarr; HTTP 409 Conflict.</li>
 *   <li>{@link PasswordInvalidaException} &rarr; HTTP 400 Bad Request.</li>
 * </ul>
 * </p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Maneja fallos de verificación de credenciales retornando un mensaje genérico.
     *
     * @param ex excepción de credenciales inválidas
     * @return {@link ResponseEntity} con código HTTP 401 Unauthorized y cuerpo con mensaje genérico
     */
    @ExceptionHandler(CredencialesInvalidasException.class)
    public ResponseEntity<Map<String, String>> handleCredencialesInvalidas(CredencialesInvalidasException ex) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("mensaje", ex.getMessage()));
    }

    /**
     * Maneja accesos rechazados por rate limiting / bloqueo de cuenta o IP.
     *
     * @param ex excepción de cuenta bloqueada
     * @return {@link ResponseEntity} con código HTTP 429 Too Many Requests
     */
    @ExceptionHandler(CuentaBloqueadaException.class)
    public ResponseEntity<Map<String, String>> handleCuentaBloqueada(CuentaBloqueadaException ex) {
        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("mensaje", ex.getMessage()));
    }

    /**
     * Maneja intentos de registro con correo electrónico ya existente.
     *
     * @param ex excepción de email duplicado
     * @return {@link ResponseEntity} con código HTTP 409 Conflict
     */
    @ExceptionHandler(EmailYaRegistradoException.class)
    public ResponseEntity<Map<String, String>> handleEmailYaRegistrado(EmailYaRegistradoException ex) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(Map.of("mensaje", ex.getMessage()));
    }

    /**
     * Maneja violaciones a la política mínima de contraseña durante el registro.
     *
     * @param ex excepción de contraseña inválida
     * @return {@link ResponseEntity} con código HTTP 400 Bad Request
     */
    @ExceptionHandler(PasswordInvalidaException.class)
    public ResponseEntity<Map<String, String>> handlePasswordInvalida(PasswordInvalidaException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("mensaje", ex.getMessage()));
    }

    /**
     * Maneja intentos de desbloqueo sobre cuentas que no se encuentran en bloqueo permanente.
     *
     * @param ex excepción de cuenta no bloqueada permanentemente
     * @return {@link ResponseEntity} con código HTTP 400 Bad Request
     */
    @ExceptionHandler(CuentaNoBloqueadaPermanentementeException.class)
    public ResponseEntity<Map<String, String>> handleCuentaNoBloqueadaPermanentemente(CuentaNoBloqueadaPermanentementeException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("mensaje", ex.getMessage()));
    }

    /**
     * Maneja la búsqueda de usuarios inexistentes en el sistema.
     *
     * @param ex excepción de usuario no encontrado
     * @return {@link ResponseEntity} con código HTTP 404 Not Found
     */
    @ExceptionHandler(UsuarioNoEncontradoException.class)
    public ResponseEntity<Map<String, String>> handleUsuarioNoEncontrado(UsuarioNoEncontradoException ex) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(Map.of("mensaje", ex.getMessage()));
    }

    /**
     * Maneja la duplicación de nombres de categorías o subcategorías a nivel jerárquico.
     *
     * @param ex excepción de nombre de categoría duplicado
     * @return {@link ResponseEntity} con código HTTP 409 Conflict
     */
    @ExceptionHandler(NombreCategoriaDuplicadoException.class)
    public ResponseEntity<Map<String, String>> handleNombreCategoriaDuplicado(NombreCategoriaDuplicadoException ex) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(Map.of("mensaje", ex.getMessage()));
    }

    /**
     * Maneja intentos de eliminación de categorías o subcategorías con publicaciones asociadas.
     *
     * @param ex excepción de categoría con publicaciones asociadas
     * @return {@link ResponseEntity} con código HTTP 409 Conflict
     */
    @ExceptionHandler(CategoriaConPublicacionesException.class)
    public ResponseEntity<Map<String, String>> handleCategoriaConPublicaciones(CategoriaConPublicacionesException ex) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(Map.of("mensaje", ex.getMessage()));
    }

    /**
     * Maneja intentos de acceso o manipulación de categorías inexistentes.
     *
     * @param ex excepción de categoría no encontrada
     * @return {@link ResponseEntity} con código HTTP 404 Not Found
     */
    @ExceptionHandler(CategoriaNoEncontradaException.class)
    public ResponseEntity<Map<String, String>> handleCategoriaNoEncontrada(CategoriaNoEncontradaException ex) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(Map.of("mensaje", ex.getMessage()));
    }

    /**
     * Maneja especificaciones de precio inválido (precio <= 0) durante la creación o edición de publicaciones.
     *
     * @param ex excepción de precio inválido
     * @return {@link ResponseEntity} con código HTTP 400 Bad Request
     */
    @ExceptionHandler(PrecioInvalidoException.class)
    public ResponseEntity<Map<String, String>> handlePrecioInvalido(PrecioInvalidoException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("mensaje", ex.getMessage()));
    }

    /**
     * Maneja especificaciones de stock inicial inválido (stock < 1) durante la creación de publicaciones.
     *
     * @param ex excepción de stock inválido
     * @return {@link ResponseEntity} con código HTTP 400 Bad Request
     */
    @ExceptionHandler(StockInvalidoException.class)
    public ResponseEntity<Map<String, String>> handleStockInvalido(StockInvalidoException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("mensaje", ex.getMessage()));
    }

    /**
     * Maneja inconsistencias entre la subcategoría indicada y la categoría raíz padre.
     *
     * @param ex excepción de subcategoría no perteneciente a categoría
     * @return {@link ResponseEntity} con código HTTP 400 Bad Request
     */
    @ExceptionHandler(SubcategoriaNoPerteneceACategoriaException.class)
    public ResponseEntity<Map<String, String>> handleSubcategoriaNoPerteneceACategoria(SubcategoriaNoPerteneceACategoriaException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("mensaje", ex.getMessage()));
    }

    /**
     * Maneja transiciones de estado de publicación no permitidas según la máquina de estados.
     *
     * @param ex excepción de transición de estado inválida
     * @return {@link ResponseEntity} con código HTTP 409 Conflict
     */
    @ExceptionHandler(TransicionEstadoInvalidaException.class)
    public ResponseEntity<Map<String, String>> handleTransicionEstadoInvalida(TransicionEstadoInvalidaException ex) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(Map.of("mensaje", ex.getMessage()));
    }

    /**
     * Maneja la omisión del motivo en transiciones de estado de publicación que lo exigen.
     *
     * @param ex excepción de motivo requerido
     * @return {@link ResponseEntity} con código HTTP 400 Bad Request
     */
    @ExceptionHandler(MotivoRequeridoException.class)
    public ResponseEntity<Map<String, String>> handleMotivoRequerido(MotivoRequeridoException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("mensaje", ex.getMessage()));
    }

    /**
     * Maneja intentos de modificación de recursos por parte de un usuario que no es el propietario.
     *
     * @param ex excepción de usuario no propietario
     * @return {@link ResponseEntity} con código HTTP 403 Forbidden
     */
    @ExceptionHandler(NoEsElPropietarioException.class)
    public ResponseEntity<Map<String, String>> handleNoEsElPropietario(NoEsElPropietarioException ex) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(Map.of("mensaje", ex.getMessage()));
    }

    /**
     * Maneja intentos de modificación inmutable de categoría o subcategoría en publicaciones aprobadas.
     *
     * @param ex excepción de categoría inmutable
     * @return {@link ResponseEntity} con código HTTP 400 Bad Request
     */
    @ExceptionHandler(CategoriaPublicacionInmutableException.class)
    public ResponseEntity<Map<String, String>> handleCategoriaPublicacionInmutable(CategoriaPublicacionInmutableException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("mensaje", ex.getMessage()));
    }

    /**
     * Maneja intentos de edición sobre publicaciones que no se encuentran en estado 'APROBADA'.
     *
     * @param ex excepción de estado no editable
     * @return {@link ResponseEntity} con código HTTP 409 Conflict
     */
    @ExceptionHandler(EstadoPublicacionNoEditableException.class)
    public ResponseEntity<Map<String, String>> handleEstadoPublicacionNoEditable(EstadoPublicacionNoEditableException ex) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(Map.of("mensaje", ex.getMessage()));
    }

    /**
     * Maneja la ausencia de parámetros de query obligatorios en peticiones REST.
     *
     * <p>Ejemplo: {@code GET /publicaciones} sin el parámetro {@code estado} requerido.</p>
     *
     * @param ex excepción de parámetro de petición faltante
     * @return {@link ResponseEntity} con código HTTP 400 Bad Request
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, String>> handleMissingServletRequestParameter(MissingServletRequestParameterException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("mensaje", "Parámetro requerido ausente: '" + ex.getParameterName() + "'"));
    }

    /**
     * Maneja la conversión fallida de parámetros de query a tipos específicos, como enums.
     *
     * <p>Ejemplo: {@code GET /publicaciones?estado=INEXISTENTE} cuando {@code estado} debe ser
     * un valor válido del enum {@link com.easymarket.marketplace.model.EstadoPublicacion}.</p>
     *
     * @param ex excepción de tipo de argumento incompatible
     * @return {@link ResponseEntity} con código HTTP 400 Bad Request
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("mensaje", "Valor inválido para el parámetro '" + ex.getName() + "': " + ex.getValue()));
    }

    /**
     * Maneja violaciones de autorización lanzadas explícitamente desde la capa de controlador.
     *
     * <p>Cubre casos como el intento de un usuario sin rol {@code ADMIN} de consultar
     * publicaciones en estado {@code PENDIENTE_REVISION} (Story 2, spec.md).</p>
     *
     * @param ex excepción de acceso denegado
     * @return {@link ResponseEntity} con código HTTP 403 Forbidden
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(Map.of("mensaje", ex.getMessage()));
    }

    /**
     * Maneja intentos de auto-compra: el comprador es el dueño de la publicación (Story 5,
     * spec.md; regla {@code comprador_id != publicacion.usuario_id}).
     *
     * @param ex excepción de auto-compra rechazada
     * @return {@link ResponseEntity} con código HTTP 409 Conflict
     */
    @ExceptionHandler(AutoCompraNoPermitidaException.class)
    public ResponseEntity<Map<String, String>> handleAutoCompraNoPermitida(AutoCompraNoPermitidaException ex) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(Map.of("mensaje", ex.getMessage()));
    }

    /**
     * Maneja intentos de compra de publicaciones sin stock disponible (stock &lt; 1) (Story 5,
     * spec.md; precondición "hay stock disponible ≥ 1" de plan.md).
     *
     * @param ex excepción de stock agotado
     * @return {@link ResponseEntity} con código HTTP 422 Unprocessable Entity
     */
    @ExceptionHandler(StockAgotadoException.class)
    public ResponseEntity<Map<String, String>> handleStockAgotado(StockAgotadoException ex) {
        return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("mensaje", ex.getMessage()));
    }

    /**
     * Maneja las peticiones de compra o manipulación sobre publicaciones inexistentes.
     *
     * @param ex excepción de publicación no encontrada
     * @return {@link ResponseEntity} con código HTTP 404 Not Found
     */
    @ExceptionHandler(PublicacionNoEncontradaException.class)
    public ResponseEntity<Map<String, String>> handlePublicacionNoEncontrada(PublicacionNoEncontradaException ex) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(Map.of("mensaje", ex.getMessage()));
    }

    /**
     * Maneja la ausencia o vacío del header obligatorio {@code Idempotency-Key} en
     * {@code POST /compras} (plan.md, "Idempotencia de compra (doble-submit)").
     *
     * @param ex excepción de header de idempotencia requerido
     * @return {@link ResponseEntity} con código HTTP 400 Bad Request
     */
    @ExceptionHandler(IdempotencyKeyRequeridaException.class)
    public ResponseEntity<Map<String, String>> handleIdempotencyKeyRequerida(IdempotencyKeyRequeridaException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("mensaje", ex.getMessage()));
    }

    /**
     * Maneja operaciones sobre transacciones cuyo identificador no existe.
     *
     * @param ex excepción de transacción inexistente
     * @return {@link ResponseEntity} con código HTTP 404 Not Found
     */
    @ExceptionHandler(TransaccionNoEncontradaException.class)
    public ResponseEntity<Map<String, String>> handleTransaccionNoEncontrada(TransaccionNoEncontradaException ex) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(Map.of("mensaje", ex.getMessage()));
    }

    /**
     * Maneja intentos de transición de envío o entrega por alguien distinto del vendedor dueño.
     *
     * @param ex excepción de actor no autorizado para la transacción
     * @return {@link ResponseEntity} con código HTTP 403 Forbidden
     */
    @ExceptionHandler(ActorNoEsVendedorTransaccionException.class)
    public ResponseEntity<Map<String, String>> handleActorNoEsVendedorTransaccion(
            ActorNoEsVendedorTransaccionException ex) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(Map.of("mensaje", ex.getMessage()));
    }

    /**
     * Maneja intentos de confirmar o reclamar una transacción por un actor distinto de su comprador.
     *
     * @param ex excepción de comprador no autorizado
     * @return {@link ResponseEntity} con código HTTP 403 Forbidden
     */
    @ExceptionHandler(ActorNoEsCompradorTransaccionException.class)
    public ResponseEntity<Map<String, String>> handleActorNoEsCompradorTransaccion(
            ActorNoEsCompradorTransaccionException ex) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(Map.of("mensaje", ex.getMessage()));
    }

    /**
     * Maneja transiciones de una transacción que contradicen su máquina de estados.
     *
     * @param ex excepción de transición de transacción inválida
     * @return {@link ResponseEntity} con código HTTP 409 Conflict
     */
    @ExceptionHandler(TransicionEstadoTransaccionInvalidaException.class)
    public ResponseEntity<Map<String, String>> handleTransicionEstadoTransaccionInvalida(
            TransicionEstadoTransaccionInvalidaException ex) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(Map.of("mensaje", ex.getMessage()));
    }

    /**
     * Maneja confirmaciones explícitas que excedieron la ventana de recepción de 48 horas.
     *
     * @param ex excepción de plazo de confirmación excedido
     * @return {@link ResponseEntity} con código HTTP 409 Conflict
     */
    @ExceptionHandler(PlazoConfirmacionRecepcionExcedidoException.class)
    public ResponseEntity<Map<String, String>> handlePlazoConfirmacionRecepcionExcedido(
            PlazoConfirmacionRecepcionExcedidoException ex) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(Map.of("mensaje", ex.getMessage()));
    }

    /**
     * Maneja reclamos que excedieron la ventana de recepción de 48 horas.
     *
     * @param ex excepción de plazo de reclamo excedido
     * @return {@link ResponseEntity} con código HTTP 409 Conflict
     */
    @ExceptionHandler(PlazoReclamoExcedidoException.class)
    public ResponseEntity<Map<String, String>> handlePlazoReclamoExcedido(PlazoReclamoExcedidoException ex) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(Map.of("mensaje", ex.getMessage()));
    }

    /**
     * Maneja cancelaciones solicitadas sin el motivo obligatorio de Story 7.
     *
     * <p>Traduce la validación de dominio de {@code MotivoCancelacionObligatorioException} al
     * criterio HTTP del task row de PHA04TSK14: "400 sin motivo". Cubre tanto el cuerpo ausente
     * como {@code motivo} {@code null}, vacío o compuesto solo por blancos.</p>
     *
     * @param ex excepción de motivo de cancelación obligatorio
     * @return {@link ResponseEntity} con código HTTP 400 Bad Request
     */
    @ExceptionHandler(MotivoCancelacionObligatorioException.class)
    public ResponseEntity<Map<String, String>> handleMotivoCancelacionObligatorio(
            MotivoCancelacionObligatorioException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("mensaje", ex.getMessage()));
    }

    /**
     * Maneja intentos de cancelación por un actor no autorizado según el estado origen (Story 7).
     *
     * <p>El comprador no puede cancelar un envío (solo el vendedor) y un tercero ajeno a la
     * transacción no puede cancelar una reserva. Traduce la excepción de dominio al código 403
     * convencional de autorización de la API.</p>
     *
     * @param ex excepción de actor no autorizado para cancelar
     * @return {@link ResponseEntity} con código HTTP 403 Forbidden
     */
    @ExceptionHandler(ActorNoAutorizadoParaCancelarTransaccionException.class)
    public ResponseEntity<Map<String, String>> handleActorNoAutorizadoParaCancelarTransaccion(
            ActorNoAutorizadoParaCancelarTransaccionException ex) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(Map.of("mensaje", ex.getMessage()));
    }

    /**
     * Maneja cancelaciones de transacciones sin correlación durable con su PaymentIntent.
     *
     * <p>Traduce la decisión del plan.md (2026-08-07): si una transacción cancelable no tiene una
     * fila {@code idempotency_keys} con {@code payment_intent_id}, la cancelación se rechaza antes
     * de modificar estado, stock, auditoría u outbox. El código 409 Conflict expresa que la
     * operación no puede ejecutarse sobre el estado incompleto de la transacción, sin cambiar
     * nada (consistente con el 409 ya usado para las transiciones de estado rechazadas).</p>
     *
     * @param ex excepción de correlación Stripe ausente
     * @return {@link ResponseEntity} con código HTTP 409 Conflict
     */
    @ExceptionHandler(PaymentIntentTransaccionNoEncontradoException.class)
    public ResponseEntity<Map<String, String>> handlePaymentIntentTransaccionNoEncontrado(
            PaymentIntentTransaccionNoEncontradoException ex) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(Map.of("mensaje", ex.getMessage()));
    }

    /**
     * Maneja resoluciones de disputa sin una de las decisiones binarias de Story 9.
     *
     * <p>Traduce la validación de dominio de {@code DecisionResolucionDisputaInvalidaException} al
     * criterio HTTP del task row de PHA04TSK15. Cubre tanto la decisión ausente o {@code null}
     * (validada por el dominio) como la cadena que no pertenece al enum
     * {@link com.easymarket.marketplace.model.ResolucionDisputa} (rechazada en el controlador).</p>
     *
     * @param ex excepción de decisión de resolución inválida
     * @return {@link ResponseEntity} con código HTTP 400 Bad Request
     */
    @ExceptionHandler(DecisionResolucionDisputaInvalidaException.class)
    public ResponseEntity<Map<String, String>> handleDecisionResolucionDisputaInvalida(
            DecisionResolucionDisputaInvalidaException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("mensaje", ex.getMessage()));
    }

    /**
     * Maneja resoluciones de disputa solicitadas sin el motivo auditable de Story 9.
     *
     * <p>Traduce la validación de dominio de {@code MotivoResolucionDisputaObligatorioException} al
     * criterio HTTP del task row de PHA04TSK15: "400 sin motivo". Cubre tanto el cuerpo ausente
     * como {@code motivo} {@code null}, vacío o compuesto solo por blancos.</p>
     *
     * @param ex excepción de motivo de resolución obligatorio
     * @return {@link ResponseEntity} con código HTTP 400 Bad Request
     */
    @ExceptionHandler(MotivoResolucionDisputaObligatorioException.class)
    public ResponseEntity<Map<String, String>> handleMotivoResolucionDisputaObligatorio(
            MotivoResolucionDisputaObligatorioException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("mensaje", ex.getMessage()));
    }
}
