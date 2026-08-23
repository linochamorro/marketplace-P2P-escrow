package com.easymarket.marketplace.controller;

import com.easymarket.marketplace.dto.TableroAdminResponseDto;
import com.easymarket.marketplace.dto.TransaccionResponseDto;
import com.easymarket.marketplace.dto.UsuarioBloqueadoResponseDto;
import com.easymarket.marketplace.model.EstadoPublicacion;
import com.easymarket.marketplace.model.Transaccion;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.MovimientoSaldoRepository;
import com.easymarket.marketplace.repository.PublicacionRepository;
import com.easymarket.marketplace.repository.TransaccionRepository;
import com.easymarket.marketplace.repository.UsuarioRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Endpoints REST de consulta (solo lectura) administrativa (PHA06TSK07; Stories 0c, 9 y 13 de
 * spec.md; plan.md, "Lecturas administrativas" y "Tablero administrativo").
 *
 * <p>Expone {@code GET /admin/disputas}, {@code GET /admin/usuarios/bloqueados} y
 * {@code GET /admin/tablero}. Las tres rutas quedan protegidas por el requestMatcher
 * {@code "/admin/**" -> hasRole("ADMIN")} de {@code SecurityConfig} y, como defensa en
 * profundidad, cada método repite {@code @PreAuthorize("hasRole('ADMIN')")} (mismo patrón que
 * {@code AdminUsuarioController.desbloquearUsuario} y {@code DisputaController.resolver}). La
 * identidad del actor no se usa como filtro de datos: estos endpoints no tienen actor-propietario
 * y {@code principal.id()} no participa en ninguna consulta; el rol lo garantiza la cadena de
 * seguridad y la anotación de método.</p>
 *
 * <p>Es una capa de lectura pura que sigue el patrón de {@code TransaccionConsultaController} y
 * {@code NotificacionController}: el controller mapea directamente desde los repositorios sin
 * servicio read-only intermedio, porque no hay lógica de dominio que encapsular (proyecciones y
 * agregaciones sin transiciones, sin movimientos de fondos y sin escrituras en el ledger). Las
 * agregaciones del tablero usan {@code COALESCE(SUM(...), 0)} y tipos {@code long} para que
 * ningún campo del JSON llegue {@code null} (constitution, principio 3: dinero como enteros en
 * centavos).</p>
 */
@RestController
@RequestMapping("/admin")
public class AdminConsultaController {

    private final TransaccionRepository transaccionRepository;
    private final UsuarioRepository usuarioRepository;
    private final PublicacionRepository publicacionRepository;
    private final MovimientoSaldoRepository movimientoSaldoRepository;

    /**
     * Construye el controlador inyectando los repositorios de lectura.
     *
     * @param transaccionRepository repositorio JPA de transacciones
     * @param usuarioRepository repositorio JPA de usuarios
     * @param publicacionRepository repositorio JPA de publicaciones
     * @param movimientoSaldoRepository repositorio JPA del ledger {@code movimientos_saldo}
     */
    public AdminConsultaController(TransaccionRepository transaccionRepository,
                                   UsuarioRepository usuarioRepository,
                                   PublicacionRepository publicacionRepository,
                                   MovimientoSaldoRepository movimientoSaldoRepository) {
        this.transaccionRepository = transaccionRepository;
        this.usuarioRepository = usuarioRepository;
        this.publicacionRepository = publicacionRepository;
        this.movimientoSaldoRepository = movimientoSaldoRepository;
    }

    /**
     * Endpoint REST {@code GET /admin/disputas} para listar las disputas abiertas
     * (PHA06TSK07; plan.md, "Lecturas administrativas").
     *
     * <p>Una disputa ES una transacción en estado {@code disputa}, por lo que el endpoint
     * reutiliza {@link TransaccionResponseDto} — el mismo DTO de {@code GET /transacciones/*} de
     * PHA06TSK06 — manteniendo el contrato de respuesta consistente en todo el dominio de
     * transacciones. Devuelve todas las transacciones en {@code disputa} ordenadas por
     * {@code fechaReservada} descendente (contrato declarado para la UI de PHA06TSK13, consistente
     * con {@code GET /transacciones/compras}/{@code /ventas}). Es una lectura pura: no transiciona
     * estados, no modifica fondos ni escribe en el ledger append-only. Requiere rol {@code ADMIN}
     * tanto en la cadena de seguridad como en {@code @PreAuthorize}.</p>
     *
     * @return {@link ResponseEntity} con código HTTP 200 OK y la lista (posiblemente vacía) de
     *         transacciones en disputa
     */
    @GetMapping("/disputas")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public ResponseEntity<List<TransaccionResponseDto>> listarDisputas() {
        List<TransaccionResponseDto> resultado = transaccionRepository
                .findByEstadoDisputaOrderByFechaReservadaDesc()
                .stream()
                .map(TransaccionResponseDto::fromEntity)
                .toList();
        return ResponseEntity.ok(resultado);
    }

