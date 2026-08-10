package com.easymarket.marketplace.controller;

import com.easymarket.marketplace.dto.CancelacionRequestDto;
import com.easymarket.marketplace.dto.PruebaEntregaRequestDto;
import com.easymarket.marketplace.dto.ReclamoRequestDto;
import com.easymarket.marketplace.security.UsuarioPrincipal;
import com.easymarket.marketplace.service.CancelacionTransaccionService;
import com.easymarket.marketplace.service.ConfirmacionRecepcionService;
import com.easymarket.marketplace.service.ReclamoService;
import com.easymarket.marketplace.service.TransicionEnvioEntregaService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints REST para las transiciones de envío, entrega, recepción, reclamo y cancelación
 * (Stories 6a a 6d y 7).
 *
 * <p>La identidad del actor proviene exclusivamente de {@link UsuarioPrincipal} autenticado por
 * JWT; el contrato HTTP no acepta un ID de actor. Las reglas de propiedad, de estados y la
 * auditoría append-only pertenecen a los servicios de dominio ({@link TransicionEnvioEntregaService},
 * {@link ConfirmacionRecepcionService}, {@link ReclamoService} y
 * {@link CancelacionTransaccionService}), que las ejecutan en su transacción de dominio.</p>
 */
@RestController
@RequestMapping("/transacciones")
public class TransaccionEnvioEntregaController {

    private final TransicionEnvioEntregaService transicionEnvioEntregaService;
    private final ConfirmacionRecepcionService confirmacionRecepcionService;
    private final ReclamoService reclamoService;
    private final CancelacionTransaccionService cancelacionTransaccionService;

    /**
     * Construye el controlador con los servicios que ejecutan las transiciones de las Stories 6a a 6d y 7.
     *
     * @param transicionEnvioEntregaService servicio de dominio de las Stories 6a y 6b
     * @param confirmacionRecepcionService servicio de dominio de la Story 6c
     * @param reclamoService servicio de dominio de la Story 6d
     * @param cancelacionTransaccionService servicio de dominio de la Story 7
     */
    public TransaccionEnvioEntregaController(TransicionEnvioEntregaService transicionEnvioEntregaService,
                                              ConfirmacionRecepcionService confirmacionRecepcionService,
                                              ReclamoService reclamoService,
                                              CancelacionTransaccionService cancelacionTransaccionService) {
        this.transicionEnvioEntregaService = transicionEnvioEntregaService;
        this.confirmacionRecepcionService = confirmacionRecepcionService;
        this.reclamoService = reclamoService;
        this.cancelacionTransaccionService = cancelacionTransaccionService;
    }

    /**
     * Marca una transacción {@code reservada} como {@code enviado} por su vendedor dueño.
     *
     * @param transaccionId ID de la transacción que se envía
     * @param principal identidad autenticada que aporta exclusivamente el ID del actor
     * @return HTTP 200 OK cuando el servicio completa la transición y su evento append-only
     * @throws com.easymarket.marketplace.exception.TransaccionNoEncontradaException si no existe
     *         la transacción
     * @throws com.easymarket.marketplace.exception.ActorNoEsVendedorTransaccionException si el
     *         actor autenticado no es dueño de la publicación
     * @throws com.easymarket.marketplace.exception.TransicionEstadoTransaccionInvalidaException si
     *         la transacción no está reservada
     */
    @PatchMapping("/{id}/enviar")
    public ResponseEntity<Void> marcarEnviado(
            @PathVariable("id") Long transaccionId,
            @AuthenticationPrincipal UsuarioPrincipal principal
    ) {
        transicionEnvioEntregaService.marcarEnviado(transaccionId, principal.id());
        return ResponseEntity.ok().build();
    }

    /**
     * Marca una transacción {@code enviado} como {@code entregado} por su vendedor dueño.
     *
     * <p>El cuerpo es opcional según el contrato de plan.md. Cuando no llega body o el campo es
     * {@code null}, delega {@code null} al servicio para registrar una entrega sin prueba textual.</p>
     *
     * @param transaccionId ID de la transacción que se entrega
     * @param requestDto cuerpo opcional con la descripción de prueba de entrega
     * @param principal identidad autenticada que aporta exclusivamente el ID del actor
     * @return HTTP 200 OK cuando el servicio completa la transición y su evento append-only
     * @throws com.easymarket.marketplace.exception.TransaccionNoEncontradaException si no existe
     *         la transacción
     * @throws com.easymarket.marketplace.exception.ActorNoEsVendedorTransaccionException si el
     *         actor autenticado no es dueño de la publicación
     * @throws com.easymarket.marketplace.exception.TransicionEstadoTransaccionInvalidaException si
     *         la transacción no está enviada
     */
    @PatchMapping("/{id}/entregar")
    public ResponseEntity<Void> marcarEntregado(
            @PathVariable("id") Long transaccionId,
            @RequestBody(required = false) PruebaEntregaRequestDto requestDto,
            @AuthenticationPrincipal UsuarioPrincipal principal
    ) {
        String descripcionPruebaEntrega = requestDto == null ? null : requestDto.descripcionPruebaEntrega();
        transicionEnvioEntregaService.marcarEntregado(transaccionId, principal.id(), descripcionPruebaEntrega);
        return ResponseEntity.ok().build();
    }

