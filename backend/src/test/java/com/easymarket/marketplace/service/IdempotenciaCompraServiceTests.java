package com.easymarket.marketplace.service;

import com.easymarket.marketplace.exception.IdempotencyKeyDuplicadaException;
import com.easymarket.marketplace.model.IdempotencyKey;
import com.easymarket.marketplace.repository.IdempotencyKeyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias para {@link IdempotenciaCompraService}.
 *
 * <p>Verifica el mecanismo de idempotencia de compra (doble-submit) de la Story 5 de
 * {@code spec.md} y de la sección "Idempotencia de compra (doble-submit)" de {@code plan.md}
 * (tarea PHA03TSK05 de {@code tasks.md}):
 * <ul>
 *   <li>Clave nueva: la operación puede continuar; se persiste la key con
 *       {@code transaccionId = null} (fase "pago en vuelo", plan.md).</li>
 *   <li>Clave existente sin {@code transaccionId}: la operación ya está en progreso; se lanza
 *       {@link IdempotencyKeyDuplicadaException} para evitar duplicar el PaymentIntent.</li>
 *   <li>Clave existente con {@code transaccionId} poblado: la compra ya se completó; se retorna
 *       el ID de la transacción existente para idempotencia exitosa.</li>
 * </ul>
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class IdempotenciaCompraServiceTests {

    @Mock
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @InjectMocks
    private IdempotenciaCompraService idempotenciaCompraService;

    /**
     * Verifica que una clave nueva (no existente en la tabla) retorna {@code null},
     * indicando que el flujo de compra puede continuar y el servicio persiste la key
     * con el {@code paymentIntentId} proporcionado y {@code transaccionId = null}
     * (cláusula "Si la key no existe: la inserta con paymentIntentId proporcionado y
     * transaccionId = null. Retorna null para indicar 'key nueva, puede continuar'"
     * de PHA03TSK05).
     */
    @Test
    @DisplayName("Clave nueva debe retornar null e insertar la key con transaccionId null")
    void registrarOReintentar_ClaveNueva_RetornaNull() {
        String key = "550e8400-e29b-41d4-a716-446655440000";
        String paymentIntentId = "pi_test_123456";

        when(idempotencyKeyRepository.findById(key)).thenReturn(Optional.empty());
        // Simula el save: retorna la entidad con los valores asignados
        when(idempotencyKeyRepository.save(any(IdempotencyKey.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        Long resultado = idempotenciaCompraService.registrarOReintentar(key, paymentIntentId);

        assertThat(resultado).isNull();

        verify(idempotencyKeyRepository).findById(key);
        verify(idempotencyKeyRepository).save(any(IdempotencyKey.class));
    }

    /**
     * Verifica que una misma clave enviada dos veces, donde la primera inserción tuvo
     * éxito (key nueva → null) y la segunda encuentra la key existente sin
     * {@code transaccionId} poblado, lanza {@link IdempotencyKeyDuplicadaException}
     * (cláusula "Si tiene transaccion_id null (solo payment_intent_id): la operación
     * ya está en progreso; lanza IdempotencyKeyDuplicadaException" de PHA03TSK05).
     */
    @Test
    @DisplayName("Misma clave dos veces sin transaccionId debe lanzar IdempotencyKeyDuplicadaException")
    void registrarOReintentar_MismaClaveSinTransaccion_LanzaIdempotencyKeyDuplicadaException() {
        String key = "550e8400-e29b-41d4-a716-446655440001";
        String paymentIntentId = "pi_test_789012";

        // Primera llamada: key no existe
        when(idempotencyKeyRepository.findById(key)).thenReturn(Optional.empty());
        when(idempotencyKeyRepository.save(any(IdempotencyKey.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        Long primeraLlamada = idempotenciaCompraService.registrarOReintentar(key, paymentIntentId);
        assertThat(primeraLlamada).isNull();

        // Segunda llamada: key ya existe, sin transaccion_id (transaccionId = null)
        IdempotencyKey keyExistente = new IdempotencyKey(key, paymentIntentId, ZonedDateTime.now());
        when(idempotencyKeyRepository.findById(key)).thenReturn(Optional.of(keyExistente));

        assertThatThrownBy(() -> idempotenciaCompraService.registrarOReintentar(key, paymentIntentId))
            .isInstanceOf(IdempotencyKeyDuplicadaException.class)
            .hasMessageContaining(key);

        // Verificar que save se llamó exactamente una vez (solo en la primera llamada)
        verify(idempotencyKeyRepository).save(any(IdempotencyKey.class));
    }

    /**
     * Verifica que cuando la clave ya existe y tiene {@code transaccionId} poblado
     * (la compra ya se completó), el servicio retorna ese {@code transaccionId}
     * sin lanzar excepción (cláusula "Si tiene transaccion_id poblado: retorna el
     * transaccion_id existente" de PHA03TSK05).
     */
    @Test
    @DisplayName("Clave existente con transaccionId poblado debe retornar el transaccionId sin lanzar excepción")
    void registrarOReintentar_ClaveExistenteConTransaccion_RetornaTransaccionId() {
        String key = "550e8400-e29b-41d4-a716-446655440002";
        String paymentIntentId = "pi_test_345678";
        Long transaccionIdEsperado = 42L;

        IdempotencyKey keyExistente = new IdempotencyKey(key, paymentIntentId, ZonedDateTime.now());
        keyExistente.setTransaccionId(transaccionIdEsperado);
        when(idempotencyKeyRepository.findById(key)).thenReturn(Optional.of(keyExistente));

        Long resultado = idempotenciaCompraService.registrarOReintentar(key, paymentIntentId);

        assertThat(resultado).isEqualTo(transaccionIdEsperado);

        // Verificar que NO se llama save cuando la key ya existe
        verify(idempotencyKeyRepository).findById(key);
        verify(idempotencyKeyRepository, never()).save(any(IdempotencyKey.class));
    }
}