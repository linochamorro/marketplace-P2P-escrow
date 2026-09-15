package com.easymarket.marketplace.controller;

import com.easymarket.marketplace.dto.TransaccionResponseDto;
import com.easymarket.marketplace.exception.TransaccionNoEncontradaException;
import com.easymarket.marketplace.model.Rol;
import com.easymarket.marketplace.model.Transaccion;
import com.easymarket.marketplace.repository.TransaccionRepository;
import com.easymarket.marketplace.security.UsuarioPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Endpoints REST de consulta (lectura) de transacciones (PHA06TSK06; Stories 5, 6a-6d y 7 de
 * spec.md; plan.md, "Lectura de transacciones").
 *
 * <p>Expone {@code GET /transacciones/compras}, {@code GET /transacciones/ventas} y
 * {@code GET /transacciones/{id}} sobre el mismo espacio de rutas que
 * {@link TransaccionEnvioEntregaController} (PATCH {@code /transacciones/{id}/...}): los literales
 * {@code /compras} y {@code /ventas} no colisionan con el template {@code /{id}} porque Spring
 * prioriza los segmentos literales, y los PATCH no colisionan con los GET por diferir en método
 * HTTP. La identidad del actor proviene exclusivamente de {@link UsuarioPrincipal} autenticado por
 * JWT; el contrato no acepta un ID de actor por path, query ni body. La ruta queda protegida por
 * {@code anyRequest().authenticated()} de {@code SecurityConfig} (sin requestMatcher nuevo).</p>
 *
 * <p>Los métodos mapean asociaciones {@code LAZY} ({@code publicacion}, {@code comprador} y
 * {@code publicacion.usuario}), por lo que cada uno abre una transacción de solo lectura con
 * {@code @Transactional(readOnly = true)}: no hay lógica de dominio que encapsular en un servicio
 * (son proyecciones puras sin transiciones ni movimientos de fondos), y la decisión de
 * autorización del detalle es parte del contrato de esta capa API.</p>
 */
@RestController
@RequestMapping("/transacciones")
public class TransaccionConsultaController {

    private final TransaccionRepository transaccionRepository;

    /**
     * Construye el controlador inyectando el repositorio de transacciones.
     *
     * @param transaccionRepository repositorio JPA de la entidad {@link Transaccion}
     */
    public TransaccionConsultaController(TransaccionRepository transaccionRepository) {
        this.transaccionRepository = transaccionRepository;
    }

    /**
     * Endpoint REST {@code GET /transacciones/compras} para listar las compras del usuario
     * autenticado (PHA06TSK06).
     *
     * <p>Devuelve únicamente las transacciones cuyo comprador es el actor — nunca las de otro
     * usuario — ordenadas por {@code fechaReservada} descendente (contrato declarado para la UI de
     * PHA06TSK12). Es una lectura pura: no transiciona estados, no modifica fondos ni escribe en
     * el ledger append-only.</p>
     *
     * @param principal identidad del usuario autenticado mediante JWT
     * @return {@link ResponseEntity} con código HTTP 200 OK y la lista (posiblemente vacía) de
     *         compras del actor
     */
    @GetMapping("/compras")
    @Transactional(readOnly = true)
    public ResponseEntity<List<TransaccionResponseDto>> listarCompras(
            @AuthenticationPrincipal UsuarioPrincipal principal
    ) {
        List<TransaccionResponseDto> resultado = transaccionRepository
                .findByCompradorIdOrderByFechaReservadaDesc(principal.id())
                .stream()
                .map(TransaccionResponseDto::fromEntity)
                .toList();
        return ResponseEntity.ok(resultado);
    }

    /**
     * Endpoint REST {@code GET /transacciones/ventas} para listar las ventas del usuario
     * autenticado (PHA06TSK06).
     *
     * <p>Devuelve únicamente las transacciones cuya publicación pertenece al actor — nunca las de
     * otro vendedor — ordenadas por {@code fechaReservada} descendente (contrato declarado para la
     * UI de PHA06TSK12). Es una lectura pura: no transiciona estados, no modifica fondos ni escribe
     * en el ledger append-only.</p>
     *
     * @param principal identidad del usuario autenticado mediante JWT
     * @return {@link ResponseEntity} con código HTTP 200 OK y la lista (posiblemente vacía) de
     *         ventas del actor
     */
    @GetMapping("/ventas")
    @Transactional(readOnly = true)
    public ResponseEntity<List<TransaccionResponseDto>> listarVentas(
            @AuthenticationPrincipal UsuarioPrincipal principal
    ) {
        List<TransaccionResponseDto> resultado = transaccionRepository
                .findByPublicacionUsuarioIdOrderByFechaReservadaDesc(principal.id())
                .stream()
                .map(TransaccionResponseDto::fromEntity)
                .toList();
        return ResponseEntity.ok(resultado);
    }

    /**
     * Endpoint REST {@code GET /transacciones/{id}} para consultar el detalle de una transacción
     * (PHA06TSK06; plan.md, "Lectura de transacciones").
     *
     * <p>Permite el detalle al comprador, al vendedor (dueño de la publicación) y al
     * {@code ADMIN}. Un tercero autenticado y una transacción inexistente reciben el mismo HTTP 404
     * con {@link TransaccionNoEncontradaException} (decisión de contrato de PHA06TSK06: ocultar la
     * existencia de datos financieros, mismo patrón que {@code PublicacionNoEncontradaException}
     * en PHA06TSK04). La identidad del actor proviene exclusivamente del JWT; el {@code id} solo
     * llega por path.</p>
     *
     * @param transaccionId ID de la transacción a consultar
     * @param principal identidad del usuario autenticado mediante JWT
     * @return {@link ResponseEntity} con código HTTP 200 OK y el DTO de detalle
     * @throws TransaccionNoEncontradaException si la transacción no existe o el actor autenticado
     *                                          no es comprador, vendedor ni {@code ADMIN}
     */
    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<TransaccionResponseDto> obtenerDetalle(
            @PathVariable("id") Long transaccionId,
            @AuthenticationPrincipal UsuarioPrincipal principal
    ) {
        Transaccion transaccion = transaccionRepository.findById(transaccionId)
                .orElseThrow(() -> new TransaccionNoEncontradaException(
                        "Transacción con ID " + transaccionId + " no encontrada"));
        boolean esComprador = transaccion.getComprador().getId().equals(principal.id());
        boolean esVendedor = transaccion.getPublicacion().getUsuario().getId().equals(principal.id());
        boolean esAdmin = principal.rol() == Rol.ADMIN;
        if (!esComprador && !esVendedor && !esAdmin) {
            throw new TransaccionNoEncontradaException(
                    "Transacción con ID " + transaccionId + " no encontrada");
        }
        return ResponseEntity.ok(TransaccionResponseDto.fromEntity(transaccion));
    }
}
