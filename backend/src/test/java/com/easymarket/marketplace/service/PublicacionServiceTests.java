package com.easymarket.marketplace.service;

import com.easymarket.marketplace.exception.CategoriaNoEncontradaException;
import com.easymarket.marketplace.exception.CategoriaPublicacionInmutableException;
import com.easymarket.marketplace.exception.EstadoPublicacionNoEditableException;
import com.easymarket.marketplace.exception.MotivoRequeridoException;
import com.easymarket.marketplace.exception.PrecioInvalidoException;
import com.easymarket.marketplace.exception.PublicacionNoEncontradaException;
import com.easymarket.marketplace.exception.StockInvalidoException;
import com.easymarket.marketplace.exception.SubcategoriaNoPerteneceACategoriaException;
import com.easymarket.marketplace.exception.TransicionEstadoInvalidaException;
import com.easymarket.marketplace.exception.UsuarioNoEncontradoException;
import com.easymarket.marketplace.model.Categoria;
import com.easymarket.marketplace.model.EstadoPublicacion;
import com.easymarket.marketplace.model.Publicacion;
import com.easymarket.marketplace.model.Rol;
import com.easymarket.marketplace.model.Subcategoria;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.CategoriaRepository;
import com.easymarket.marketplace.repository.PublicacionRepository;
import com.easymarket.marketplace.repository.SubcategoriaRepository;
import com.easymarket.marketplace.repository.UsuarioRepository;
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

        Categoria categoria = new Categoria("Electrónica");
        categoria.setId(10L);

        Subcategoria subcategoria = new Subcategoria(categoria, "Laptops");
        subcategoria.setId(100L);

        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(usuario));
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

        Publicacion resultado = publicacionService.editarPublicacion(30L, 25000L, 0, "Nueva descripción", 10L, 100L);

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
        assertThatThrownBy(() -> publicacionService.editarPublicacion(31L, 10000L, 5, "Desc", 20L, 100L))
            .isInstanceOf(CategoriaPublicacionInmutableException.class)
            .hasMessageContaining("No se permite modificar la categoría o subcategoría");

        // Intento de modificar subcategoría (100L -> 200L)
        assertThatThrownBy(() -> publicacionService.editarPublicacion(31L, 10000L, 5, "Desc", 10L, 200L))
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

        assertThatThrownBy(() -> publicacionService.editarPublicacion(32L, 10000L, 5, "Desc", null, null))
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

        assertThatThrownBy(() -> publicacionService.editarPublicacion(33L, 0L, 5, "Desc", null, null))
            .isInstanceOf(PrecioInvalidoException.class)
            .hasMessageContaining("El precio debe ser un monto entero positivo mayor a cero");

        assertThatThrownBy(() -> publicacionService.editarPublicacion(33L, -500L, 5, "Desc", null, null))
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

        assertThatThrownBy(() -> publicacionService.editarPublicacion(34L, 10000L, -1, "Desc", null, null))
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
}


