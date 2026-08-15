package com.easymarket.marketplace.service;

import com.easymarket.marketplace.model.Categoria;
import com.easymarket.marketplace.model.Notificacion;
import com.easymarket.marketplace.model.Publicacion;
import com.easymarket.marketplace.model.PublicacionEvento;
import com.easymarket.marketplace.model.Rol;
import com.easymarket.marketplace.model.Subcategoria;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.CategoriaRepository;
import com.easymarket.marketplace.repository.NotificacionRepository;
import com.easymarket.marketplace.repository.PublicacionEventoRepository;
import com.easymarket.marketplace.repository.PublicacionRepository;
import com.easymarket.marketplace.repository.SubcategoriaRepository;
import com.easymarket.marketplace.repository.UsuarioRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias de los efectos de auditoría y aviso creados junto con una publicación de Story 1.
 *
 * <p>Comprueba el contenido de los objetos entregados a los repositorios; el rollback real de los
 * tres efectos se comprueba independientemente contra PostgreSQL en
 * {@link PublicacionCreacionAuditoriaAtomicidadIntegrationTests}.</p>
 */
@ExtendWith(MockitoExtension.class)
class PublicacionCreacionAuditoriaTests {

    /** Repositorio de la publicación que entrega el identificador persistido. */
    @Mock private PublicacionRepository publicacionRepository;
    /** Repositorio que localiza al vendedor y a la cuenta ADMIN única. */
    @Mock private UsuarioRepository usuarioRepository;
    /** Repositorio de la categoría válida. */
    @Mock private CategoriaRepository categoriaRepository;
    /** Repositorio de la subcategoría válida. */
    @Mock private SubcategoriaRepository subcategoriaRepository;
    /** Repositorio del log append-only de publicación. */
    @Mock private PublicacionEventoRepository publicacionEventoRepository;
    /** Repositorio del aviso in-app dirigido al ADMIN. */
    @Mock private NotificacionRepository notificacionRepository;
    /** Servicio bajo prueba. */
    @InjectMocks private PublicacionService publicacionService;

    /**
     * Verifica que una creación válida guarde la publicación, el evento CREADA y el aviso literal
     * dirigido al ADMIN único.
     */
    @Test
    @DisplayName("Crear publicación guarda CREADA y NUEVA_PUBLICACION_PENDIENTE literal para ADMIN")
    void crearPublicacion_Valida_GuardaPublicacionEventoYAvisoParaAdmin() {
        Usuario vendedor = usuario("vendedor@example.com", Rol.USUARIO, 10L);
        Usuario admin = usuario("admin@example.com", Rol.ADMIN, 20L);
        Categoria categoria = new Categoria("Tecnología");
        categoria.setId(30L);
        Subcategoria subcategoria = new Subcategoria(categoria, "Laptops");
        subcategoria.setId(40L);
        Publicacion persistida = new Publicacion(vendedor, categoria, subcategoria, 150000L, 2, "Portátil");
        persistida.setId(50L);
        when(usuarioRepository.findById(10L)).thenReturn(Optional.of(vendedor));
        when(usuarioRepository.findByRol(Rol.ADMIN)).thenReturn(Optional.of(admin));
        when(categoriaRepository.findById(30L)).thenReturn(Optional.of(categoria));
        when(subcategoriaRepository.findById(40L)).thenReturn(Optional.of(subcategoria));
        when(publicacionRepository.save(any(Publicacion.class))).thenReturn(persistida);

        Publicacion resultado = publicacionService.crearPublicacion(10L, 30L, 40L, 150000L, 2, "Portátil");

        ArgumentCaptor<PublicacionEvento> evento = ArgumentCaptor.forClass(PublicacionEvento.class);
        ArgumentCaptor<Notificacion> aviso = ArgumentCaptor.forClass(Notificacion.class);
        assertThat(resultado).isSameAs(persistida);
        verify(publicacionRepository).save(any(Publicacion.class));
        verify(publicacionEventoRepository).save(evento.capture());
        verify(notificacionRepository).save(aviso.capture());
        assertThat(evento.getValue().getPublicacion()).isSameAs(persistida);
        assertThat(evento.getValue().getActor()).isSameAs(vendedor);
        assertThat(evento.getValue().getTipo()).isEqualTo("CREADA");
        assertThat(aviso.getValue().getUsuario()).isSameAs(admin);
        assertThat(aviso.getValue().getTipo()).isEqualTo("NUEVA_PUBLICACION_PENDIENTE");
        assertThat(aviso.getValue().getMensaje()).isEqualTo("Nueva publicación pendiente de revisión: #50");
    }

    /**
     * Construye un usuario identificable para el escenario unitario.
     *
     * @param email correo único del usuario
     * @param rol rol del usuario dentro del escenario
     * @param id identificador persistido simulado
     * @return usuario con el identificador indicado
     */
    private Usuario usuario(String email, Rol rol, Long id) {
        Usuario usuario = new Usuario(email, "hash", rol, 0L, ZonedDateTime.now());
        usuario.setId(id);
        return usuario;
    }
}
