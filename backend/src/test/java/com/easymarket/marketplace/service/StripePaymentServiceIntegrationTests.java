package com.easymarket.marketplace.service;

import com.easymarket.marketplace.service.CrearPaymentIntentResult;
import com.easymarket.marketplace.service.StripePaymentService;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas de integración para {@link StripePaymentService} contra Stripe sandbox (test mode).
 *
 * <p>Estas pruebas requieren una clave real {@code STRIPE_SECRET_KEY} configurada como
 * variable de entorno (prefijo {@code sk_test_}) para ejecutarse. Si no hay clave real
 * disponible, el test se skip automáticamente mediante
 * {@link EnabledIfEnvironmentVariable @EnabledIfEnvironmentVariable}, sin fallar el build.</p>
 *
 * <p>No utiliza Testcontainers ni base de datos — las pruebas son puramente contra la
 * API de Stripe sandbox (plan.md, secciones "Flujo de compra y reserva de stock (PHA03)"
 * y "Justificación: captura inmediata sobre auth-hold").</p>
 *
 * <p>Corresponde a PHA03TSK06 de {@code tasks.md}: test de integración (Stripe test mode)
 * que verifica que un PaymentIntent se crea con el monto correcto en centavos.</p>
 */
@EnabledIfEnvironmentVariable(named = "STRIPE_SECRET_KEY", matches = "sk_test_.+")
class StripePaymentServiceIntegrationTests {

    /**
     * Verifica que un PaymentIntent se crea en Stripe sandbox con el monto correcto en
     * centavos y moneda PEN (soles peruanos), utilizando {@code setConfirm(false)} para
     * que retorne estado {@code requires_payment_method}.
     *
     * <p>El test crea un PaymentIntent a través del servicio, recupera el objeto desde
     * Stripe usando el ID devuelto en el resultado, y verifica:
     * <ul>
     *   <li>{@code clientSecret} no es null ni vacío y sigue el formato {@code pi_..._secret_...}.</li>
     *   <li>{@code amount} coincide exactamente con el monto en centavos solicitado (principio 3:
     *       dinero como enteros).</li>
     *   <li>{@code currency} es "pen" (minúsculas).</li>
     *   <li>{@code status} es {@code requires_payment_method} porque {@code setConfirm(false)}.</li>
     * </ul>
     * </p>
     *
     * @throws StripeException si ocurre un error de comunicación con la API de Stripe
     */
    @Test
    @DisplayName("Debe crear PaymentIntent en Stripe sandbox con monto correcto en centavos")
    void crearPaymentIntent_CreaPaymentIntentConMontoCorrecto() throws StripeException {
        Stripe.apiKey = System.getenv("STRIPE_SECRET_KEY");
        long montoCentavos = 299900L; // S/ 2,999.00
        String moneda = "pen";
        String idempotencyKey = UUID.randomUUID().toString();
        StripePaymentService stripePaymentService = new StripePaymentService();

        CrearPaymentIntentResult resultado = stripePaymentService.crearPaymentIntent(
                montoCentavos, moneda, idempotencyKey, 1L, 2L);

        assertThat(resultado).isNotNull();
        assertThat(resultado.paymentIntentId()).isNotNull().isNotEmpty();
        assertThat(resultado.paymentIntentId()).startsWith("pi_");
        assertThat(resultado.clientSecret()).isNotNull().isNotEmpty();
        assertThat(resultado.clientSecret()).startsWith("pi_").contains("_secret_");

        // Recuperar el PaymentIntent desde Stripe para verificar el monto
        PaymentIntent intent = PaymentIntent.retrieve(resultado.paymentIntentId());
        assertThat(intent).isNotNull();
        assertThat(intent.getAmount()).isEqualTo(montoCentavos);
        assertThat(intent.getCurrency().toLowerCase()).isEqualTo("pen");
        assertThat(intent.getStatus()).isEqualTo("requires_payment_method");
    }
}
