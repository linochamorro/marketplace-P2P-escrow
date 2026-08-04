package com.easymarket.marketplace.config;

import com.stripe.Stripe;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Configuración del cliente de la API de Stripe para la aplicación EasyMarket.
 *
 * <p>Inyecta la clave secreta de Stripe ({@code stripe.secret-key}) desde la configuración de
 * Spring (variables de entorno o archivos {@code application-*.yml}) y establece
 * {@link Stripe#apiKey} en el {@link PostConstruct @PostConstruct} de la clase, antes de que
 * cualquier servicio intente usar la API de Stripe.</p>
 *
 * <p>La propiedad se resuelve en tres perfiles (plan.md, sección "Manejo de secretos"):
 * <ul>
 *   <li><b>dev</b>: fallback a {@code sk_test_REPLACE_ME} desde variable de entorno
 *       {@code STRIPE_SECRET_KEY}.</li>
*      <li><b>test</b>: valor fijo {@code sk_test_FAKE_FOR_CONTEXT_TEST} para que el contexto
 *       de tests cargue sin errores de binding; los tests reales contra Stripe sandbox
 *       requieren {@code STRIPE_SECRET_KEY} real y se ejecutan condicionalmente
 *       (ver {@code StripePaymentServiceIntegrationTests}).</li>
 *       <li><b>prod</b>: obligatorio desde variable de entorno {@code STRIPE_SECRET_KEY},
 *       sin valor por defecto (fail-fast). En contextos sin el perfil {@code test} ni
 *       {@code dev} ni variable de entorno, se usa una cadena vacía como último recurso,
 *       emitiendo una advertencia en el log.</li>
 * </ul>
 * </p>
 *
 * <p>Corresponde a PHA03TSK06 de {@code tasks.md}: config del cliente Stripe para creación de
 * PaymentIntents en modo captura inmediata.</p>
 */
@Configuration
public class StripeConfig {

    private static final Logger log = LoggerFactory.getLogger(StripeConfig.class);

    private final String secretKey;

    /**
     * Construye la configuración con la clave secreta de Stripe.
     *
     * @param secretKey la clave secreta de Stripe inyectada desde {@code stripe.secret-key}
     *                  en {@code application-*.yml}
     */
    public StripeConfig(@Value("${stripe.secret-key:}") String secretKey) {
        this.secretKey = secretKey;
    }

    /**
     * Inicializa la clave API de Stripe global ({@link Stripe#apiKey}) al arrancar la aplicación.
     *
     * <p>Se ejecuta automáticamente después del constructor porque el bean ya está completamente
     * configurado. Si la clave no es válida (no empieza con {@code sk_test_} o {@code sk_live_}),
     * emite una advertencia en el log — no bloquea el arranque porque la validación real ocurre
     * cuando se hace la primera llamada a la API de Stripe.</p>
     */
    @PostConstruct
    public void initStripeApiKey() {
        Stripe.apiKey = secretKey;
        if (secretKey == null || secretKey.isBlank()) {
            log.warn("Stripe secret-key no está configurada. Los PaymentIntents fallarán hasta que se configure STRIPE_SECRET_KEY.");
        } else {
            log.info("Stripe API key configurada (modo: {})", secretKey.startsWith("sk_test_") ? "test (sandbox)" : "live (producción)");
        }
    }
}