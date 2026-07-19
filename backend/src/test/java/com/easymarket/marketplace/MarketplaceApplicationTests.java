package com.easymarket.marketplace;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

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
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class MarketplaceApplicationTests {

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
