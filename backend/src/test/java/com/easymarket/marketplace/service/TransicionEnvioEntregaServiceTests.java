package com.easymarket.marketplace.service;

import com.easymarket.marketplace.exception.ActorNoEsVendedorTransaccionException;
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
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
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
 * Pruebas unitarias de {@link TransicionEnvioEntregaService} para las Stories 6a y 6b de
 * {@code spec.md}.
 *
 * <p>Verifica que la máquina de estados solo permite {@code reservada -> enviado -> entregado},
 * que únicamente el dueño de la publicación puede ejecutar las transiciones y que cada transición
 * persiste su evento de auditoría append-only. También verifica que la prueba de entrega opcional
 * se persiste exclusivamente en la transacción al marcar entregado, sin ocupar el motivo del
 * evento. La prueba no cubre confirmación, saldo, reclamos ni API, porque pertenecen a tareas
 * posteriores de {@code tasks.md}.</p>
 */
@ExtendWith(MockitoExtension.class)
class TransicionEnvioEntregaServiceTests {

    @Mock
    private TransaccionRepository transaccionRepository;

    @Mock
    private TransaccionEventoRepository transaccionEventoRepository;

    @InjectMocks
    private TransicionEnvioEntregaService transicionEnvioEntregaService;

    /**
     * Verifica que no se puede marcar como enviado una transacción que ya está en estado
     * {@link EstadoTransaccion#ENTREGADO}, pues la única transición habilitada hacia enviado
     * procede desde {@link EstadoTransaccion#RESERVADA}.
     */
    @Test
    @DisplayName("Rechaza marcar enviado cuando la transacción no está reservada")
    void marcarEnviado_EstadoFueraDeOrden_LanzaExcepcionEspecifica() {
        Usuario vendedor = vendedor(10L);
        Transaccion transaccion = transaccion(vendedor, EstadoTransaccion.ENTREGADO);
        when(transaccionRepository.findById(100L)).thenReturn(Optional.of(transaccion));

        assertThatThrownBy(() -> transicionEnvioEntregaService.marcarEnviado(100L, 10L))
            .isInstanceOf(TransicionEstadoTransaccionInvalidaException.class);

        verify(transaccionRepository, never()).save(any());
        verify(transaccionEventoRepository, never()).save(any());
    }

    /**
     * Verifica que un usuario distinto del dueño de la publicación no puede marcar la transacción
     * como enviada, aun cuando esta se encuentre en estado {@link EstadoTransaccion#RESERVADA}.
     */
    @Test
    @DisplayName("Rechaza marcar enviado si el actor no es el vendedor dueño")
    void marcarEnviado_ActorNoEsVendedor_LanzaExcepcionEspecifica() {
        Usuario vendedor = vendedor(10L);
        Transaccion transaccion = transaccion(vendedor, EstadoTransaccion.RESERVADA);
        when(transaccionRepository.findById(100L)).thenReturn(Optional.of(transaccion));

        assertThatThrownBy(() -> transicionEnvioEntregaService.marcarEnviado(100L, 20L))
            .isInstanceOf(ActorNoEsVendedorTransaccionException.class);

        verify(transaccionRepository, never()).save(any());
        verify(transaccionEventoRepository, never()).save(any());
    }

