package com.easymarket.marketplace.service;

import com.easymarket.marketplace.exception.CategoriaNoEncontradaException;
import com.easymarket.marketplace.exception.CategoriaPublicacionInmutableException;
import com.easymarket.marketplace.exception.EstadoPublicacionNoEditableException;
import com.easymarket.marketplace.exception.MotivoRequeridoException;
import com.easymarket.marketplace.exception.NoEsElPropietarioException;
import com.easymarket.marketplace.exception.PrecioInvalidoException;
import com.easymarket.marketplace.exception.PublicacionConTransaccionesException;
import com.easymarket.marketplace.exception.PublicacionNoEncontradaException;
import com.easymarket.marketplace.exception.StockInvalidoException;
import com.easymarket.marketplace.exception.SubcategoriaNoPerteneceACategoriaException;
import com.easymarket.marketplace.exception.TransicionEstadoInvalidaException;
import com.easymarket.marketplace.exception.UsuarioNoEncontradoException;
import com.easymarket.marketplace.model.Categoria;
import com.easymarket.marketplace.model.EstadoPublicacion;
import com.easymarket.marketplace.model.Notificacion;
import com.easymarket.marketplace.model.Publicacion;
import com.easymarket.marketplace.model.PublicacionEvento;
import com.easymarket.marketplace.model.Rol;
import com.easymarket.marketplace.model.Subcategoria;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.CategoriaRepository;
import com.easymarket.marketplace.repository.NotificacionRepository;
import com.easymarket.marketplace.repository.PublicacionRepository;
import com.easymarket.marketplace.repository.PublicacionEventoRepository;
import com.easymarket.marketplace.repository.SubcategoriaRepository;
import com.easymarket.marketplace.repository.TransaccionRepository;
import com.easymarket.marketplace.repository.UsuarioRepository;
import com.easymarket.marketplace.service.NotificacionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias para {@link PublicacionService}.
 *
 * <p>Verifica las reglas de negocio de creación y máquina de estados de publicaciones
 * (Stories 1, 2 y 3, spec.md):
 * <ul>
 *   <li>Validación de precio estrictamente positivo (centavos entero > 0).</li>
 *   <li>Validación de stock inicial mínimo de 1 unidad.</li>
 *   <li>Validación de existencia del usuario vendedor, categoría y subcategoría.</li>
 *   <li>Validación de consistencia padre-hijo (la subcategoría debe pertenecer a la categoría indicada).</li>
 *   <li>Asignación por defecto del estado {@link EstadoPublicacion#PENDIENTE_REVISION}.</li>
 *   <li>Transiciones de estado válidas conforme a la máquina de estados oficial.</li>
 *   <li>Edición de publicación aprobada (Story 3): actualización de precio, stock (≥0) y descripción.</li>
 *   <li>Bloqueo de modificación de categoría/subcategoría en publicación aprobada con {@link CategoriaPublicacionInmutableException}.</li>
 *   <li>Rechazo de edición en estados distintos a 'APROBADA' con {@link EstadoPublicacionNoEditableException}.</li>
 *   <li>Corrección de categoría/subcategoría desde 'CAMBIOS_SOLICITADOS' o 'RECHAZADA' con reenvío a 'PENDIENTE_REVISION' (Story 3).</li>
 *   <li>Rechazo de corrección en 'APROBADA' con {@link CategoriaPublicacionInmutableException} y en estados no corregibles con {@link TransicionEstadoInvalidaException}.</li>
 *   <li>Eliminación definitiva de publicación propia en CUALQUIER estado (APROBADA, PENDIENTE_REVISION, OCULTA, CAMBIOS_SOLICITADOS, RECHAZADA) — corrección de Lino 2026-08-20 extendiendo Story 3.</li>
 *   <li>Rechazo de eliminación de una publicación con al menos una transacción asociada con {@link PublicacionConTransaccionesException} — decisión de Lino 2026-08-23 (PHA12).</li>
 *   <li>Carga bajo lock pesimista ({@code findByIdWithLock}, {@code PESSIMISTIC_WRITE}) como PRIMERA operación de la eliminación, cerrando la ventana TOCTOU compra-vs-delete entre {@code existsByPublicacionId} y {@code delete} — endurecimiento de Lino 2026-08-23 (PHA12TSK07).</li>
 *   <li>Validación de propiedad ({@link NoEsElPropietarioException}) en corrección y eliminación.</li>
 * </ul>
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class PublicacionServiceTests {

    @Mock
    private PublicacionRepository publicacionRepository;

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private CategoriaRepository categoriaRepository;

    @Mock
    private SubcategoriaRepository subcategoriaRepository;

    @Mock
    private PublicacionEventoRepository publicacionEventoRepository;

    @Mock
    private NotificacionRepository notificacionRepository;

    @Mock
    private NotificacionService notificacionService;

    @Mock
    private TransaccionRepository transaccionRepository;

    @InjectMocks
    private PublicacionService publicacionService;

    /**
     * Verifica la creación exitosa de una publicación con datos válidos y estado inicial 'pendiente_revisión'.
     */
    @Test
    @DisplayName("Debe crear la publicación con estado 'pendiente_revisión' cuando todos los datos son válidos")
    void crearPublicacion_DatosValidos_CreaPublicacionConEstadoPendienteRevision() {
        Usuario usuario = new Usuario("vendedor@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now());
        usuario.setId(1L);
        Usuario admin = new Usuario("admin@example.com", "hash", Rol.ADMIN, 0L, ZonedDateTime.now());
        admin.setId(2L);

        Categoria categoria = new Categoria("Electrónica");
        categoria.setId(10L);

        Subcategoria subcategoria = new Subcategoria(categoria, "Laptops");
        subcategoria.setId(100L);

        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(usuario));
        lenient().when(usuarioRepository.findByRol(Rol.ADMIN)).thenReturn(Optional.of(admin));
        when(categoriaRepository.findById(10L)).thenReturn(Optional.of(categoria));
        when(subcategoriaRepository.findById(100L)).thenReturn(Optional.of(subcategoria));

        Publicacion publicacionGuardada = new Publicacion(
            usuario, categoria, subcategoria, 150000L, 5, "Laptop Core i7"
        );
        publicacionGuardada.setId(500L);

        when(publicacionRepository.save(any(Publicacion.class))).thenReturn(publicacionGuardada);

        Publicacion resultado = publicacionService.crearPublicacion(
            1L, 10L, 100L, 150000L, 5, "Laptop Core i7"
        );

        assertThat(resultado.getId()).isEqualTo(500L);
        assertThat(resultado.getEstado()).isEqualTo(EstadoPublicacion.PENDIENTE_REVISION);
        assertThat(resultado.getPrecio()).isEqualTo(150000L);
        assertThat(resultado.getStock()).isEqualTo(5);

        verify(publicacionRepository).save(any(Publicacion.class));
        verify(notificacionService).crearNotificacionAdmin(eq("PUBLICACION_PENDIENTE_APROBAR"), anyString(), isNull(), any());
    }

    /**
     * Verifica que la creación válida persista los tres efectos de Story 1: publicación pendiente,
     * evento canónico CREADA y aviso in-app para la única cuenta ADMIN.
     */
    @Test
    @DisplayName("Debe guardar publicación, evento CREADA y aviso literal para el ADMIN al crear una publicación")
    void crearPublicacion_DatosValidos_GuardaPublicacionEventoYNotificacionAdmin() {
        Usuario vendedor = new Usuario("vendedor-auditoria@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now());
        vendedor.setId(1L);
        Usuario admin = new Usuario("admin-auditoria@example.com", "hash", Rol.ADMIN, 0L, ZonedDateTime.now());
        admin.setId(2L);
        Categoria categoria = new Categoria("Electrónica");
        categoria.setId(10L);
        Subcategoria subcategoria = new Subcategoria(categoria, "Laptops");
        subcategoria.setId(100L);
        Publicacion publicacionGuardada = new Publicacion(vendedor, categoria, subcategoria, 150000L, 5, "Laptop Core i7");
        publicacionGuardada.setId(500L);

        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(vendedor));
        lenient().when(usuarioRepository.findByRol(Rol.ADMIN)).thenReturn(Optional.of(admin));
        when(categoriaRepository.findById(10L)).thenReturn(Optional.of(categoria));
        when(subcategoriaRepository.findById(100L)).thenReturn(Optional.of(subcategoria));
        when(publicacionRepository.save(any(Publicacion.class))).thenReturn(publicacionGuardada);
        lenient().when(notificacionService.crearNotificacionAdmin(anyString(), anyString(), any(), any())).thenReturn(mock(Notificacion.class));

        Publicacion resultado = publicacionService.crearPublicacion(1L, 10L, 100L, 150000L, 5, "Laptop Core i7");

        assertThat(resultado).isSameAs(publicacionGuardada);
        ArgumentCaptor<PublicacionEvento> eventoCaptor = ArgumentCaptor.forClass(PublicacionEvento.class);
        verify(publicacionEventoRepository).save(eventoCaptor.capture());
        assertThat(eventoCaptor.getValue().getPublicacion()).isSameAs(publicacionGuardada);
        assertThat(eventoCaptor.getValue().getActor()).isSameAs(vendedor);
        assertThat(eventoCaptor.getValue().getTipo()).isEqualTo("CREADA");
        verify(notificacionService).crearNotificacionAdmin(eq("PUBLICACION_PENDIENTE_APROBAR"), anyString(), isNull(), any());
    }

    /**
     * Verifica que se rechace la creación si el precio es menor o igual a cero.
     */
    @Test
    @DisplayName("Debe lanzar PrecioInvalidoException cuando el precio es menor o igual a cero")
    void crearPublicacion_PrecioCeroONegativo_LanzaPrecioInvalidoException() {
        assertThatThrownBy(() -> publicacionService.crearPublicacion(1L, 10L, 100L, 0L, 5, "Descripción"))
            .isInstanceOf(PrecioInvalidoException.class)
            .hasMessageContaining("El precio debe ser un monto entero positivo mayor a cero");

        assertThatThrownBy(() -> publicacionService.crearPublicacion(1L, 10L, 100L, -5000L, 5, "Descripción"))
            .isInstanceOf(PrecioInvalidoException.class);

        verify(publicacionRepository, never()).save(any(Publicacion.class));
    }

    /**
     * Verifica que se rechace la creación si el stock inicial es menor a uno.
     */
    @Test
    @DisplayName("Debe lanzar StockInvalidoException cuando el stock inicial es menor a uno")
    void crearPublicacion_StockCeroONegativo_LanzaStockInvalidoException() {
        assertThatThrownBy(() -> publicacionService.crearPublicacion(1L, 10L, 100L, 1000L, 0, "Descripción"))
            .isInstanceOf(StockInvalidoException.class)
            .hasMessageContaining("El stock inicial debe ser al menos de 1 unidad");

        assertThatThrownBy(() -> publicacionService.crearPublicacion(1L, 10L, 100L, 1000L, -2, "Descripción"))
            .isInstanceOf(StockInvalidoException.class);

        verify(publicacionRepository, never()).save(any(Publicacion.class));
    }

    /**
     * Verifica que se rechace la creación si el usuario vendedor no existe.
     */
    @Test
    @DisplayName("Debe lanzar UsuarioNoEncontradoException cuando el usuario no existe")
    void crearPublicacion_UsuarioInexistente_LanzaUsuarioNoEncontradoException() {
        when(usuarioRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> publicacionService.crearPublicacion(999L, 10L, 100L, 5000L, 2, "Descripción"))
            .isInstanceOf(UsuarioNoEncontradoException.class)
            .hasMessageContaining("Usuario vendedor con ID 999 no encontrado");

        verify(publicacionRepository, never()).save(any(Publicacion.class));
    }

    /**
     * Verifica que se rechace la creación si la categoría no existe.
     */
    @Test
    @DisplayName("Debe lanzar CategoriaNoEncontradaException cuando la categoría no existe")
    void crearPublicacion_CategoriaInexistente_LanzaCategoriaNoEncontradaException() {
        Usuario usuario = new Usuario("vendedor2@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now());
        usuario.setId(2L);

        when(usuarioRepository.findById(2L)).thenReturn(Optional.of(usuario));
        when(categoriaRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> publicacionService.crearPublicacion(2L, 999L, 100L, 5000L, 2, "Descripción"))
            .isInstanceOf(CategoriaNoEncontradaException.class)
            .hasMessageContaining("Categoría raíz con ID 999 no encontrada");

        verify(publicacionRepository, never()).save(any(Publicacion.class));
    }

    /**
     * Verifica que se rechace la creación si la subcategoría no existe.
     */
    @Test
    @DisplayName("Debe lanzar CategoriaNoEncontradaException cuando la subcategoría no existe")
    void crearPublicacion_SubcategoriaInexistente_LanzaCategoriaNoEncontradaException() {
        Usuario usuario = new Usuario("vendedor3@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now());
        usuario.setId(3L);

        Categoria categoria = new Categoria("Hogar");
        categoria.setId(20L);

        when(usuarioRepository.findById(3L)).thenReturn(Optional.of(usuario));
        when(categoriaRepository.findById(20L)).thenReturn(Optional.of(categoria));
        when(subcategoriaRepository.findById(888L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> publicacionService.crearPublicacion(3L, 20L, 888L, 5000L, 2, "Descripción"))
            .isInstanceOf(CategoriaNoEncontradaException.class)
            .hasMessageContaining("Subcategoría con ID 888 no encontrada");

        verify(publicacionRepository, never()).save(any(Publicacion.class));
    }

    /**
     * Verifica que se rechace la creación si la subcategoría existe pero pertenece a una categoría padre diferente.
     */
    @Test
    @DisplayName("Debe lanzar SubcategoriaNoPerteneceACategoriaException si la subcategoría no pertenece a la categoría indicada")
    void crearPublicacion_SubcategoriaNoPerteneceACategoria_LanzaSubcategoriaNoPerteneceACategoriaException() {
        Usuario usuario = new Usuario("vendedor4@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now());
        usuario.setId(4L);

        Categoria categoriaElectrónica = new Categoria("Electrónica");
        categoriaElectrónica.setId(10L);

        Categoria categoriaHogar = new Categoria("Hogar");
        categoriaHogar.setId(20L);

        // Subcategoría "Muebles" pertenece a Hogar (20L), pero se intenta asociar con Electrónica (10L)
        Subcategoria subcategoriaMuebles = new Subcategoria(categoriaHogar, "Muebles");
        subcategoriaMuebles.setId(200L);

        when(usuarioRepository.findById(4L)).thenReturn(Optional.of(usuario));
        when(categoriaRepository.findById(10L)).thenReturn(Optional.of(categoriaElectrónica));
        when(subcategoriaRepository.findById(200L)).thenReturn(Optional.of(subcategoriaMuebles));

        assertThatThrownBy(() -> publicacionService.crearPublicacion(4L, 10L, 200L, 5000L, 2, "Descripción"))
            .isInstanceOf(SubcategoriaNoPerteneceACategoriaException.class)
            .hasMessageContaining("La subcategoría 'Muebles' (ID 200) no pertenece a la categoría con ID 10");

        verify(publicacionRepository, never()).save(any(Publicacion.class));
    }

    /**
     * Verifica la obtención exitosa de una publicación por su ID existente.
     */
    @Test
    @DisplayName("Debe retornar la publicación cuando el ID existe")
    void obtenerPublicacionPorId_IdExistente_RetornaPublicacion() {
        Publicacion publicacion = new Publicacion();
        publicacion.setId(50L);

        when(publicacionRepository.findById(50L)).thenReturn(Optional.of(publicacion));

        Publicacion resultado = publicacionService.obtenerPublicacionPorId(50L);

        assertThat(resultado.getId()).isEqualTo(50L);
    }

    /**
     * Verifica que se lance PublicacionNoEncontradaException si el ID de publicación no existe.
     */
    @Test
    @DisplayName("Debe lanzar PublicacionNoEncontradaException cuando el ID de publicación no existe")
    void obtenerPublicacionPorId_IdInexistente_LanzaPublicacionNoEncontradaException() {
        when(publicacionRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> publicacionService.obtenerPublicacionPorId(999L))
            .isInstanceOf(PublicacionNoEncontradaException.class)
            .hasMessageContaining("Publicación con ID 999 no encontrada");
    }

    /**
     * Verifica la transición válida de PENDIENTE_REVISION a APROBADA.
     */
    @Test
    @DisplayName("Debe cambiar estado de PENDIENTE_REVISION a APROBADA correctamente")
    void cambiarEstado_PendienteRevisionAAprobada_TransicionExitosa() {
        Publicacion p = new Publicacion();
        p.setId(10L);
        p.setEstado(EstadoPublicacion.PENDIENTE_REVISION);

        when(publicacionRepository.findById(10L)).thenReturn(Optional.of(p));
        when(publicacionRepository.save(any(Publicacion.class))).thenAnswer(i -> i.getArgument(0));

        Publicacion resultado = publicacionService.cambiarEstado(10L, EstadoPublicacion.APROBADA, null);

        assertThat(resultado.getEstado()).isEqualTo(EstadoPublicacion.APROBADA);
        verify(publicacionRepository).save(p);
    }

    /**
     * Verifica la transición válida de PENDIENTE_REVISION a CAMBIOS_SOLICITADOS con motivo.
     */
    @Test
    @DisplayName("Debe cambiar estado de PENDIENTE_REVISION a CAMBIOS_SOLICITADOS con motivo de texto libre")
    void cambiarEstado_PendienteRevisionACambiosSolicitados_ConMotivo_TransicionExitosa() {
        Publicacion p = new Publicacion();
        p.setId(11L);
        p.setEstado(EstadoPublicacion.PENDIENTE_REVISION);

        when(publicacionRepository.findById(11L)).thenReturn(Optional.of(p));
        when(publicacionRepository.save(any(Publicacion.class))).thenAnswer(i -> i.getArgument(0));

        Publicacion resultado = publicacionService.cambiarEstado(11L, EstadoPublicacion.CAMBIOS_SOLICITADOS, "Categoría incorrecta");

        assertThat(resultado.getEstado()).isEqualTo(EstadoPublicacion.CAMBIOS_SOLICITADOS);
        verify(publicacionRepository).save(p);
    }

    /**
     * Verifica la transición válida de PENDIENTE_REVISION a RECHAZADA con motivo obligatorio.
     */
    @Test
    @DisplayName("Debe cambiar estado de PENDIENTE_REVISION a RECHAZADA con motivo obligatorio")
    void cambiarEstado_PendienteRevisionARechazada_ConMotivo_TransicionExitosa() {
        Publicacion p = new Publicacion();
        p.setId(12L);
        p.setEstado(EstadoPublicacion.PENDIENTE_REVISION);

        when(publicacionRepository.findById(12L)).thenReturn(Optional.of(p));
        when(publicacionRepository.save(any(Publicacion.class))).thenAnswer(i -> i.getArgument(0));

        Publicacion resultado = publicacionService.cambiarEstado(12L, EstadoPublicacion.RECHAZADA, "Producto prohibido: armas");

        assertThat(resultado.getEstado()).isEqualTo(EstadoPublicacion.RECHAZADA);
        verify(publicacionRepository).save(p);
    }

    /**
     * Verifica la transición de ida y vuelta de CAMBIOS_SOLICITADOS a PENDIENTE_REVISION.
     */
    @Test
    @DisplayName("Debe cambiar estado de CAMBIOS_SOLICITADOS a PENDIENTE_REVISION al reenviar el vendedor")
    void cambiarEstado_CambiosSolicitadosAPendienteRevision_TransicionExitosa() {
        Publicacion p = new Publicacion();
        p.setId(13L);
        p.setEstado(EstadoPublicacion.CAMBIOS_SOLICITADOS);

        when(publicacionRepository.findById(13L)).thenReturn(Optional.of(p));
        when(publicacionRepository.save(any(Publicacion.class))).thenAnswer(i -> i.getArgument(0));

        Publicacion resultado = publicacionService.cambiarEstado(13L, EstadoPublicacion.PENDIENTE_REVISION, null);

        assertThat(resultado.getEstado()).isEqualTo(EstadoPublicacion.PENDIENTE_REVISION);
        verify(publicacionRepository).save(p);
    }

    /**
     * Verifica la transición de ida y vuelta de RECHAZADA a PENDIENTE_REVISION.
     */
    @Test
    @DisplayName("Debe cambiar estado de RECHAZADA a PENDIENTE_REVISION al corregir y reenviar el vendedor")
    void cambiarEstado_RechazadaAPendienteRevision_TransicionExitosa() {
        Publicacion p = new Publicacion();
        p.setId(14L);
        p.setEstado(EstadoPublicacion.RECHAZADA);

        when(publicacionRepository.findById(14L)).thenReturn(Optional.of(p));
        when(publicacionRepository.save(any(Publicacion.class))).thenAnswer(i -> i.getArgument(0));

        Publicacion resultado = publicacionService.cambiarEstado(14L, EstadoPublicacion.PENDIENTE_REVISION, null);

        assertThat(resultado.getEstado()).isEqualTo(EstadoPublicacion.PENDIENTE_REVISION);
        verify(publicacionRepository).save(p);
    }
    /**
     * Verifica que transiciones que involucren OCULTA sean rechazadas con TransicionEstadoInvalidaException en esta tarea.
     */
    @Test
    @DisplayName("Debe lanzar TransicionEstadoInvalidaException para transiciones que involucren OCULTA")
    void cambiarEstado_TransicionesConOculta_LanzaTransicionEstadoInvalidaException() {
        Publicacion pAprobada = new Publicacion();
        pAprobada.setId(15L);
        pAprobada.setEstado(EstadoPublicacion.APROBADA);

        Publicacion pOculta = new Publicacion();
        pOculta.setId(18L);
        pOculta.setEstado(EstadoPublicacion.OCULTA);

        when(publicacionRepository.findById(15L)).thenReturn(Optional.of(pAprobada));
        when(publicacionRepository.findById(18L)).thenReturn(Optional.of(pOculta));

        // APROBADA -> OCULTA
        assertThatThrownBy(() -> publicacionService.cambiarEstado(15L, EstadoPublicacion.OCULTA, null))
            .isInstanceOf(TransicionEstadoInvalidaException.class)
            .hasMessageContaining("Transición de estado no permitida");

        // OCULTA -> APROBADA
        assertThatThrownBy(() -> publicacionService.cambiarEstado(18L, EstadoPublicacion.APROBADA, null))
            .isInstanceOf(TransicionEstadoInvalidaException.class)
            .hasMessageContaining("Transición de estado no permitida");

        verify(publicacionRepository, never()).save(any(Publicacion.class));
    }

    /**
     * Verifica que se rechacen las transiciones no permitidas en la máquina de estados.
     */
    @Test
    @DisplayName("Debe lanzar TransicionEstadoInvalidaException al intentar transiciones inválidas")
    void cambiarEstado_TransicionInvalida_LanzaTransicionEstadoInvalidaException() {
        Publicacion p = new Publicacion();
        p.setId(16L);
        p.setEstado(EstadoPublicacion.APROBADA);

        when(publicacionRepository.findById(16L)).thenReturn(Optional.of(p));

        // APROBADA -> RECHAZADA (No permitido directamente)
        assertThatThrownBy(() -> publicacionService.cambiarEstado(16L, EstadoPublicacion.RECHAZADA, "Motivo"))
            .isInstanceOf(TransicionEstadoInvalidaException.class)
            .hasMessageContaining("Transición de estado no permitida");

        // APROBADA -> CAMBIOS_SOLICITADOS (Solo permitido desde PENDIENTE_REVISION)
        assertThatThrownBy(() -> publicacionService.cambiarEstado(16L, EstadoPublicacion.CAMBIOS_SOLICITADOS, "Motivo"))
            .isInstanceOf(TransicionEstadoInvalidaException.class);

        verify(publicacionRepository, never()).save(p);
    }

    /**
     * Verifica que se requiera motivo para cambiar a RECHAZADA o CAMBIOS_SOLICITADOS.
     */
    @Test
    @DisplayName("Debe lanzar MotivoRequeridoException cuando se cambia a RECHAZADA o CAMBIOS_SOLICITADOS sin motivo")
    void cambiarEstado_RechazadaOSolicitadaSinMotivo_LanzaMotivoRequeridoException() {
        Publicacion p = new Publicacion();
        p.setId(17L);
        p.setEstado(EstadoPublicacion.PENDIENTE_REVISION);

        when(publicacionRepository.findById(17L)).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> publicacionService.cambiarEstado(17L, EstadoPublicacion.RECHAZADA, "   "))
            .isInstanceOf(MotivoRequeridoException.class)
            .hasMessageContaining("Se requiere un motivo explícito");

        assertThatThrownBy(() -> publicacionService.cambiarEstado(17L, EstadoPublicacion.CAMBIOS_SOLICITADOS, null))
            .isInstanceOf(MotivoRequeridoException.class)
            .hasMessageContaining("Se requiere un motivo explícito");

        verify(publicacionRepository, never()).save(p);
    }

    /**
     * Verifica que intentar transicionar al mismo estado actual lanza {@link TransicionEstadoInvalidaException}.
     *
     * <p>El same-state no es una transición válida en la máquina de estados oficial.
     * Story 2 (spec.md) rechaza cualquier acción de admin sobre un estado distinto a
     * {@link EstadoPublicacion#PENDIENTE_REVISION}. Tratar same-state como no-op silencioso
     * viola ese contrato.</p>
     */
    @Test
    @DisplayName("Debe lanzar TransicionEstadoInvalidaException cuando el nuevo estado es igual al estado actual")
    void cambiarEstado_MismoEstado_LanzaTransicionEstadoInvalidaException() {
        Publicacion pAprobada = new Publicacion();
        pAprobada.setId(20L);
        pAprobada.setEstado(EstadoPublicacion.APROBADA);

        Publicacion pPendiente = new Publicacion();
        pPendiente.setId(21L);
        pPendiente.setEstado(EstadoPublicacion.PENDIENTE_REVISION);

        when(publicacionRepository.findById(20L)).thenReturn(Optional.of(pAprobada));
        when(publicacionRepository.findById(21L)).thenReturn(Optional.of(pPendiente));

        // APROBADA -> APROBADA (same-state)
        assertThatThrownBy(() -> publicacionService.cambiarEstado(20L, EstadoPublicacion.APROBADA, null))
            .isInstanceOf(TransicionEstadoInvalidaException.class)
            .hasMessageContaining("Transición de estado no permitida");

        // PENDIENTE_REVISION -> PENDIENTE_REVISION (same-state)
        assertThatThrownBy(() -> publicacionService.cambiarEstado(21L, EstadoPublicacion.PENDIENTE_REVISION, null))
            .isInstanceOf(TransicionEstadoInvalidaException.class)
            .hasMessageContaining("Transición de estado no permitida");

        verify(publicacionRepository, never()).save(any(Publicacion.class));
    }

    /**
     * Verifica que una publicación en estado APROBADA permita editar precio, stock (incluso 0) y descripción.
     */
    @Test
    @DisplayName("Debe editar precio, stock y descripción de publicación APROBADA cuando las categorías enviadas coinciden")
    void editarPublicacion_PublicacionAprobada_EditaCamposPermitidosExitosamente() {
        Categoria cat = new Categoria("Tecnología");
        cat.setId(10L);
        Subcategoria subcat = new Subcategoria(cat, "Gadgets");
        subcat.setId(100L);

        Publicacion p = new Publicacion();
        p.setId(30L);
        p.setEstado(EstadoPublicacion.APROBADA);
        p.setCategoria(cat);
        p.setSubcategoria(subcat);
        p.setPrecio(10000L);
        p.setStock(5);
        p.setDescripcion("Descripción antigua");

        when(publicacionRepository.findById(30L)).thenReturn(Optional.of(p));
        when(publicacionRepository.save(any(Publicacion.class))).thenAnswer(i -> i.getArgument(0));

        Publicacion resultado = publicacionService.editarPublicacion(30L, 25000L, 0, "Nueva descripción", 10L, 100L, null);

        assertThat(resultado.getPrecio()).isEqualTo(25000L);
        assertThat(resultado.getStock()).isEqualTo(0);
        assertThat(resultado.getDescripcion()).isEqualTo("Nueva descripción");
        assertThat(resultado.getEstado()).isEqualTo(EstadoPublicacion.APROBADA);
        verify(publicacionRepository).save(p);
    }

    /**
     * Verifica que intentar modificar categoría o subcategoría en publicación APROBADA lance CategoriaPublicacionInmutableException.
     */
    @Test
    @DisplayName("Debe lanzar CategoriaPublicacionInmutableException al intentar modificar categoría o subcategoría de publicación APROBADA")
    void editarPublicacion_CategoriaOSubcategoriaDiferente_LanzaCategoriaPublicacionInmutableException() {
        Categoria cat = new Categoria("Tecnología");
        cat.setId(10L);
        Subcategoria subcat = new Subcategoria(cat, "Gadgets");
        subcat.setId(100L);

        Publicacion p = new Publicacion();
        p.setId(31L);
        p.setEstado(EstadoPublicacion.APROBADA);
        p.setCategoria(cat);
        p.setSubcategoria(subcat);

        when(publicacionRepository.findById(31L)).thenReturn(Optional.of(p));

        // Intento de modificar categoría raíz (10L -> 20L)
        assertThatThrownBy(() -> publicacionService.editarPublicacion(31L, 10000L, 5, "Desc", 20L, 100L, null))
            .isInstanceOf(CategoriaPublicacionInmutableException.class)
            .hasMessageContaining("No se permite modificar la categoría o subcategoría");

        // Intento de modificar subcategoría (100L -> 200L)
        assertThatThrownBy(() -> publicacionService.editarPublicacion(31L, 10000L, 5, "Desc", 10L, 200L, null))
            .isInstanceOf(CategoriaPublicacionInmutableException.class)
            .hasMessageContaining("No se permite modificar la categoría o subcategoría");

        verify(publicacionRepository, never()).save(any(Publicacion.class));
    }

    /**
     * Verifica que intentar editar una publicación en un estado distinto a APROBADA lance EstadoPublicacionNoEditableException.
     */
    @Test
    @DisplayName("Debe lanzar EstadoPublicacionNoEditableException si la publicación no está en estado APROBADA")
    void editarPublicacion_EstadoNoAprobado_LanzaEstadoPublicacionNoEditableException() {
        Publicacion pPendiente = new Publicacion();
        pPendiente.setId(32L);
        pPendiente.setEstado(EstadoPublicacion.PENDIENTE_REVISION);

        when(publicacionRepository.findById(32L)).thenReturn(Optional.of(pPendiente));

        assertThatThrownBy(() -> publicacionService.editarPublicacion(32L, 10000L, 5, "Desc", null, null, null))
            .isInstanceOf(EstadoPublicacionNoEditableException.class)
            .hasMessageContaining("Solo se pueden editar publicaciones en estado 'APROBADA'");

        verify(publicacionRepository, never()).save(any(Publicacion.class));
    }

    /**
     * Verifica que un precio nulo o menor/igual a 0 al editar lance PrecioInvalidoException.
     */
    @Test
    @DisplayName("Debe lanzar PrecioInvalidoException si el precio editado es menor o igual a cero")
    void editarPublicacion_PrecioInvalido_LanzaPrecioInvalidoException() {
        Publicacion p = new Publicacion();
        p.setId(33L);
        p.setEstado(EstadoPublicacion.APROBADA);

        when(publicacionRepository.findById(33L)).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> publicacionService.editarPublicacion(33L, 0L, 5, "Desc", null, null, null))
            .isInstanceOf(PrecioInvalidoException.class)
            .hasMessageContaining("El precio debe ser un monto entero positivo mayor a cero");

        assertThatThrownBy(() -> publicacionService.editarPublicacion(33L, -500L, 5, "Desc", null, null, null))
            .isInstanceOf(PrecioInvalidoException.class);

        verify(publicacionRepository, never()).save(any(Publicacion.class));
    }

    /**
     * Verifica que un stock negativo al editar lance StockInvalidoException.
     */
    @Test
    @DisplayName("Debe lanzar StockInvalidoException si el stock editado es negativo")
    void editarPublicacion_StockNegativo_LanzaStockInvalidoException() {
        Publicacion p = new Publicacion();
        p.setId(34L);
        p.setEstado(EstadoPublicacion.APROBADA);

        when(publicacionRepository.findById(34L)).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> publicacionService.editarPublicacion(34L, 10000L, -1, "Desc", null, null, null))
            .isInstanceOf(StockInvalidoException.class)
            .hasMessageContaining("El stock no puede ser negativo");

        verify(publicacionRepository, never()).save(any(Publicacion.class));
    }

    /**
     * Verifica que actualizar el stock a 0 en una publicación APROBADA la transicione automáticamente a OCULTA (Story 10).
     */
    @Test
    @DisplayName("Debe transicionar de APROBADA a OCULTA automáticamente cuando el nuevo stock es 0")
    void actualizarStock_StockCeroEnAprobada_TransicionaAOcultaAutomaticamente() {
        Publicacion p = new Publicacion();
        p.setId(40L);
        p.setEstado(EstadoPublicacion.APROBADA);
        p.setStock(5);

        when(publicacionRepository.findById(40L)).thenReturn(Optional.of(p));
        when(publicacionRepository.save(any(Publicacion.class))).thenAnswer(i -> i.getArgument(0));

        Publicacion resultado = publicacionService.actualizarStock(40L, 0);

        assertThat(resultado.getStock()).isEqualTo(0);
        assertThat(resultado.getEstado()).isEqualTo(EstadoPublicacion.OCULTA);
        verify(publicacionRepository).save(p);
    }

    /**
     * Verifica que reponer stock (>= 1) en una publicación OCULTA la transicione automáticamente de vuelta a APROBADA (Story 10).
     */
    @Test
    @DisplayName("Debe transicionar de OCULTA a APROBADA automáticamente cuando se repone stock >= 1")
    void actualizarStock_StockMayorOIgualAUnoEnOculta_TransicionaAAprobadaAutomaticamente() {
        Publicacion p = new Publicacion();
        p.setId(41L);
        p.setEstado(EstadoPublicacion.OCULTA);
        p.setStock(0);

        when(publicacionRepository.findById(41L)).thenReturn(Optional.of(p));
        when(publicacionRepository.save(any(Publicacion.class))).thenAnswer(i -> i.getArgument(0));

        Publicacion resultado = publicacionService.actualizarStock(41L, 3);

        assertThat(resultado.getStock()).isEqualTo(3);
        assertThat(resultado.getEstado()).isEqualTo(EstadoPublicacion.APROBADA);
        verify(publicacionRepository).save(p);
    }

    /**
     * Verifica que intentar actualizar stock a un valor negativo lance StockInvalidoException.
     */
    @Test
    @DisplayName("Debe lanzar StockInvalidoException al intentar actualizar a stock negativo")
    void actualizarStock_StockNegativo_LanzaStockInvalidoException() {
        assertThatThrownBy(() -> publicacionService.actualizarStock(42L, -1))
            .isInstanceOf(StockInvalidoException.class)
            .hasMessageContaining("El stock no puede ser negativo");

        verify(publicacionRepository, never()).save(any(Publicacion.class));
    }

    /**
     * Verifica que actualizar el stock en estado PENDIENTE_REVISION conserve dicho estado sin forzar transiciones.
     */
    @Test
    @DisplayName("Debe actualizar el stock conservando el estado PENDIENTE_REVISION si la publicación no está moderada")
    void actualizarStock_EstadoPendienteRevision_ActualizaStockSinCambiarEstado() {
        Publicacion p = new Publicacion();
        p.setId(43L);
        p.setEstado(EstadoPublicacion.PENDIENTE_REVISION);
        p.setStock(5);

        when(publicacionRepository.findById(43L)).thenReturn(Optional.of(p));
        when(publicacionRepository.save(any(Publicacion.class))).thenAnswer(i -> i.getArgument(0));

        Publicacion resultado = publicacionService.actualizarStock(43L, 0);

        assertThat(resultado.getStock()).isEqualTo(0);
        assertThat(resultado.getEstado()).isEqualTo(EstadoPublicacion.PENDIENTE_REVISION);
        verify(publicacionRepository).save(p);
    }

    /**
     * Verifica que actualizar el stock a un valor mayor o igual a 1 en una publicación ya APROBADA conserve el estado APROBADA.
     */
    @Test
    @DisplayName("Debe actualizar el stock y conservar el estado APROBADA cuando se modifica stock >= 1")
    void actualizarStock_PublicacionAprobadaStockMayorAUno_ConservaEstadoAprobada() {
        Publicacion p = new Publicacion();
        p.setId(44L);
        p.setEstado(EstadoPublicacion.APROBADA);
        p.setStock(2);

        when(publicacionRepository.findById(44L)).thenReturn(Optional.of(p));
        when(publicacionRepository.save(any(Publicacion.class))).thenAnswer(i -> i.getArgument(0));

        Publicacion resultado = publicacionService.actualizarStock(44L, 10);

        assertThat(resultado.getStock()).isEqualTo(10);
        assertThat(resultado.getEstado()).isEqualTo(EstadoPublicacion.APROBADA);
        verify(publicacionRepository).save(p);
    }

    /**
     * Verifica que corregir la categoría/subcategoría desde CAMBIOS_SOLICITADOS reenvíe la publicación
     * a PENDIENTE_REVISION y persista los nuevos valores campo a campo (Story 3, spec.md).
     */
    @Test
    @DisplayName("Debe corregir categoría/subcategoría desde CAMBIOS_SOLICITADOS y reenviar a PENDIENTE_REVISION")
    void corregirPublicacion_DesdeCambiosSolicitados_CorrigeCategoriaYReenviaAPendienteRevision() {
        Usuario duenio = new Usuario("vendedor@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now());
        duenio.setId(1L);

        Categoria categoriaOriginal = new Categoria("Electrónica");
        categoriaOriginal.setId(10L);
        Subcategoria subcategoriaOriginal = new Subcategoria(categoriaOriginal, "Laptops");
        subcategoriaOriginal.setId(100L);

        Publicacion publicacion = new Publicacion(duenio, categoriaOriginal, subcategoriaOriginal, 150000L, 5, "Laptop Core i7");
        publicacion.setId(50L);
        publicacion.setEstado(EstadoPublicacion.CAMBIOS_SOLICITADOS);

        Categoria categoriaCorregida = new Categoria("Hogar");
        categoriaCorregida.setId(20L);
        Subcategoria subcategoriaCorregida = new Subcategoria(categoriaCorregida, "Muebles");
        subcategoriaCorregida.setId(200L);

        when(publicacionRepository.findById(50L)).thenReturn(Optional.of(publicacion));
        when(categoriaRepository.findById(20L)).thenReturn(Optional.of(categoriaCorregida));
        when(subcategoriaRepository.findById(200L)).thenReturn(Optional.of(subcategoriaCorregida));
        when(publicacionRepository.save(any(Publicacion.class))).thenAnswer(i -> i.getArgument(0));

        Publicacion resultado = publicacionService.corregirPublicacion(50L, 1L, 20L, 200L);

        assertThat(resultado.getEstado()).isEqualTo(EstadoPublicacion.PENDIENTE_REVISION);

        ArgumentCaptor<Publicacion> captor = ArgumentCaptor.forClass(Publicacion.class);
        verify(publicacionRepository).save(captor.capture());
        Publicacion persistida = captor.getValue();
        assertThat(persistida.getId()).isEqualTo(50L);
        assertThat(persistida.getEstado()).isEqualTo(EstadoPublicacion.PENDIENTE_REVISION);
        assertThat(persistida.getCategoria()).isSameAs(categoriaCorregida);
        assertThat(persistida.getSubcategoria()).isSameAs(subcategoriaCorregida);
        assertThat(persistida.getPrecio()).isEqualTo(150000L);
        assertThat(persistida.getStock()).isEqualTo(5);
    }

    /**
     * Verifica que corregir la categoría/subcategoría desde RECHAZADA reenvíe la publicación a
     * PENDIENTE_REVISION (Story 3, spec.md: editar una rechazada la reenvía a revisión).
     */
    @Test
    @DisplayName("Debe corregir categoría/subcategoría desde RECHAZADA y reenviar a PENDIENTE_REVISION")
    void corregirPublicacion_DesdeRechazada_CorrigeCategoriaYReenviaAPendienteRevision() {
        Usuario duenio = new Usuario("vendedor-rechazada@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now());
        duenio.setId(1L);

        Categoria categoriaOriginal = new Categoria("Electrónica");
        categoriaOriginal.setId(10L);
        Subcategoria subcategoriaOriginal = new Subcategoria(categoriaOriginal, "Laptops");
        subcategoriaOriginal.setId(100L);

        Publicacion publicacion = new Publicacion(duenio, categoriaOriginal, subcategoriaOriginal, 150000L, 5, "Laptop");
        publicacion.setId(50L);
        publicacion.setEstado(EstadoPublicacion.RECHAZADA);

        Categoria categoriaCorregida = new Categoria("Hogar");
        categoriaCorregida.setId(20L);
        Subcategoria subcategoriaCorregida = new Subcategoria(categoriaCorregida, "Muebles");
        subcategoriaCorregida.setId(200L);

        when(publicacionRepository.findById(50L)).thenReturn(Optional.of(publicacion));
        when(categoriaRepository.findById(20L)).thenReturn(Optional.of(categoriaCorregida));
        when(subcategoriaRepository.findById(200L)).thenReturn(Optional.of(subcategoriaCorregida));
        when(publicacionRepository.save(any(Publicacion.class))).thenAnswer(i -> i.getArgument(0));

        Publicacion resultado = publicacionService.corregirPublicacion(50L, 1L, 20L, 200L);

        assertThat(resultado.getEstado()).isEqualTo(EstadoPublicacion.PENDIENTE_REVISION);
        assertThat(resultado.getCategoria()).isSameAs(categoriaCorregida);
        assertThat(resultado.getSubcategoria()).isSameAs(subcategoriaCorregida);
        verify(publicacionRepository).save(publicacion);
    }

    /**
     * Verifica que corregir la categoría/subcategoría de una publicación APROBADA sea rechazado
     * (Story 3, spec.md: en aprobada la categoría/subcategoría queda bloqueada).
     */
    @Test
    @DisplayName("Debe lanzar CategoriaPublicacionInmutableException al corregir categoría desde APROBADA")
    void corregirPublicacion_DesdeAprobada_LanzaCategoriaPublicacionInmutableException() {
        Usuario duenio = new Usuario("vendedor-aprobada@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now());
        duenio.setId(1L);
        Categoria categoria = new Categoria("Electrónica");
        categoria.setId(10L);
        Subcategoria subcategoria = new Subcategoria(categoria, "Laptops");
        subcategoria.setId(100L);

        Publicacion publicacion = new Publicacion(duenio, categoria, subcategoria, 150000L, 5, "Laptop");
        publicacion.setId(51L);
        publicacion.setEstado(EstadoPublicacion.APROBADA);

        when(publicacionRepository.findById(51L)).thenReturn(Optional.of(publicacion));

        assertThatThrownBy(() -> publicacionService.corregirPublicacion(51L, 1L, 20L, 200L))
            .isInstanceOf(CategoriaPublicacionInmutableException.class)
            .hasMessageContaining("No se permite modificar la categoría o subcategoría");

        verify(publicacionRepository, never()).save(any(Publicacion.class));
    }

    /**
     * Verifica que corregir desde PENDIENTE_REVISION sea rechazado: la publicación está en tránsito
     * de moderación y no admite corrección de categoría/subcategoría (defensivo, Story 3).
     */
    @Test
    @DisplayName("Debe lanzar TransicionEstadoInvalidaException al corregir desde PENDIENTE_REVISION")
    void corregirPublicacion_DesdePendienteRevision_LanzaTransicionEstadoInvalidaException() {
        Usuario duenio = new Usuario("vendedor-pendiente@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now());
        duenio.setId(1L);
        Categoria categoria = new Categoria("Electrónica");
        categoria.setId(10L);
        Subcategoria subcategoria = new Subcategoria(categoria, "Laptops");
        subcategoria.setId(100L);

        Publicacion publicacion = new Publicacion(duenio, categoria, subcategoria, 150000L, 5, "Laptop");
        publicacion.setId(52L);
        publicacion.setEstado(EstadoPublicacion.PENDIENTE_REVISION);

        when(publicacionRepository.findById(52L)).thenReturn(Optional.of(publicacion));

        assertThatThrownBy(() -> publicacionService.corregirPublicacion(52L, 1L, 20L, 200L))
            .isInstanceOf(TransicionEstadoInvalidaException.class)
            .hasMessageContaining("Transición de estado no permitida");

        verify(publicacionRepository, never()).save(any(Publicacion.class));
    }

    /**
     * Verifica que corregir desde OCULTA sea rechazado (defensivo; la publicación oculta no está en
     * tránsito de moderación y no admite reenvío a revisión).
     */
    @Test
    @DisplayName("Debe lanzar TransicionEstadoInvalidaException al corregir desde OCULTA")
    void corregirPublicacion_DesdeOculta_LanzaTransicionEstadoInvalidaException() {
        Usuario duenio = new Usuario("vendedor-oculta@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now());
        duenio.setId(1L);
        Categoria categoria = new Categoria("Electrónica");
        categoria.setId(10L);
        Subcategoria subcategoria = new Subcategoria(categoria, "Laptops");
        subcategoria.setId(100L);

        Publicacion publicacion = new Publicacion(duenio, categoria, subcategoria, 150000L, 5, "Laptop");
        publicacion.setId(53L);
        publicacion.setEstado(EstadoPublicacion.OCULTA);

        when(publicacionRepository.findById(53L)).thenReturn(Optional.of(publicacion));

        assertThatThrownBy(() -> publicacionService.corregirPublicacion(53L, 1L, 20L, 200L))
            .isInstanceOf(TransicionEstadoInvalidaException.class)
            .hasMessageContaining("Transición de estado no permitida");

        verify(publicacionRepository, never()).save(any(Publicacion.class));
    }

    /**
     * Verifica que corregir una publicación ajena lance NoEsElPropietarioException (Story 3: solo el
     * vendedor dueño corrige su publicación).
     */
    @Test
    @DisplayName("Debe lanzar NoEsElPropietarioException al corregir una publicación que no es del usuario")
    void corregirPublicacion_NoPropietario_LanzaNoEsElPropietarioException() {
        Usuario duenio = new Usuario("vendedor-duenio@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now());
        duenio.setId(1L);
        Categoria categoria = new Categoria("Electrónica");
        categoria.setId(10L);
        Subcategoria subcategoria = new Subcategoria(categoria, "Laptops");
        subcategoria.setId(100L);

        Publicacion publicacion = new Publicacion(duenio, categoria, subcategoria, 150000L, 5, "Laptop");
        publicacion.setId(54L);
        publicacion.setEstado(EstadoPublicacion.CAMBIOS_SOLICITADOS);

        when(publicacionRepository.findById(54L)).thenReturn(Optional.of(publicacion));

        assertThatThrownBy(() -> publicacionService.corregirPublicacion(54L, 999L, 20L, 200L))
            .isInstanceOf(NoEsElPropietarioException.class)
            .hasMessageContaining("no es el propietario");

        verify(publicacionRepository, never()).save(any(Publicacion.class));
    }

    /**
     * Verifica que corregir con una categoría inexistente lance CategoriaNoEncontradaException
     * (misma validación de existencia que crearPublicacion, Stories 1 y 3).
     */
    @Test
    @DisplayName("Debe lanzar CategoriaNoEncontradaException al corregir con categoría inexistente")
    void corregirPublicacion_CategoriaInexistente_LanzaCategoriaNoEncontradaException() {
        Usuario duenio = new Usuario("vendedor-cat@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now());
        duenio.setId(1L);
        Categoria categoria = new Categoria("Electrónica");
        categoria.setId(10L);
        Subcategoria subcategoria = new Subcategoria(categoria, "Laptops");
        subcategoria.setId(100L);

        Publicacion publicacion = new Publicacion(duenio, categoria, subcategoria, 150000L, 5, "Laptop");
        publicacion.setId(55L);
        publicacion.setEstado(EstadoPublicacion.CAMBIOS_SOLICITADOS);

        when(publicacionRepository.findById(55L)).thenReturn(Optional.of(publicacion));
        when(categoriaRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> publicacionService.corregirPublicacion(55L, 1L, 999L, 200L))
            .isInstanceOf(CategoriaNoEncontradaException.class)
            .hasMessageContaining("Categoría raíz con ID 999 no encontrada");

        verify(publicacionRepository, never()).save(any(Publicacion.class));
    }

    /**
     * Verifica que corregir con una subcategoría inexistente lance CategoriaNoEncontradaException
     * (misma validación de existencia que crearPublicacion).
     */
    @Test
    @DisplayName("Debe lanzar CategoriaNoEncontradaException al corregir con subcategoría inexistente")
    void corregirPublicacion_SubcategoriaInexistente_LanzaCategoriaNoEncontradaException() {
        Usuario duenio = new Usuario("vendedor-subcat@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now());
        duenio.setId(1L);
        Categoria categoria = new Categoria("Electrónica");
        categoria.setId(10L);
        Subcategoria subcategoria = new Subcategoria(categoria, "Laptops");
        subcategoria.setId(100L);

        Publicacion publicacion = new Publicacion(duenio, categoria, subcategoria, 150000L, 5, "Laptop");
        publicacion.setId(56L);
        publicacion.setEstado(EstadoPublicacion.CAMBIOS_SOLICITADOS);

        Categoria categoriaCorregida = new Categoria("Hogar");
        categoriaCorregida.setId(20L);

        when(publicacionRepository.findById(56L)).thenReturn(Optional.of(publicacion));
        when(categoriaRepository.findById(20L)).thenReturn(Optional.of(categoriaCorregida));
        when(subcategoriaRepository.findById(888L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> publicacionService.corregirPublicacion(56L, 1L, 20L, 888L))
            .isInstanceOf(CategoriaNoEncontradaException.class)
            .hasMessageContaining("Subcategoría con ID 888 no encontrada");

        verify(publicacionRepository, never()).save(any(Publicacion.class));
    }

    /**
     * Verifica que corregir con una subcategoría que pertenece a otra categoría lance
     * SubcategoriaNoPerteneceACategoriaException (misma validación padre-hijo que crearPublicacion).
     */
    @Test
    @DisplayName("Debe lanzar SubcategoriaNoPerteneceACategoriaException al corregir con subcategoría de otra categoría")
    void corregirPublicacion_SubcategoriaNoPerteneceACategoria_LanzaSubcategoriaNoPerteneceACategoriaException() {
        Usuario duenio = new Usuario("vendedor-padre@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now());
        duenio.setId(1L);
        Categoria categoria = new Categoria("Electrónica");
        categoria.setId(10L);
        Subcategoria subcategoria = new Subcategoria(categoria, "Laptops");
        subcategoria.setId(100L);

        Publicacion publicacion = new Publicacion(duenio, categoria, subcategoria, 150000L, 5, "Laptop");
        publicacion.setId(57L);
        publicacion.setEstado(EstadoPublicacion.CAMBIOS_SOLICITADOS);

        Categoria categoriaCorregida = new Categoria("Hogar");
        categoriaCorregida.setId(20L);
        Categoria otraCategoria = new Categoria("Ropa");
        otraCategoria.setId(30L);
        // Subcategoría "Camisas" pertenece a Ropa (30L), pero se intenta asociar con Hogar (20L)
        Subcategoria subcategoriaRopa = new Subcategoria(otraCategoria, "Camisas");
        subcategoriaRopa.setId(300L);

        when(publicacionRepository.findById(57L)).thenReturn(Optional.of(publicacion));
        when(categoriaRepository.findById(20L)).thenReturn(Optional.of(categoriaCorregida));
        when(subcategoriaRepository.findById(300L)).thenReturn(Optional.of(subcategoriaRopa));

        assertThatThrownBy(() -> publicacionService.corregirPublicacion(57L, 1L, 20L, 300L))
            .isInstanceOf(SubcategoriaNoPerteneceACategoriaException.class)
            .hasMessageContaining("no pertenece a la categoría con ID 20");

        verify(publicacionRepository, never()).save(any(Publicacion.class));
    }

    /**
     * Verifica que la corrección válida no persista evento ni notificación nuevos: el log append-only
     * publicacion_eventos solo admite el tipo CREADA y la corrección no genera avisos (constitución,
     * principio 2; límite de alcance de PHA06TSK03).
     */
    @Test
    @DisplayName("Debe corregir sin persistir eventos ni notificaciones nuevas")
    void corregirPublicacion_Valida_NoPersisteEventoNiNotificacionNuevos() {
        Usuario duenio = new Usuario("vendedor-sinaviso@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now());
        duenio.setId(1L);
        Categoria categoriaOriginal = new Categoria("Electrónica");
        categoriaOriginal.setId(10L);
        Subcategoria subcategoriaOriginal = new Subcategoria(categoriaOriginal, "Laptops");
        subcategoriaOriginal.setId(100L);

        Publicacion publicacion = new Publicacion(duenio, categoriaOriginal, subcategoriaOriginal, 150000L, 5, "Laptop");
        publicacion.setId(58L);
        publicacion.setEstado(EstadoPublicacion.CAMBIOS_SOLICITADOS);

        Categoria categoriaCorregida = new Categoria("Hogar");
        categoriaCorregida.setId(20L);
        Subcategoria subcategoriaCorregida = new Subcategoria(categoriaCorregida, "Muebles");
        subcategoriaCorregida.setId(200L);

        when(publicacionRepository.findById(58L)).thenReturn(Optional.of(publicacion));
        when(categoriaRepository.findById(20L)).thenReturn(Optional.of(categoriaCorregida));
        when(subcategoriaRepository.findById(200L)).thenReturn(Optional.of(subcategoriaCorregida));
        when(publicacionRepository.save(any(Publicacion.class))).thenAnswer(i -> i.getArgument(0));

        Publicacion resultado = publicacionService.corregirPublicacion(58L, 1L, 20L, 200L);

        assertThat(resultado.getEstado()).isEqualTo(EstadoPublicacion.PENDIENTE_REVISION);
        verify(publicacionEventoRepository, never()).save(any());
        verify(notificacionRepository, never()).save(any());
    }

    /**
     * Verifica que el dueño pueda eliminar definitivamente una publicación RECHAZADA y que el
     * repositorio reciba exactamente esa entidad (Story 3, spec.md).
     */
    @Test
    @DisplayName("Debe eliminar definitivamente una publicación RECHAZADA invocando delete con esa entidad")
    void eliminarPublicacion_Rechazada_EliminaDefinitivamente() {
        Usuario duenio = new Usuario("vendedor-elimina@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now());
        duenio.setId(1L);
        Categoria categoria = new Categoria("Electrónica");
        categoria.setId(10L);
        Subcategoria subcategoria = new Subcategoria(categoria, "Laptops");
        subcategoria.setId(100L);

        Publicacion publicacion = new Publicacion(duenio, categoria, subcategoria, 150000L, 5, "Laptop");
        publicacion.setId(60L);
        publicacion.setEstado(EstadoPublicacion.RECHAZADA);

        when(publicacionRepository.findByIdWithLock(60L)).thenReturn(Optional.of(publicacion));

        publicacionService.eliminarPublicacion(60L, 1L);

        verify(publicacionRepository).delete(publicacion);
    }

    /**
     * Verifica que eliminar una publicación propia sea posible en CUALQUIER estado
     * (APROBADA, PENDIENTE_REVISION, OCULTA, CAMBIOS_SOLICITADOS, RECHAZADA)
     * y que el repositorio reciba delete con la entidad correspondiente.
     * Historia: corrección de Lino 2026-08-20 extendiendo Story 3 a cualquier estado.
     */
    @Test
    @DisplayName("Debe eliminar definitivamente una publicación en CUALQUIER estado (APROBADA, PENDIENTE_REVISION, OCULTA, CAMBIOS_SOLICITADOS, RECHAZADA)")
    void eliminarPublicacion_CualquierEstado_EliminaDefinitivamente() {
        Usuario duenio = new Usuario("vendedor-elimina-cualquier@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now());
        duenio.setId(1L);
        Categoria categoria = new Categoria("Electrónica");
        categoria.setId(10L);
        Subcategoria subcategoria = new Subcategoria(categoria, "Laptops");
        subcategoria.setId(100L);

        // APROBADA
        Publicacion pAprobada = new Publicacion(duenio, categoria, subcategoria, 150000L, 5, "Laptop");
        pAprobada.setId(61L);
        pAprobada.setEstado(EstadoPublicacion.APROBADA);

        // PENDIENTE_REVISION
        Publicacion pPendiente = new Publicacion(duenio, categoria, subcategoria, 150000L, 5, "Laptop");
        pPendiente.setId(62L);
        pPendiente.setEstado(EstadoPublicacion.PENDIENTE_REVISION);

        // OCULTA
        Publicacion pOculta = new Publicacion(duenio, categoria, subcategoria, 150000L, 0, "Laptop");
        pOculta.setId(65L);
        pOculta.setEstado(EstadoPublicacion.OCULTA);

        // CAMBIOS_SOLICITADOS
        Publicacion pCambios = new Publicacion(duenio, categoria, subcategoria, 150000L, 5, "Laptop");
        pCambios.setId(63L);
        pCambios.setEstado(EstadoPublicacion.CAMBIOS_SOLICITADOS);

        // RECHAZADA
        Publicacion pRechazada = new Publicacion(duenio, categoria, subcategoria, 150000L, 5, "Laptop");
        pRechazada.setId(64L);
        pRechazada.setEstado(EstadoPublicacion.RECHAZADA);

        when(publicacionRepository.findByIdWithLock(61L)).thenReturn(Optional.of(pAprobada));
        when(publicacionRepository.findByIdWithLock(62L)).thenReturn(Optional.of(pPendiente));
        when(publicacionRepository.findByIdWithLock(63L)).thenReturn(Optional.of(pCambios));
        when(publicacionRepository.findByIdWithLock(64L)).thenReturn(Optional.of(pRechazada));
        when(publicacionRepository.findByIdWithLock(65L)).thenReturn(Optional.of(pOculta));

        // Eliminar en cada estado - no debe lanzar excepción
        publicacionService.eliminarPublicacion(61L, 1L);
        publicacionService.eliminarPublicacion(62L, 1L);
        publicacionService.eliminarPublicacion(63L, 1L);
        publicacionService.eliminarPublicacion(64L, 1L);
        publicacionService.eliminarPublicacion(65L, 1L);

        // Verificar que delete se invocó para cada publicación
        verify(publicacionRepository).delete(pAprobada);
        verify(publicacionRepository).delete(pPendiente);
        verify(publicacionRepository).delete(pCambios);
        verify(publicacionRepository).delete(pRechazada);
        verify(publicacionRepository).delete(pOculta);
    }

    /**
     * Verifica que eliminar una publicación ajena lance NoEsElPropietarioException sin invocar delete
     * (Story 3: solo el vendedor dueño elimina su publicación rechazada).
     */
    @Test
    @DisplayName("Debe lanzar NoEsElPropietarioException al eliminar una publicación que no es del usuario")
    void eliminarPublicacion_NoPropietario_LanzaNoEsElPropietarioExceptionYNoElimina() {
        Usuario duenio = new Usuario("vendedor-ajeno@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now());
        duenio.setId(1L);
        Categoria categoria = new Categoria("Electrónica");
        categoria.setId(10L);
        Subcategoria subcategoria = new Subcategoria(categoria, "Laptops");
        subcategoria.setId(100L);

        Publicacion publicacion = new Publicacion(duenio, categoria, subcategoria, 150000L, 5, "Laptop");
        publicacion.setId(64L);
        publicacion.setEstado(EstadoPublicacion.RECHAZADA);

        when(publicacionRepository.findByIdWithLock(64L)).thenReturn(Optional.of(publicacion));

        assertThatThrownBy(() -> publicacionService.eliminarPublicacion(64L, 999L))
            .isInstanceOf(NoEsElPropietarioException.class)
            .hasMessageContaining("no es el propietario");

        verify(publicacionRepository, never()).delete(any(Publicacion.class));
    }

    /**
     * Verifica que eliminar una publicación con al menos una transacción asociada lance
     * {@link PublicacionConTransaccionesException} y NO invoque {@code delete}
     * (decisión de Lino 2026-08-23, plan.md "PHA12 — Eliminación de publicaciones con
     * transacciones asociadas"; corrige la violación de la FK {@code fk_transacciones_publicacion} V7).
     */
    @Test
    @DisplayName("Debe lanzar PublicacionConTransaccionesException al eliminar una publicación con transacción asociada y NO invocar delete")
    void eliminarPublicacion_ConTransaccionAsociada_LanzaPublicacionConTransaccionesExceptionYNoElimina() {
        Usuario duenio = new Usuario("vendedor-contransaccion@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now());
        duenio.setId(1L);
        Categoria categoria = new Categoria("Electrónica");
        categoria.setId(10L);
        Subcategoria subcategoria = new Subcategoria(categoria, "Laptops");
        subcategoria.setId(100L);

        Publicacion publicacion = new Publicacion(duenio, categoria, subcategoria, 150000L, 5, "Laptop");
        publicacion.setId(70L);
        publicacion.setEstado(EstadoPublicacion.APROBADA);

        when(publicacionRepository.findByIdWithLock(70L)).thenReturn(Optional.of(publicacion));
        when(transaccionRepository.existsByPublicacionId(70L)).thenReturn(true);

        assertThatThrownBy(() -> publicacionService.eliminarPublicacion(70L, 1L))
            .isInstanceOf(PublicacionConTransaccionesException.class)
            .hasMessageContaining("al menos una transacción asociada");

        verify(publicacionRepository, never()).delete(any(Publicacion.class));
    }

    /**
     * Verifica que eliminar una publicación SIN transacciones asociadas siga eliminando
     * (regresión PHA11: cualquier estado es eliminable) y que la consulta previa de existencia
     * de transacciones se ejecute exactamente una vez antes del {@code delete}.
     */
    @Test
    @DisplayName("Debe eliminar definitivamente una publicación sin transacciones asociadas (regresión PHA11)")
    void eliminarPublicacion_SinTransacciones_EliminaDefinitivamente() {
        Usuario duenio = new Usuario("vendedor-sintransaccion@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now());
        duenio.setId(1L);
        Categoria categoria = new Categoria("Electrónica");
        categoria.setId(10L);
        Subcategoria subcategoria = new Subcategoria(categoria, "Laptops");
        subcategoria.setId(100L);

        Publicacion publicacion = new Publicacion(duenio, categoria, subcategoria, 150000L, 5, "Laptop");
        publicacion.setId(71L);
        publicacion.setEstado(EstadoPublicacion.RECHAZADA);

        when(publicacionRepository.findByIdWithLock(71L)).thenReturn(Optional.of(publicacion));
        when(transaccionRepository.existsByPublicacionId(71L)).thenReturn(false);

        publicacionService.eliminarPublicacion(71L, 1L);

        verify(transaccionRepository).existsByPublicacionId(71L);
        verify(publicacionRepository).delete(publicacion);
    }

    /**
     * Verifica que la eliminación cargue la publicación mediante la consulta bajo lock pesimista
     * {@code findByIdWithLock} y NO mediante el {@code findById} simple (PHA12TSK07; decisión de
     * Lino 2026-08-23, plan.md "PHA12", fila "Endurecimiento TOCTOU compra-vs-delete"): cerrar la
     * ventana entre {@code existsByPublicacionId} y {@code delete} exige que la MISMA operación que
     * evalúa y elimina sea la que adquiere el {@code PESSIMISTIC_WRITE} sobre la fila padre.
     * Verifica además con {@link InOrder} el orden interno lock → consulta de transacciones → delete.
     */
    @Test
    @DisplayName("Debe cargar la publicación con findByIdWithLock (nunca findById simple) antes de evaluar transacciones y eliminar")
    void eliminarPublicacion_FlujoExitoso_UsaCargaBajoLockYNoFindById() {
        Usuario duenio = new Usuario("vendedor-bajolock@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now());
        duenio.setId(1L);
        Categoria categoria = new Categoria("Electrónica");
        categoria.setId(10L);
        Subcategoria subcategoria = new Subcategoria(categoria, "Laptops");
        subcategoria.setId(100L);

        Publicacion publicacion = new Publicacion(duenio, categoria, subcategoria, 150000L, 5, "Laptop");
        publicacion.setId(72L);
        publicacion.setEstado(EstadoPublicacion.APROBADA);

        when(publicacionRepository.findByIdWithLock(72L)).thenReturn(Optional.of(publicacion));
        when(transaccionRepository.existsByPublicacionId(72L)).thenReturn(false);

        publicacionService.eliminarPublicacion(72L, 1L);

        verify(publicacionRepository).findByIdWithLock(72L);
        verify(publicacionRepository, never()).findById(72L);

        InOrder orden = inOrder(publicacionRepository, transaccionRepository);
        orden.verify(publicacionRepository).findByIdWithLock(72L);
        orden.verify(transaccionRepository).existsByPublicacionId(72L);
        orden.verify(publicacionRepository).delete(publicacion);
    }

    /**
     * Verifica que intentar eliminar una publicación inexistente lance {@link PublicacionNoEncontradaException}
     * desde la carga bajo lock (Optional vacío), sin consultar transacciones ni invocar delete
     * (PHA12TSK07: el contrato observable 404 se conserva con el nuevo mecanismo de carga).
     */
    @Test
    @DisplayName("Debe lanzar PublicacionNoEncontradaException al eliminar una publicación inexistente (carga bajo lock vacía)")
    void eliminarPublicacion_Inexistente_LanzaPublicacionNoEncontradaExceptionSinEliminar() {
        when(publicacionRepository.findByIdWithLock(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> publicacionService.eliminarPublicacion(99L, 1L))
            .isInstanceOf(PublicacionNoEncontradaException.class)
            .hasMessageContaining("no encontrada");

        verify(transaccionRepository, never()).existsByPublicacionId(99L);
        verify(publicacionRepository, never()).delete(any(Publicacion.class));
    }
}
