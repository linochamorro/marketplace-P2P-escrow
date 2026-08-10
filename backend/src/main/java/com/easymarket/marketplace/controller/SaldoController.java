package com.easymarket.marketplace.controller;

import com.easymarket.marketplace.dto.SaldoResponseDto;
import com.easymarket.marketplace.model.ConsultaSaldoResult;
import com.easymarket.marketplace.security.UsuarioPrincipal;
import com.easymarket.marketplace.service.ConsultaSaldoService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint REST de consulta de saldo disponible del usuario autenticado (Story 12, spec.md).
 *
 * <p>Expone {@code GET /usuarios/me/saldo}: cualquier usuario autenticado (comprador o vendedor,
 * sin rol especial) consulta su {@code saldo_disponible} cacheado junto con el detalle
 * append-only de movimientos que lo componen. La identidad proviene exclusivamente de
 * {@link UsuarioPrincipal} autenticado por JWT; el contrato no acepta path params, query params
 * ni body. La ruta queda protegida por {@code anyRequest().authenticated()} de
 * {@code SecurityConfig} (sin requestMatcher nuevo). El endpoint delega íntegramente la lectura
 * en {@link ConsultaSaldoService} (PHA04TSK11): no recalcula ni reconcilia el saldo desde el
 * ledger, y el detalle no declara orden.</p>
 */
@RestController
@RequestMapping("/usuarios/me/saldo")
public class SaldoController {

    private final ConsultaSaldoService consultaSaldoService;

    /**
     * Construye el controlador inyectando el servicio de dominio de consulta de saldo.
     *
     * @param consultaSaldoService servicio de dominio que lee el saldo cacheado y los movimientos
     */
    public SaldoController(ConsultaSaldoService consultaSaldoService) {
        this.consultaSaldoService = consultaSaldoService;
    }

    /**
     * Endpoint REST {@code GET /usuarios/me/saldo} para consultar el saldo del usuario autenticado
     * (Story 12, spec.md).
     *
     * <p>Devuelve el saldo cacheado en centavos y el detalle de movimientos del usuario cuya
     * identidad resuelve el JWT — nunca de otro usuario. No expone vendedorId/email/rol: la
     * identidad es implícita del principal autenticado (patrón PHA04TSK16). El 404 de
     * {@code UsuarioNoEncontradoException} lo traduce {@code GlobalExceptionHandler} cuando el ID
     * del principal no existe en la base (caso teórico: la identidad proviene de un JWT válido).</p>
     *
     * @param principal identidad del usuario autenticado mediante JWT
     * @return {@link ResponseEntity} con código HTTP 200 OK y el DTO de saldo y movimientos
     */
    @GetMapping
    public ResponseEntity<SaldoResponseDto> consultarSaldo(
            @AuthenticationPrincipal UsuarioPrincipal principal
    ) {
        ConsultaSaldoResult resultado = consultaSaldoService.consultarSaldo(principal.id());
        return ResponseEntity.ok(SaldoResponseDto.fromEntity(resultado));
    }
}