    /**
     * Endpoint REST {@code GET /admin/usuarios/bloqueados} para listar las cuentas con bloqueo
     * permanente (PHA06TSK07; plan.md, "Lecturas administrativas").
     *
     * <p>Devuelve SOLAMENTE cuentas reales de {@code usuarios} con al menos una combinación
     * {@code login_attempts.intentos >= 12}, deduplicadas por email, con el par mínimo
     * {@code usuarioId}/{@code email} — los datos que {@code POST /admin/usuarios/{id}/desbloquear}
     * y la UI administrativa necesitan. Un email en {@code login_attempts} sin cuenta real no es
     * una cuenta y no aparece. La definición canónica de bloqueo permanente (intentos &gt;= 12) es
     * la misma que usa {@code AdminUsuarioController.desbloquearUsuario} y el umbral del upsert
     * {@code LoginAttemptRepository.registrarFalloAtomic}. Es una lectura pura: no reinicia
     * contadores, no escribe en {@code admin_acciones} ni modifica el ledger. Requiere rol
     * {@code ADMIN} en la cadena de seguridad y en {@code @PreAuthorize}.</p>
     *
     * @return {@link ResponseEntity} con código HTTP 200 OK y la lista (posiblemente vacía) de
     *         cuentas bloqueadas permanentemente
     */
    @GetMapping("/usuarios/bloqueados")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<UsuarioBloqueadoResponseDto>> listarUsuariosBloqueados() {
        List<UsuarioBloqueadoResponseDto> resultado = usuarioRepository
                .findUsuariosConBloqueoPermanente()
                .stream()
                .map(usuario -> new UsuarioBloqueadoResponseDto(usuario.getId(), usuario.getEmail()))
                .toList();
        return ResponseEntity.ok(resultado);
    }

    /**
     * Endpoint REST {@code GET /admin/tablero} con las métricas operativas y financieras reales
     * del marketplace (PHA06TSK07; Story 13 de spec.md; plan.md, "Tablero administrativo").
     *
     * <p>Devuelve seis campos agregados de lectura, todos con valores numéricos que nunca son
     * {@code null}: publicaciones en {@code pendiente_revisión}, disputas abiertas
     * ({@code disputa}), cuentas con bloqueo permanente, {@code volumenEscrowCentavos} (suma de
     * {@code precio_snapshot} de {@code reservada}/{@code enviado}/{@code entregado}/{@code disputa}),
     * {@code fondosLiberadosCentavos} (suma de movimientos positivos del ledger) y
     * {@code transaccionesFinalizadas} ({@code recibido}/{@code recibido_sin_respuesta}/{@code completada}).
     * Todas las agregaciones son de lectura: no se recalcula ni modifica el ledger, no se
     * transicionan estados y no se escriben movimientos. Requiere rol {@code ADMIN} en la cadena
     * de seguridad y en {@code @PreAuthorize}.</p>
     *
     * @return {@link ResponseEntity} con código HTTP 200 OK y el DTO con las métricas del tablero
     */
    @GetMapping("/tablero")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TableroAdminResponseDto> obtenerTablero() {
        TableroAdminResponseDto tablero = new TableroAdminResponseDto(
                publicacionRepository.countByEstado(EstadoPublicacion.PENDIENTE_REVISION),
                transaccionRepository.contarDisputasAbiertas(),
                usuarioRepository.contarUsuariosConBloqueoPermanente(),
                transaccionRepository.sumarVolumenEscrowCentavos(),
                movimientoSaldoRepository.sumarMovimientosPositivos(),
                transaccionRepository.contarTransaccionesFinalizadas()
        );
        return ResponseEntity.ok(tablero);
    }
}
