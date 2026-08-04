package com.easymarket.marketplace.controller;

import com.easymarket.marketplace.dto.CompraRequestDto;
import com.easymarket.marketplace.dto.CompraResponseDto;
import com.easymarket.marketplace.exception.IdempotencyKeyDuplicadaException;
import com.easymarket.marketplace.exception.IdempotencyKeyRequeridaException;
import com.easymarket.marketplace.model.Publicacion;
import com.easymarket.marketplace.security.UsuarioPrincipal;
import com.easymarket.marketplace.service.CrearPaymentIntentResult;
import com.easymarket.marketplace.service.IdempotenciaCompraService;
import com.easymarket.marketplace.service.PublicacionService;
import com.easymarket.marketplace.service.StripePaymentService;
import com.easymarket.marketplace.service.ValidacionCompraService;
import com.stripe.exception.StripeException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller REST del flujo de compra (PHA03TSK09, Story 5, spec.md).
 *
 * <p>Implementa el paso 1 de la coreografía de compra de plan.md ("Flujo de compra y reserva
 * de stock (PHA03)"): {@code POST /compras} <b>valida</b> las precondiciones (auto-compra y
 * stock &ge; 1), <b>crea el PaymentIntent</b> en Stripe (captura inmediata, moneda PEN) y
 * <b>registra la {@code Idempotency-Key}</b> para la guardia de doble-submit. Devuelve el
 * {@code client_secret} para que el frontend confirme el pago con Stripe.js / PaymentElement.</p>
 *
 * <p><strong>Alcance estricto (sin scope creep):</strong> este endpoint NO reserva stock ni
 * crea la transacción {@code reservada} — eso ocurre en el webhook {@code payment_intent.succeeded}
 * (PHA03TSK08, ya cerrada). Tampoco valida el {@code estado} de la publicación ni agrega
 * endpoints de consulta (PHA05).</p>
 *
 * <p><strong>Orquestación (en orden):</strong>
 * <ol>
 *   <li>Identidad del comprador: exclusivamente de {@code @AuthenticationPrincipal} (el body
 *       NO acepta {@code compradorId}).</li>
 *   <li>Header {@code Idempotency-Key} obligatorio: ausente o vacío &rarr; 400.</li>
 *   <li>Resolución de la publicación (para conocer el precio en centavos).</li>
 *   <li>Validación de precondiciones: {@link ValidacionCompraService#validarCompra} —
 *       {@code AutoCompraNoPermitidaException} &rarr; 409, {@code StockAgotadoException} &rarr; 422,
 *       {@code PublicacionNoEncontradaException} &rarr; 404.</li>
 *   <li>Creación del PaymentIntent: {@link StripePaymentService#crearPaymentIntent} con el precio
 *       de la publicación en centavos y moneda {@code "pen"}.</li>
 *   <li>Guardia de idempotencia: {@link IdempotenciaCompraService#registrarOReintentar}.</li>
 * </ol>
 * </p>
 *
 * <p><strong>Idempotencia (doble-submit):</strong> el doble envío con la misma key NO responde
 * 409 (decisión confirmada de plan.md / ESTADO_PROYECTO): Stripe ya deduplica por idempotency-key,
 * por lo que el segundo {@code clientSecret} es el del PaymentIntent existente y se devuelve de
 * forma idempotente (HTTP 200 si la key está "en vuelo" vía
 * {@link IdempotencyKeyDuplicadaException}; HTTP 201 si la compra ya se completó y el servicio
 * retorna la transacción existente).</p>
 */
@RestController
@RequestMapping("/compras")
public class CompraController {

    /** Código ISO 4217 de moneda en minúsculas para el marketplace (PEN, soles peruanos) — plan.md. */
    private static final String MONEDA_PEN = "pen";

    private final PublicacionService publicacionService;
    private final ValidacionCompraService validacionCompraService;
    private final StripePaymentService stripePaymentService;
    private final IdempotenciaCompraService idempotenciaCompraService;

    /**
     * Construye el controlador inyectando los servicios de dominio del flujo de compra.
     *
     * @param publicacionService servicio de consulta de publicaciones (para el precio en centavos)
     * @param validacionCompraService servicio de validación de precondiciones de compra
     * @param stripePaymentService servicio de integración con Stripe (creación de PaymentIntents)
     * @param idempotenciaCompraService servicio de guardia de idempotencia (doble-submit)
     */
    public CompraController(PublicacionService publicacionService,
                            ValidacionCompraService validacionCompraService,
                            StripePaymentService stripePaymentService,
                            IdempotenciaCompraService idempotenciaCompraService) {
        this.publicacionService = publicacionService;
        this.validacionCompraService = validacionCompraService;
        this.stripePaymentService = stripePaymentService;
        this.idempotenciaCompraService = idempotenciaCompraService;
    }

    /**
     * Endpoint REST {@code POST /compras}: paso 1 de la coreografía de compra (plan.md).
     *
     * <p>Valida las precondiciones de la Story 5 (auto-compra y stock), crea el PaymentIntent
     * en Stripe con captura inmediata y moneda PEN, registra la {@code Idempotency-Key} y
     * devuelve el {@code clientSecret} para que el frontend complete el pago. La compra NO queda
     * registrada como transacción aquí: la transacción {@code reservada} nace en el webhook
     * {@code payment_intent.succeeded} (PHA03TSK08).</p>
     *
     * @param idempotencyKey header obligatorio de idempotencia generado por el frontend (UUID v4)
     * @param principal      identidad del comprador autenticado mediante JWT (única fuente del
     *                       compradorId; el body no lo acepta)
     * @param requestDto     DTO con el ID de la publicación a comprar
     * @return HTTP 201 Created con el {@code clientSecret} y {@code paymentIntentId} (key nueva o
     *         ya completada); HTTP 200 OK idempotente con el mismo body si la key está en vuelo
     * @throws StripeException si la API de Stripe rechaza o falla la creación del PaymentIntent
     */
    @PostMapping
    public ResponseEntity<CompraResponseDto> crearCompra(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal UsuarioPrincipal principal,
            @Valid @RequestBody CompraRequestDto requestDto
    ) throws StripeException {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IdempotencyKeyRequeridaException();
        }

        Long compradorId = principal.id();
        Long publicacionId = requestDto.publicacionId();

        // Resolver la publicación para conocer su precio (entero en centavos, principio 3)
        Publicacion publicacion = publicacionService.obtenerPublicacionPorId(publicacionId);

        // Validación de precondiciones de negocio (auto-compra, stock >= 1) — PHA03TSK03
        validacionCompraService.validarCompra(compradorId, publicacionId);

        // Creación del PaymentIntent: captura inmediata, moneda PEN, idempotencia delegada a Stripe.
        // Los IDs de negocio viajan en la metadata del PaymentIntent (plan.md 2026-08-03) para que
        // el webhook PHA03TSK10 pueda resolverlos al llegar payment_intent.succeeded.
        CrearPaymentIntentResult intencion = stripePaymentService.crearPaymentIntent(
                publicacion.getPrecio(), MONEDA_PEN, idempotencyKey, compradorId, publicacionId);

        // Guardia de idempotencia / doble-submit — PHA03TSK05
        try {
            idempotenciaCompraService.registrarOReintentar(idempotencyKey, intencion.paymentIntentId());
        } catch (IdempotencyKeyDuplicadaException ex) {
            // Key "en vuelo": la compra se está procesando. Stripe ya deduplicó el PaymentIntent,
            // así que el clientSecret obtenido es el del intent existente → respuesta idempotente.
            return ResponseEntity.ok(new CompraResponseDto(intencion.clientSecret(), intencion.paymentIntentId()));
        }

        // Key nueva (retorna null) o key ya completada (retorna el transaccionId existente):
        // el clientSecret del PaymentIntent es el correcto → 201 Created idempotente.
        CompraResponseDto respuesta = new CompraResponseDto(intencion.clientSecret(), intencion.paymentIntentId());
        return ResponseEntity.status(HttpStatus.CREATED).body(respuesta);
    }
}
