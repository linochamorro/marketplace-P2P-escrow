package com.easymarket.marketplace.service;

import com.easymarket.marketplace.model.StripeRefundOutbox;
import com.easymarket.marketplace.repository.StripeRefundOutboxRepository;
import com.stripe.exception.ApiException;
import com.stripe.exception.StripeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the durable Stripe-refund outbox processor.
 */
@ExtendWith(MockitoExtension.class)
class StripeRefundOutboxProcessorTests {

    @Mock private StripeRefundOutboxRepository stripeRefundOutboxRepository;
    @Mock private StripeRefundOutboxSelectionService stripeRefundOutboxSelectionService;
    @Mock private StripeRefundGateway stripeRefundGateway;

    /**
     * Verifies that an accepted Stripe refund makes the durable order requested.
     *
     * @throws StripeException declared because the verified {@link StripeRefundGateway} contract can throw it
     */
    @Test
    @DisplayName("Accepted Stripe refund marks the outbox order SOLICITADO")
    void procesarPendientes_StripeAcepta_MarcaSolicitadoYRegistraIntento() throws StripeException {
        StripeRefundOutbox orden = ordenPendiente();
        when(stripeRefundOutboxSelectionService.seleccionarPendientes()).thenReturn(List.of(orden));

        processor().procesarPendientes();

        verify(stripeRefundGateway).crearRefund("pi_123", "refund:77");
        assertThat(orden.getEstado()).isEqualTo("SOLICITADO");
        assertThat(orden.getIntentos()).isEqualTo(1);
        assertThat(orden.getUltimoError()).isNull();
        assertThat(orden.getUpdatedAt()).isAfterOrEqualTo(orden.getCreatedAt());
        verify(stripeRefundOutboxRepository).save(orden);
    }

    /**
     * Verifies that a Stripe failure keeps the exact order eligible for a later retry.
     *
     * @throws StripeException declared because the stubbed {@link StripeRefundGateway} contract can throw it
     */
    @Test
    @DisplayName("Stripe failure keeps the outbox order PENDIENTE and records the attempt")
    void procesarPendientes_StripeFalla_ConservaPendienteReintentable() throws StripeException {
        StripeRefundOutbox orden = ordenPendiente();
        when(stripeRefundOutboxSelectionService.seleccionarPendientes()).thenReturn(List.of(orden));
        when(stripeRefundGateway.crearRefund("pi_123", "refund:77"))
            .thenThrow(new ApiException("Stripe unavailable", null, null, 500, null));

        processor().procesarPendientes();

        assertThat(orden.getEstado()).isEqualTo("PENDIENTE");
        assertThat(orden.getIntentos()).isEqualTo(1);
        assertThat(orden.getUltimoError()).isEqualTo("Stripe unavailable");
        assertThat(orden.getUpdatedAt()).isAfterOrEqualTo(orden.getCreatedAt());
        verify(stripeRefundOutboxRepository).save(orden);
    }

    /**
     * Creates the processor under test with mocked collaborators.
     *
     * @return processor configured for this test fixture
     */
    private StripeRefundOutboxProcessor processor() {
        return new StripeRefundOutboxProcessor(stripeRefundOutboxSelectionService, stripeRefundOutboxRepository,
            stripeRefundGateway);
    }

    /**
     * Creates a pending durable order with a stable Stripe idempotency key.
     *
     * @return pending refund order for the fixture
     */
    private StripeRefundOutbox ordenPendiente() {
        return new StripeRefundOutbox(null, "pi_123", "refund:77", ZonedDateTime.now().minusMinutes(1));
    }
}
