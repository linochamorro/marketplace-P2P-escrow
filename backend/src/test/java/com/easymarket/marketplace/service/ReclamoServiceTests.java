package com.easymarket.marketplace.service;

import com.easymarket.marketplace.exception.ActorNoEsCompradorTransaccionException;
import com.easymarket.marketplace.exception.PlazoReclamoExcedidoException;
import com.easymarket.marketplace.exception.TransicionEstadoTransaccionInvalidaException;
import com.easymarket.marketplace.model.EstadoTransaccion;
import com.easymarket.marketplace.model.Publicacion;
import com.easymarket.marketplace.model.Transaccion;
import com.easymarket.marketplace.model.TransaccionEvento;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.TransaccionEventoRepository;
import com.easymarket.marketplace.repository.TransaccionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias del servicio de reclamo de las Stories 6d y 8.
 *
 * <p>La ventana de 48 horas comienza en {@code fecha_entregado} y su frontera es inclusiva. Como
 * Story 6d define el motivo como texto libre, sin declararlo obligatorio como hace explícitamente
 * la Story 7 para cancelaciones, {@code null} y la cadena vacía se aceptan y se registran sin
 * transformación. El reclamo solo cambia el estado y crea el evento: no libera ni revierte fondos,
 * no crea movimientos de saldo y no modifica stock.</p>
 */
@ExtendWith(MockitoExtension.class)
class ReclamoServiceTests {

    private static final Long TRANSACCION_ID = 100L;
    private static final Long COMPRADOR_ID = 20L;
    private static final Long VENDEDOR_ID = 10L;
    private static final Long ADMIN_ID = 30L;
    private static final ZonedDateTime AHORA = ZonedDateTime.of(2026, 8, 6, 12, 0, 0, 0, ZoneOffset.UTC);

    @Mock
    private TransaccionRepository transaccionRepository;

    @Mock
    private TransaccionEventoRepository transaccionEventoRepository;

    /**
     * Rechaza un reclamo repetido porque disputa no puede volver a transicionar a disputa.
     */
    @Test
    @DisplayName("Rechaza reclamar fuera de entregado sin persistir escrituras")
    void reclamar_EstadoDisputa_LanzaExcepcionEspecificaSinEscrituras() {
        Transaccion transaccion = transaccionEntregada(AHORA.minusHours(1));
        transaccion.setEstado(EstadoTransaccion.DISPUTA);
        when(transaccionRepository.findByIdForUpdate(TRANSACCION_ID)).thenReturn(Optional.of(transaccion));

        assertThatThrownBy(() -> service().reclamar(TRANSACCION_ID, COMPRADOR_ID, "No recibí el producto"))
            .isInstanceOf(TransicionEstadoTransaccionInvalidaException.class);

        verificarSinEscrituras();
    }

    /**
     * Rechaza al vendedor aunque la transacción esté entregada y dentro del plazo.
     */
    @Test
    @DisplayName("Rechaza reclamar si el actor es el vendedor")
    void reclamar_ActorVendedor_LanzaExcepcionEspecificaSinEscrituras() {
        Transaccion transaccion = transaccionEntregada(AHORA.minusHours(1));
        when(transaccionRepository.findByIdForUpdate(TRANSACCION_ID)).thenReturn(Optional.of(transaccion));

        assertThatThrownBy(() -> service().reclamar(TRANSACCION_ID, VENDEDOR_ID, "Producto defectuoso"))
            .isInstanceOf(ActorNoEsCompradorTransaccionException.class);

        verificarSinEscrituras();
    }

    /**
     * Rechaza al administrador porque el actor debe ser la identidad del comprador, no un rol.
     */
    @Test
    @DisplayName("Rechaza reclamar si el actor es admin y no comprador")
    void reclamar_ActorAdmin_LanzaExcepcionEspecificaSinEscrituras() {
        Transaccion transaccion = transaccionEntregada(AHORA.minusHours(1));
        when(transaccionRepository.findByIdForUpdate(TRANSACCION_ID)).thenReturn(Optional.of(transaccion));

        assertThatThrownBy(() -> service().reclamar(TRANSACCION_ID, ADMIN_ID, "El paquete llegó dañado"))
            .isInstanceOf(ActorNoEsCompradorTransaccionException.class);

        verificarSinEscrituras();
    }

    /**
     * Rechaza un reclamo posterior a la ventana de 48 horas sin crear una transición ni un evento.
     */
    @Test
    @DisplayName("Rechaza reclamar después de 48 horas sin escrituras")
    void reclamar_PlazoExcedido_LanzaExcepcionEspecificaSinEscrituras() {
        Transaccion transaccion = transaccionEntregada(AHORA.minusHours(48).minusNanos(1));
        when(transaccionRepository.findByIdForUpdate(TRANSACCION_ID)).thenReturn(Optional.of(transaccion));

        assertThatThrownBy(() -> service().reclamar(TRANSACCION_ID, COMPRADOR_ID, "Falta un accesorio"))
            .isInstanceOf(PlazoReclamoExcedidoException.class);

        verificarSinEscrituras();
    }

