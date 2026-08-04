package com.easymarket.marketplace.service;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas unitarias para {@link WebhookVerificationService}.
 *
 * <p>Verifica la verificación de firma de webhook de Stripe (PHA03TSK07 de {@code tasks.md},
 * Constitution principio 4 enmendado, y sección "Verificación de webhooks Stripe" de
 * {@code plan.md}):
 * <ul>
 *   <li>Firma inválida: el servicio rechaza el payload antes de tocar la base de datos,
 *       lanzando {@link SignatureVerificationException}.</li>
 *   <li>Firma válida: el servicio retorna un {@link Event} deserializado correctamente
 *       con los campos esperados.</li>
 * </ul>
 * </p>
 *
 * <p>No requiere {@code @SpringBootTest} — es un test JUnit 5 puro sin contexto de Spring,
 * ya que el servicio no tiene dependencias externas (no toca BD, no usa configuración
 * de Spring). La firma válida se genera manualmente computando el HMAC-SHA256 con el
 * mismo algoritmo que Stripe SDK, evitando mockear el método estático
 * {@code Webhook.constructEvent}.</p>
 */
class WebhookVerificationServiceTests {

    private final WebhookVerificationService service = new WebhookVerificationService();

    /**
     * Verifica que un payload con un header de firma inválido (signature vacía,
     * timestamp incorrecto) lanza {@link SignatureVerificationException}.
     *
     * <p>No se debe usar {@code Exception.class} genérico — se verifica el tipo
     * exacto de la excepción de Stripe para firma inválida.</p>
     */
    @Test
    @DisplayName("Firma inválida debe lanzar SignatureVerificationException")
    void verificarFirma_FirmaInvalida_LanzaSignatureVerificationException() {
        String payload = "{\"id\":\"evt_test\"}";
        String sigHeader = "t=1234567890,v1=firma_invalida";
        String secret = "whsec_test_secret";

        assertThatThrownBy(() -> service.verificarFirma(payload, sigHeader, secret))
                .isInstanceOf(SignatureVerificationException.class);
    }

    /**
     * Verifica que un payload firmado correctamente con HMAC-SHA256 (mismo algoritmo
     * que usa Stripe SDK internamente) retorna un objeto {@link Event} válido con
     * los campos esperados.
     *
     * <p>Se genera manualmente la firma HMAC-SHA256 con el formato de Stripe:
     * {@code t=<timestamp>,v1=<hmac>}. El payload, timestamp y secret son conocidos,
     * por lo que se puede computar la firma esperada sin llamar a Stripe.</p>
     *
     * <p>Esto prueba la verificación real del SDK de Stripe (sin mockear nada),
     * usando el método estático {@code Webhook.constructEvent} que implementa
     * exactamente este algoritmo.</p>
     */
    @Test
    @DisplayName("Firma válida debe retornar Event con tipo e ID correctos")
    void verificarFirma_FirmaValida_RetornaEvent() throws Exception {
        String secret = "whsec_test_secret";
        String payload = "{\"id\":\"evt_test_123\",\"object\":\"event\","
                + "\"type\":\"payment_intent.succeeded\",\"data\":{\"object\":{\"id\":\"pi_test\"}}}";
        // Usar un timestamp dentro de la ventana de tolerancia de 5 minutos del SDK de Stripe
        long timestamp = System.currentTimeMillis() / 1000L;
        String signedPayload = timestamp + "." + payload;
        String expectedSignature = computeHmacSha256(signedPayload, secret);
        String sigHeader = "t=" + timestamp + ",v1=" + expectedSignature;

        Event event = service.verificarFirma(payload, sigHeader, secret);

        assertThat(event).isNotNull();
        assertThat(event.getType()).isEqualTo("payment_intent.succeeded");
        assertThat(event.getId()).isEqualTo("evt_test_123");
    }

    /**
     * Calcula el HMAC-SHA256 de un mensaje usando una clave secreta, en el formato
     * que Stripe espera para la verificación de firma de webhook.
     *
     * <p>El resultado es una cadena hexadecimal en minúsculas, exactamente como
     * Stripe SDK la genera internamente al verificar {@code Webhook.constructEvent}.
     * La clave se interpreta como bytes UTF-8, que es el encoding estándar que
     * Stripe utiliza.</p>
     *
     * @param data el mensaje a firmar (formato: {@code timestamp + "." + payload})
     * @param key  la clave secreta del webhook (prefijo {@code whsec_})
     * @return la firma HMAC-SHA256 en hexadecimal minúsculas (formato Stripe v1)
     * @throws Exception si ocurre un error con el algoritmo HMAC
     */
    private String computeHmacSha256(String data, String key) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(
                key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(secretKeySpec);
        byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : hmacBytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}