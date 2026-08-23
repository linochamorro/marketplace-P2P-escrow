package com.easymarket.marketplace;

import com.easymarket.marketplace.repository.AdminAccionRepository;
import com.easymarket.marketplace.repository.IdempotencyKeyRepository;
import com.easymarket.marketplace.repository.LoginAttemptRepository;
import com.easymarket.marketplace.repository.MovimientoSaldoRepository;
import com.easymarket.marketplace.repository.NotificacionRepository;
import com.easymarket.marketplace.repository.ProcessedStripeEventRepository;
import com.easymarket.marketplace.repository.PublicacionEventoRepository;
import com.easymarket.marketplace.repository.StripeRefundOutboxRepository;
import com.easymarket.marketplace.repository.TransaccionEventoRepository;
import com.easymarket.marketplace.repository.UsuarioRepository;
import com.easymarket.marketplace.repository.AvisoEnvioPendienteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Test de carga de contexto (smoke test) para {@link MarketplaceApplication}.
 *
 * <p>Verifica que el contexto de Spring Boot levanta sin errores de wiring,
 * sin necesidad de una base de datos real ni credenciales de Stripe. Esto
 * se logra a través del perfil {@code test} (ver
 * {@code src/test/resources/application-test.yml}), que deshabilita
 * explícitamente la autoconfiguración de DataSource, JPA, Flyway y
 * Spring Security.</p>
 *
 * <p>Este test cubre únicamente el criterio de {@code PHA00TSK01}: que el
 * scaffolding base compile y que el contexto de Spring levante sin
 * conflictos de wiring entre las dependencias declaradas en {@code pom.xml}.</p>
 *
 * <p><strong>Limitaciones intencionales de alcance:</strong></p>
 * <ul>
 *   <li>No valida conectividad con PostgreSQL — responsabilidad de
 *       {@code PHA00TSK03} (docker-compose) y {@code PHA01TSK01}
 *       (migración Flyway).</li>
 *   <li>No valida credenciales de Stripe — responsabilidad de
 *       {@code PHA03TSK06}.</li>
 *   <li>No valida configuración de seguridad JWT — responsabilidad de
 *       {@code PHA01TSK03}.</li>
 * </ul>
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class MarketplaceApplicationTests {

    @MockitoBean
    private UsuarioRepository usuarioRepository;

    @MockitoBean
    private LoginAttemptRepository loginAttemptRepository;

    @MockitoBean
    private AdminAccionRepository adminAccionRepository;

    @MockitoBean
    private com.easymarket.marketplace.repository.CategoriaRepository categoriaRepository;

    @MockitoBean
    private com.easymarket.marketplace.repository.SubcategoriaRepository subcategoriaRepository;

    @MockitoBean
    private com.easymarket.marketplace.repository.PublicacionCountRepository publicacionCountRepository;

    @MockitoBean
    private com.easymarket.marketplace.repository.PublicacionRepository publicacionRepository;

    /** Mock required by the PHA06 publication-creation audit dependency. */
    @MockitoBean
    private PublicacionEventoRepository publicacionEventoRepository;

    /** Mock required by the V17 moderation-reasons ledger dependency of PublicacionService (PHA06TSK05). */
    @MockitoBean
    private com.easymarket.marketplace.repository.PublicacionMotivoHistoricoRepository publicacionMotivoHistoricoRepository;

    @MockitoBean
    private com.easymarket.marketplace.repository.TransaccionRepository transaccionRepository;

    @MockitoBean
    private TransaccionEventoRepository transaccionEventoRepository;

    @MockitoBean
    private MovimientoSaldoRepository movimientoSaldoRepository;

    @MockitoBean
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @MockitoBean
    private ProcessedStripeEventRepository processedStripeEventRepository;

    @MockitoBean
    private StripeRefundOutboxRepository stripeRefundOutboxRepository;

    /** Mock required by the pending-shipment notification job added in PHA04TSK08. */
    @MockitoBean
    private NotificacionRepository notificacionRepository;

    /** Mock required by the V13 idempotency marker dependency of the PHA04TSK08 job. */
    @MockitoBean
    private AvisoEnvioPendienteRepository avisoEnvioPendienteRepository;

    /**
     * Verifica que el contexto de aplicación arranca correctamente.
     *
     * <p>Si este test falla, indica un error de configuración o de wiring
     * de beans que debe resolverse antes de continuar con cualquier tarea
     * de {@code PHA01} en adelante.</p>
     */
    @Test
    void contextLoads() {
        // El contexto se levanta en el setup de la clase — si llega aquí, pasó.
    }
}