    /**
     * Acepta un motivo nulo como texto libre no obligatorio y lo registra sin modificarlo.
     */
    @Test
    @DisplayName("Acepta motivo nulo porque Story 6d no lo declara obligatorio")
    void reclamar_MotivoNulo_TransicionaYRegistraMotivoNulo() {
        Transaccion transaccion = transaccionEntregada(AHORA.minusHours(48));
        when(transaccionRepository.findByIdForUpdate(TRANSACCION_ID)).thenReturn(Optional.of(transaccion));
        when(transaccionRepository.save(transaccion)).thenReturn(transaccion);

        service().reclamar(TRANSACCION_ID, COMPRADOR_ID, null);

        assertThat(eventoRegistrado().getMotivo()).isNull();
    }

    /**
     * Acepta un motivo vacío como texto libre no obligatorio y lo registra sin modificarlo.
     */
    @Test
    @DisplayName("Acepta motivo vacío porque Story 6d no exige contenido mínimo")
    void reclamar_MotivoVacio_TransicionaYRegistraMotivoVacio() {
        Transaccion transaccion = transaccionEntregada(AHORA.minusHours(1));
        when(transaccionRepository.findByIdForUpdate(TRANSACCION_ID)).thenReturn(Optional.of(transaccion));
        when(transaccionRepository.save(transaccion)).thenReturn(transaccion);

        service().reclamar(TRANSACCION_ID, COMPRADOR_ID, "");

        assertThat(eventoRegistrado().getMotivo()).isEmpty();
    }

    /**
     * Transiciona una entrega reclamada dentro de plazo y audita actor, estados y motivo literal.
     */
    @Test
    @DisplayName("Reclama dentro de plazo, congela fondos y registra el evento append-only")
    void reclamar_EntregadoDentroDePlazo_TransicionaADisputaYRegistraEvento() {
        Transaccion transaccion = transaccionEntregada(AHORA.minusHours(48));
        when(transaccionRepository.findByIdForUpdate(TRANSACCION_ID)).thenReturn(Optional.of(transaccion));
        when(transaccionRepository.save(transaccion)).thenReturn(transaccion);

        Transaccion resultado = service().reclamar(TRANSACCION_ID, COMPRADOR_ID, "Producto distinto al publicado");

        assertThat(resultado.getEstado()).isEqualTo(EstadoTransaccion.DISPUTA);
        TransaccionEvento evento = eventoRegistrado();
        assertThat(evento.getTransaccion()).isSameAs(resultado);
        assertThat(evento.getActor()).isSameAs(resultado.getComprador());
        assertThat(evento.getEstadoOrigen()).isEqualTo(EstadoTransaccion.ENTREGADO);
        assertThat(evento.getEstadoDestino()).isEqualTo(EstadoTransaccion.DISPUTA);
        assertThat(evento.getMotivo()).isEqualTo("Producto distinto al publicado");
    }

    /**
     * Verifica que la transición y su evento append-only comparten un límite transaccional de Spring.
     *
     * @throws NoSuchMethodException si la firma de dominio esperada deja de existir
     */
    @Test
    @DisplayName("Declara una transacción Spring para estado y evento")
    void reclamar_MetodoDeDominio_EstaAnotadoComoTransaccional() throws NoSuchMethodException {
        Method metodo = ReclamoService.class.getMethod("reclamar", Long.class, Long.class, String.class);

        assertThat(metodo.isAnnotationPresent(Transactional.class)).isTrue();
    }

    /**
     * Construye el servicio bajo prueba con un reloj fijo para verificar determinísticamente el plazo.
     *
     * @return servicio configurado con los mocks y el instante {@link #AHORA}
     */
    private ReclamoService service() {
        return new ReclamoService(transaccionRepository, transaccionEventoRepository,
            Clock.fixed(AHORA.toInstant(), ZoneOffset.UTC));
    }

    /**
     * Obtiene el único evento que el servicio debe insertar durante un reclamo válido.
     *
     * @return evento append-only enviado al repositorio
     */
    private TransaccionEvento eventoRegistrado() {
        ArgumentCaptor<TransaccionEvento> captor = ArgumentCaptor.forClass(TransaccionEvento.class);
        verify(transaccionEventoRepository).save(captor.capture());
        return captor.getValue();
    }

    /**
     * Crea una transacción entregada, asociada al comprador y a la publicación del vendedor.
     *
     * @param fechaEntregado instante que inicia la ventana para reclamar
     * @return transacción preparada para los escenarios de reclamo
     */
    private Transaccion transaccionEntregada(ZonedDateTime fechaEntregado) {
        Usuario comprador = usuario(COMPRADOR_ID);
        Usuario vendedor = usuario(VENDEDOR_ID);
        Publicacion publicacion = new Publicacion();
        publicacion.setUsuario(vendedor);

        Transaccion transaccion = new Transaccion();
        transaccion.setId(TRANSACCION_ID);
        transaccion.setComprador(comprador);
        transaccion.setPublicacion(publicacion);
        transaccion.setEstado(EstadoTransaccion.ENTREGADO);
        transaccion.setFechaEntregado(fechaEntregado);
        return transaccion;
    }

    /**
     * Crea un usuario persistente simulado para la prueba.
     *
     * @param id ID persistente a asignar al usuario
     * @return usuario configurado con el ID indicado
     */
    private Usuario usuario(Long id) {
        Usuario usuario = new Usuario();
        usuario.setId(id);
        return usuario;
    }

    /**
     * Verifica que un rechazo no persiste una transición ni un evento append-only.
     */
    private void verificarSinEscrituras() {
        verify(transaccionRepository, never()).save(any());
        verify(transaccionEventoRepository, never()).save(any());
    }
}