    /**
     * Verifica que el vendedor dueño puede avanzar de reservada a enviado, registrando la fecha
     * de envío y un evento de auditoría en la misma operación de dominio.
     */
    @Test
    @DisplayName("Marca enviada una transacción reservada por su vendedor dueño")
    void marcarEnviado_ReservadaYVendedor_ActualizaEstadoFechaYPersisteEvento() {
        Usuario vendedor = vendedor(10L);
        Transaccion transaccion = transaccion(vendedor, EstadoTransaccion.RESERVADA);
        when(transaccionRepository.findById(100L)).thenReturn(Optional.of(transaccion));
        when(transaccionRepository.save(transaccion)).thenReturn(transaccion);

        Transaccion resultado = transicionEnvioEntregaService.marcarEnviado(100L, 10L);

        assertThat(resultado.getEstado()).isEqualTo(EstadoTransaccion.ENVIADO);
        assertThat(resultado.getFechaEnviado()).isNotNull();
        ArgumentCaptor<TransaccionEvento> eventoCaptor = ArgumentCaptor.forClass(TransaccionEvento.class);
        verify(transaccionEventoRepository).save(eventoCaptor.capture());
        TransaccionEvento evento = eventoCaptor.getValue();
        assertThat(evento.getTransaccion()).isSameAs(resultado);
        assertThat(evento.getActor()).isSameAs(vendedor);
        assertThat(evento.getEstadoOrigen()).isEqualTo(EstadoTransaccion.RESERVADA);
        assertThat(evento.getEstadoDestino()).isEqualTo(EstadoTransaccion.ENVIADO);
        assertThat(evento.getMotivo()).isNull();
        assertThat(evento.getCreatedAt()).isNotNull();
    }

    /**
     * Verifica que el vendedor dueño puede avanzar de enviado a entregado, persistiendo la
     * descripción opcional en la transacción y un evento append-only con motivo nulo en la misma
     * operación de dominio.
     */
    @Test
    @DisplayName("Marca entregada una transacción enviada y persiste la descripción de prueba")
    void marcarEntregado_EnviadaYVendedorConDescripcion_PersisteDescripcionYEventoSinMotivo() {
        Usuario vendedor = vendedor(10L);
        Transaccion transaccion = transaccion(vendedor, EstadoTransaccion.ENVIADO);
        when(transaccionRepository.findById(100L)).thenReturn(Optional.of(transaccion));
        when(transaccionRepository.save(transaccion)).thenReturn(transaccion);

        Transaccion resultado = transicionEnvioEntregaService.marcarEntregado(100L, 10L,
            "Foto entregada en recepción");

        assertThat(resultado.getEstado()).isEqualTo(EstadoTransaccion.ENTREGADO);
        assertThat(resultado.getFechaEntregado()).isNotNull();
        assertThat(resultado.getDescripcionPruebaEntrega()).isEqualTo("Foto entregada en recepción");
        ArgumentCaptor<TransaccionEvento> eventoCaptor = ArgumentCaptor.forClass(TransaccionEvento.class);
        verify(transaccionEventoRepository).save(eventoCaptor.capture());
        TransaccionEvento evento = eventoCaptor.getValue();
        assertThat(evento.getTransaccion()).isSameAs(resultado);
        assertThat(evento.getActor()).isSameAs(vendedor);
        assertThat(evento.getEstadoOrigen()).isEqualTo(EstadoTransaccion.ENVIADO);
        assertThat(evento.getEstadoDestino()).isEqualTo(EstadoTransaccion.ENTREGADO);
        assertThat(evento.getMotivo()).isNull();
        assertThat(evento.getCreatedAt()).isNotNull();
    }

