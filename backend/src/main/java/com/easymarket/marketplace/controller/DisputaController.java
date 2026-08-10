package com.easymarket.marketplace.controller;

import com.easymarket.marketplace.dto.ResolverDisputaRequestDto;
import com.easymarket.marketplace.exception.DecisionResolucionDisputaInvalidaException;
import com.easymarket.marketplace.model.ResolucionDisputa;
import com.easymarket.marketplace.security.UsuarioPrincipal;
import com.easymarket.marketplace.service.ResolucionDisputaService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints REST de la resolución administrativa de disputas (Story 9, spec.md).
 *
 * <p>El recurso de la ruta {@code /disputas/{id}/resolver} no tiene entidad propia: una disputa
 * ES una transacción en estado {@code disputa}, por lo que el {@code {id}} de la ruta es el ID de
 * la transacción disputada ({@code transaccionId}) que consume el servicio de dominio. La
 * identidad del admin responsable proviene exclusivamente de {@link UsuarioPrincipal} autenticado
 * por JWT con rol {@code ADMIN}; el contrato HTTP no acepta un ID de admin en el cuerpo. La
 * resolución binaria, la acreditación/reversión de fondos, el stock, la auditoría append-only y la
 * orden durable de reembolso pertenecen a {@link ResolucionDisputaService}, que las ejecuta en su
 * transacción de dominio (PHA04TSK10).</p>
 */
@RestController
@RequestMapping("/disputas")
public class DisputaController {

    private final ResolucionDisputaService resolucionDisputaService;

    /**
     * Construye el controlador con el servicio de dominio de resolución de disputas.
     *
     * @param resolucionDisputaService servicio de dominio de la Story 9 (PHA04TSK10)
     */
    public DisputaController(ResolucionDisputaService resolucionDisputaService) {
        this.resolucionDisputaService = resolucionDisputaService;
    }

    /**
     * Resuelve una disputa a favor del vendedor ({@code completada}) o del comprador
     * ({@code cancelada}) como administrador autenticado.
     *
     * <p>No existe una entidad separada {@code disputa}: el {@code {id}} de la ruta
     * {@code /disputas/{id}/resolver} ES el ID de la transacción en disputa (transaccionId).
     * El admin proviene exclusivamente de {@code principal.id()} del JWT; el cuerpo solo aporta la
     * decisión binaria (texto traducido por {@code ResolucionDisputa.valueOf}) y el motivo
     * auditable. La ausencia del cuerpo o de campos se delega como {@code null} al dominio, que
     * valida obligatoriedad y traduce sus excepciones a 400. El endpoint es estricto para el rol
     * administrador en la cadena de seguridad y aquí en la anotación de método (defensa en
     * profundidad).</p>
     *
     * @param disputaId ID de la transacción en disputa (equivalente a {@code transaccionId})
     * @param requestDto cuerpo con la decisión binaria y el motivo; opcional para permitir que el
     *                   dominio valide la ausencia de ambos con su código 400
     * @param principal identidad autenticada que aporta exclusivamente el ID del admin responsable
     * @return HTTP 200 OK cuando el servicio completa la resolución y todos sus efectos
     * @throws DecisionResolucionDisputaInvalidaException si la cadena de decisión no pertenece al
     *         enum {@link ResolucionDisputa} o si el dominio recibe la decisión ausente
     * @throws com.easymarket.marketplace.exception.MotivoResolucionDisputaObligatorioException si
     *         el motivo no contiene texto
     * @throws com.easymarket.marketplace.exception.TransaccionNoEncontradaException si no existe
     *         la transacción disputada
     * @throws com.easymarket.marketplace.exception.TransicionEstadoTransaccionInvalidaException si
     *         la transacción no está en {@code disputa}
     * @throws com.easymarket.marketplace.exception.UsuarioNoEncontradoException si el actor
     *         administrativo autenticado no existe
     * @throws com.easymarket.marketplace.exception.PaymentIntentTransaccionNoEncontradoException si
     *         la rama compradora carece de correlación Stripe durable
     */
    @PatchMapping("/{id}/resolver")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> resolver(
            @PathVariable("id") Long disputaId,
            @RequestBody(required = false) ResolverDisputaRequestDto requestDto,
            @AuthenticationPrincipal UsuarioPrincipal principal
    ) {
        ResolucionDisputa decision = traducirDecision(requestDto);
        String motivo = requestDto == null ? null : requestDto.motivo();
        resolucionDisputaService.resolver(disputaId, principal.id(), decision, motivo);
        return ResponseEntity.ok().build();
    }

    /**
     * Traduce el texto de decisión del cuerpo al enum de dominio, rechazando cadenas inválidas.
     *
     * <p>Una decisión ausente o {@code null} se devuelve como {@code null} para que el dominio la
     * valide con su mensaje y excepción canónica. Una cadena que no pertenece al enum se rechaza
     * aquí mismo con la misma excepción de dominio, mapeada a 400, antes de invocar el servicio.</p>
     *
     * @param requestDto cuerpo de la petición, o {@code null} cuando el cuerpo está ausente
     * @return decisión binaria del enum, o {@code null} cuando no se indicó decisión
     * @throws DecisionResolucionDisputaInvalidaException si la cadena no es un valor del enum
     */
    private ResolucionDisputa traducirDecision(ResolverDisputaRequestDto requestDto) {
        if (requestDto == null || requestDto.decision() == null) {
            return null;
        }
        try {
            return ResolucionDisputa.valueOf(requestDto.decision());
        } catch (IllegalArgumentException ex) {
            throw new DecisionResolucionDisputaInvalidaException(
                "La decisión de resolución de disputa es inválida: '" + requestDto.decision() + "'");
        }
    }
}