    /**
     * Confirma la recepción de una transacción {@code entregado} por su comprador autenticado.
     *
     * <p>Este endpoint no admite cuerpo: el actor proviene exclusivamente del JWT y el servicio
     * ejecuta atómicamente la liberación de fondos, el movimiento append-only y el evento de
     * transición.</p>
     *
     * @param transaccionId ID de la transacción cuya recepción se confirma
     * @param principal identidad autenticada que aporta exclusivamente el ID del comprador
     * @return HTTP 200 OK cuando el servicio completa la confirmación
     * @throws com.easymarket.marketplace.exception.TransaccionNoEncontradaException si no existe
     *         la transacción
     * @throws com.easymarket.marketplace.exception.ActorNoEsCompradorTransaccionException si el
     *         actor autenticado no es su comprador
     * @throws com.easymarket.marketplace.exception.TransicionEstadoTransaccionInvalidaException si
     *         la transacción no está entregada
     * @throws com.easymarket.marketplace.exception.PlazoConfirmacionRecepcionExcedidoException si
     *         no se puede confirmar dentro de la ventana de 48 horas
     */
    @PatchMapping("/{id}/confirmar")
    public ResponseEntity<Void> confirmarRecepcion(
            @PathVariable("id") Long transaccionId,
            @AuthenticationPrincipal UsuarioPrincipal principal
    ) {
        confirmacionRecepcionService.confirmarRecepcion(transaccionId, principal.id());
        return ResponseEntity.ok().build();
    }

    /**
     * Abre un reclamo de una transacción {@code entregado} por su comprador autenticado.
     *
     * <p>El cuerpo es opcional. Su ausencia y {@code motivo: null} se delegan como {@code null},
     * pues Story 6d define el motivo como texto libre no obligatorio. El actor proviene
     * exclusivamente del JWT; el servicio deja los fondos y stock sin cambios.</p>
     *
     * @param transaccionId ID de la transacción que se reclama
     * @param requestDto cuerpo opcional con el motivo libre del reclamo
     * @param principal identidad autenticada que aporta exclusivamente el ID del comprador
     * @return HTTP 200 OK cuando el servicio completa la transición a disputa
     * @throws com.easymarket.marketplace.exception.TransaccionNoEncontradaException si no existe
     *         la transacción
     * @throws com.easymarket.marketplace.exception.ActorNoEsCompradorTransaccionException si el
     *         actor autenticado no es su comprador
     * @throws com.easymarket.marketplace.exception.TransicionEstadoTransaccionInvalidaException si
     *         la transacción no está entregada
     * @throws com.easymarket.marketplace.exception.PlazoReclamoExcedidoException si no se puede
     *         reclamar dentro de la ventana de 48 horas
     */
    @PatchMapping("/{id}/reclamar")
    public ResponseEntity<Void> reclamar(
            @PathVariable("id") Long transaccionId,
            @RequestBody(required = false) ReclamoRequestDto requestDto,
            @AuthenticationPrincipal UsuarioPrincipal principal
    ) {
        String motivo = requestDto == null ? null : requestDto.motivo();
        reclamoService.reclamar(transaccionId, principal.id(), motivo);
        return ResponseEntity.ok().build();
    }

    /**
     * Cancela una transacción {@code reservada} o {@code enviado} por el actor autorizado según el
     * estado origen (Story 7, spec.md).
     *
     * <p>El cuerpo con el motivo es obligatorio. Su ausencia y un valor {@code null} se delegan
     * como {@code null} al servicio de dominio, que rechaza con
     * {@code MotivoCancelacionObligatorioException} (HTTP 400) antes de escribir: la
     * obligatoriedad pertenece al dominio, no a este DTO. El actor proviene exclusivamente del
     * JWT; el servicio ejecuta atómicamente el cambio a {@code cancelada}, la restauración del
     * stock, el evento append-only y la orden durable de reembolso (plan.md, "Reembolsos Stripe
     * por cancelación").</p>
     *
     * @param transaccionId ID de la transacción que se cancela
     * @param requestDto cuerpo obligatorio con el motivo de la cancelación
     * @param principal identidad autenticada que aporta exclusivamente el ID del actor
     * @return HTTP 200 OK cuando el servicio completa la cancelación y todos sus efectos
     * @throws com.easymarket.marketplace.exception.MotivoCancelacionObligatorioException si el
     *         motivo no contiene texto
     * @throws com.easymarket.marketplace.exception.TransaccionNoEncontradaException si no existe
     *         la transacción
     * @throws com.easymarket.marketplace.exception.TransicionEstadoTransaccionInvalidaException si
     *         la transacción no está reservada ni enviada
     * @throws com.easymarket.marketplace.exception.ActorNoAutorizadoParaCancelarTransaccionException
     *         si el actor no puede cancelar la transacción en su estado origen
     * @throws com.easymarket.marketplace.exception.PaymentIntentTransaccionNoEncontradoException si
     *         falta la correlación Stripe durable antes de modificar cualquier efecto
     */
    @PatchMapping("/{id}/cancelar")
    public ResponseEntity<Void> cancelar(
            @PathVariable("id") Long transaccionId,
            @RequestBody(required = false) CancelacionRequestDto requestDto,
            @AuthenticationPrincipal UsuarioPrincipal principal
    ) {
        String motivo = requestDto == null ? null : requestDto.motivo();
        cancelacionTransaccionService.cancelar(transaccionId, principal.id(), motivo);
        return ResponseEntity.ok().build();
    }
}