    /**
     * Verifica que la descripción de prueba de entrega es opcional: el vendedor dueño puede marcar
     * entregada una transacción enviada con {@code null}, y ese valor nulo se persiste en la
     * transacción mientras el evento append-only conserva su motivo de negocio nulo.
     */
    @Test
    @DisplayName("Permite marcar entregada sin descripción de prueba")
    void marcarEntregado_EnviadaYVendedorSinDescripcion_PermiteNullYPersisteEventoSinMotivo() {
        Usuario vendedor = vendedor(10L);
        Transaccion transaccion = transaccion(vendedor, EstadoTransaccion.ENVIADO);
        when(transaccionRepository.findById(100L)).thenReturn(Optional.of(transaccion));
        when(transaccionRepository.save(transaccion)).thenReturn(transaccion);

        Transaccion resultado = transicionEnvioEntregaService.marcarEntregado(100L, 10L, null);

        assertThat(resultado.getEstado()).isEqualTo(EstadoTransaccion.ENTREGADO);
        assertThat(resultado.getFechaEntregado()).isNotNull();
        assertThat(resultado.getDescripcionPruebaEntrega()).isNull();
        ArgumentCaptor<TransaccionEvento> eventoCaptor = ArgumentCaptor.forClass(TransaccionEvento.class);
        verify(transaccionEventoRepository).save(eventoCaptor.capture());
        TransaccionEvento evento = eventoCaptor.getValue();
        assertThat(evento.getTransaccion()).isSameAs(resultado);
        assertThat(evento.getActor()).isSameAs(vendedor);
        assertThat(evento.getEstadoOrigen()).isEqualTo(EstadoTransaccion.ENVIADO);
        assertThat(evento.getEstadoDestino()).isEqualTo(EstadoTransaccion.ENTREGADO);
        assertThat(evento.getMotivo()).isNull();
        assertThat(evento.getCreatedAt()).isNotNull();
    }

    /**
     * Verifica que marcar entregada sigue rechazando una transición que no parte de
     * {@link EstadoTransaccion#ENVIADO}, incluso cuando se provee una descripción opcional.
     */
    @Test
    @DisplayName("Rechaza marcar entregada cuando la transacción no está enviada")
    void marcarEntregado_EstadoFueraDeOrden_LanzaExcepcionEspecifica() {
        Usuario vendedor = vendedor(10L);
        Transaccion transaccion = transaccion(vendedor, EstadoTransaccion.RESERVADA);
        when(transaccionRepository.findById(100L)).thenReturn(Optional.of(transaccion));

        assertThatThrownBy(() -> transicionEnvioEntregaService.marcarEntregado(100L, 10L, null))
            .isInstanceOf(TransicionEstadoTransaccionInvalidaException.class);

        verify(transaccionRepository, never()).save(any());
        verify(transaccionEventoRepository, never()).save(any());
    }

    /**
     * Verifica que un actor distinto del vendedor dueño no puede marcar entregada una transacción
     * enviada, incluso cuando proporciona una descripción de prueba de entrega.
     */
    @Test
    @DisplayName("Rechaza marcar entregada si el actor no es el vendedor dueño")
    void marcarEntregado_ActorNoEsVendedor_LanzaExcepcionEspecifica() {
        Usuario vendedor = vendedor(10L);
        Transaccion transaccion = transaccion(vendedor, EstadoTransaccion.ENVIADO);
        when(transaccionRepository.findById(100L)).thenReturn(Optional.of(transaccion));

        assertThatThrownBy(() -> transicionEnvioEntregaService.marcarEntregado(100L, 20L,
            "Foto entregada en recepción"))
            .isInstanceOf(ActorNoEsVendedorTransaccionException.class);

        verify(transaccionRepository, never()).save(any());
        verify(transaccionEventoRepository, never()).save(any());
    }

    /**
     * Crea un vendedor persistente simulado para las pruebas.
     *
     * @param id ID persistente a asignar al vendedor
     * @return usuario vendedor con el ID indicado
     */
    private Usuario vendedor(Long id) {
        Usuario vendedor = new Usuario();
        vendedor.setId(id);
        return vendedor;
    }

    /**
     * Crea una transacción persistente simulada, asociada a una publicación del vendedor indicado.
     *
     * @param vendedor dueño de la publicación de la transacción
     * @param estado estado inicial requerido para el escenario de prueba
     * @return transacción configurada con ID, publicación y estado indicados
     */
    private Transaccion transaccion(Usuario vendedor, EstadoTransaccion estado) {
        Publicacion publicacion = new Publicacion();
        publicacion.setUsuario(vendedor);

        Transaccion transaccion = new Transaccion();
        transaccion.setId(100L);
        transaccion.setPublicacion(publicacion);
        transaccion.setEstado(estado);
        transaccion.setFechaReservada(ZonedDateTime.now());
        return transaccion;
    }
}
