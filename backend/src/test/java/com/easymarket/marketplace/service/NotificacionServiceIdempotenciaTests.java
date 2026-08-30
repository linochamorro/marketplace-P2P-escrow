package com.easymarket.marketplace.service;

import com.easymarket.marketplace.model.Notificacion;
import com.easymarket.marketplace.model.Publicacion;
import com.easymarket.marketplace.model.Rol;
import com.easymarket.marketplace.model.Transaccion;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.NotificacionRepository;
import com.easymarket.marketplace.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * Verifica la reutilización idempotente de la proyección de notificaciones por elemento pendiente.
 */
@ExtendWith(MockitoExtension.class)
class NotificacionServiceIdempotenciaTests {

    @Mock
    private NotificacionRepository notificacionRepository;

    @Mock
    private UsuarioRepository usuarioRepository;

    private NotificacionService service;
    private Usuario admin;
    private Usuario usuario;
    private Publicacion publicacion;
    private Transaccion transaccion;
    private ZonedDateTime ahora;

    /** Prepara destinatarios y asociaciones con identificadores persistidos simulados. */
    @BeforeEach
    void setUp() {
        service = new NotificacionService(notificacionRepository, usuarioRepository);
        admin = new Usuario("admin@example.com", "hash", Rol.ADMIN, 0L, ZonedDateTime.now());
        admin.setId(1L);
        usuario = new Usuario("user@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now());
        usuario.setId(2L);
        publicacion = new Publicacion();
        publicacion.setId(10L);
        transaccion = new Transaccion();
        transaccion.setId(20L);
        ahora = ZonedDateTime.parse("2026-08-29T12:00:00Z");
    }

    /** Comprueba que dos emisiones de publicación compartan una fila y reactiven una fila leída. */
    @Test
    void avisoDePublicacion_ReutilizaYReactivaLaMismaFila() {
        Notificacion existente = new Notificacion(admin, null, "antiguo", "PUBLICACION_PENDIENTE_APROBAR", ahora);
        existente.setLeida(true);
        when(usuarioRepository.findByRol(Rol.ADMIN)).thenReturn(Optional.of(admin));
        when(notificacionRepository.findByUsuario_IdAndPublicacion_IdAndTipo(1L, 10L,
                "PUBLICACION_PENDIENTE_APROBAR")).thenReturn(Optional.of(existente));
        when(notificacionRepository.upsertPublicacion(1L, 10L, "PUBLICACION_PENDIENTE_APROBAR",
                "nuevo", ahora)).thenReturn(1);

        Notificacion resultado = service.crearNotificacionAdmin(
                "PUBLICACION_PENDIENTE_APROBAR", "nuevo", publicacion, null, ahora);

        assertThat(resultado).isSameAs(existente);
        assertThat(resultado.isLeida()).isFalse();
        assertThat(resultado.getPublicacion()).isSameAs(publicacion);
        assertThat(resultado.getMensaje()).isEqualTo("nuevo");
        verify(notificacionRepository).upsertPublicacion(1L, 10L, "PUBLICACION_PENDIENTE_APROBAR",
                "nuevo", ahora);
    }

    /** Comprueba que la clave transaccional incluye destinatario y tipo y no inserta duplicados. */
    @Test
    void avisoTransaccional_ReutilizaLaFilaAunqueEsteLeida() {
        Notificacion existente = new Notificacion(usuario, transaccion, "antiguo", "COMPRA_CONFIRMADA", ahora);
        existente.setLeida(true);
        when(notificacionRepository.findByUsuario_IdAndTransaccion_IdAndTipo(2L, 20L,
                "COMPRA_CONFIRMADA")).thenReturn(Optional.of(existente));
        when(notificacionRepository.upsertTransaccion(2L, 20L, "COMPRA_CONFIRMADA", "nuevo", ahora))
                .thenReturn(1);

        Notificacion resultado = service.crearNotificacionUsuario(
                usuario, "COMPRA_CONFIRMADA", "nuevo", null, transaccion, ahora);

        assertThat(resultado).isSameAs(existente);
        assertThat(resultado.isLeida()).isFalse();
        assertThat(resultado.getTransaccion()).isSameAs(transaccion);
        verify(notificacionRepository).upsertTransaccion(2L, 20L, "COMPRA_CONFIRMADA", "nuevo", ahora);
    }

    /** Comprueba que los recordatorios diarios insertan historial y nunca reutilizan el slot normal. */
    @Test
    void avisoDiario_ConservaDosFilasParaLaMismaTransaccion() {
        when(notificacionRepository.save(org.mockito.ArgumentMatchers.any(Notificacion.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.crearNotificacionDiaria(usuario, "COMPRA_PENDIENTE_DIARIA", "COMPRAS PENDIENTES",
                transaccion, ahora);
        service.crearNotificacionDiaria(usuario, "COMPRA_PENDIENTE_DIARIA", "COMPRAS PENDIENTES",
                transaccion, ahora.plusHours(24).plusMinutes(1));

        verify(notificacionRepository, times(2))
                .save(org.mockito.ArgumentMatchers.any(Notificacion.class));
        org.mockito.Mockito.verify(notificacionRepository, org.mockito.Mockito.never())
                .findByUsuario_IdAndTransaccion_IdAndTipo(2L, 20L, "COMPRA_PENDIENTE_DIARIA");
    }

    /**
     * Requires the normal publication path to use one database upsert instead of a read-then-save
     * sequence, so a concurrent loser can retry/reuse rather than expose a unique-key error.
     */
    @Test
    void avisoDePublicacion_UsaUpsertAtomico() {
        when(usuarioRepository.findByRol(Rol.ADMIN)).thenReturn(Optional.of(admin));
        when(notificacionRepository.upsertPublicacion(1L, 10L, "PUBLICACION_PENDIENTE_APROBAR",
                "nuevo", ahora)).thenReturn(1);
        Notificacion existente = new Notificacion(admin, publicacion, null, "nuevo",
                "PUBLICACION_PENDIENTE_APROBAR", ahora);
        when(notificacionRepository.findByUsuario_IdAndPublicacion_IdAndTipo(1L, 10L,
                "PUBLICACION_PENDIENTE_APROBAR")).thenReturn(Optional.of(existente));

        Notificacion resultado = service.crearNotificacionAdmin(
                "PUBLICACION_PENDIENTE_APROBAR", "nuevo", publicacion, null, ahora);

        assertThat(resultado).isSameAs(existente);
        verify(notificacionRepository).upsertPublicacion(1L, 10L, "PUBLICACION_PENDIENTE_APROBAR",
                "nuevo", ahora);
        verify(notificacionRepository).findByUsuario_IdAndPublicacion_IdAndTipo(1L, 10L,
                "PUBLICACION_PENDIENTE_APROBAR");
    }

    /** Requires the normal transaction path to use the atomic transaction-slot upsert. */
    @Test
    void avisoTransaccional_UsaUpsertAtomico() {
        when(notificacionRepository.upsertTransaccion(2L, 20L, "COMPRA_CONFIRMADA",
                "nuevo", ahora)).thenReturn(1);
        Notificacion existente = new Notificacion(usuario, null, transaccion, "nuevo",
                "COMPRA_CONFIRMADA", ahora);
        when(notificacionRepository.findByUsuario_IdAndTransaccion_IdAndTipo(2L, 20L,
                "COMPRA_CONFIRMADA")).thenReturn(Optional.of(existente));

        Notificacion resultado = service.crearNotificacionUsuario(
                usuario, "COMPRA_CONFIRMADA", "nuevo", null, transaccion, ahora);

        assertThat(resultado).isSameAs(existente);
        verify(notificacionRepository).upsertTransaccion(2L, 20L, "COMPRA_CONFIRMADA", "nuevo", ahora);
        verify(notificacionRepository).findByUsuario_IdAndTransaccion_IdAndTipo(2L, 20L,
                "COMPRA_CONFIRMADA");
    }
}
