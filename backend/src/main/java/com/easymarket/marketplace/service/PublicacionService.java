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
import com.easymarket.marketplace.model.Subcategoria;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.CategoriaRepository;
import com.easymarket.marketplace.repository.PublicacionRepository;
import com.easymarket.marketplace.repository.SubcategoriaRepository;
import com.easymarket.marketplace.repository.UsuarioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Servicio de dominio para la creación, consulta y máquina de estados de publicaciones (Stories 1, 2 y 3, spec.md).
 *
 * <p>Aplica las validaciones de negocio previas al guardado y gestiona las transiciones de estado:
 * <ul>
 *   <li>Validación de precio estrictamente positivo (monto entero en centavos > 0).</li>
 *   <li>Validación de stock inicial de al menos 1 unidad (stock >= 1).</li>
 *   <li>Validación de existencia del usuario vendedor, categoría y subcategoría.</li>
 *   <li>Validación de jerarquía padre-hijo (garantiza que la subcategoría pertenezca a la categoría indicada).</li>
 *   <li>Asignación por defecto del estado {@link EstadoPublicacion#PENDIENTE_REVISION}.</li>
 *   <li>Transiciones controladas conforme a la máquina de estados oficial.</li>
 *   <li>Edición de publicaciones aprobadas (modifica precio, stock &ge; 0 y descripción; bloquea categoría/subcategoría).</li>
 * </ul>
 * </p>
 */
@Service
public class PublicacionService {

    private static final Logger logger = LoggerFactory.getLogger(PublicacionService.class);

    private final PublicacionRepository publicacionRepository;
    private final UsuarioRepository usuarioRepository;
    private final CategoriaRepository categoriaRepository;
    private final SubcategoriaRepository subcategoriaRepository;

    /**
     * Construye el servicio inyectando los repositorios necesarios.
     *
     * @param publicacionRepository repositorio JPA de publicaciones
     * @param usuarioRepository repositorio JPA de usuarios
     * @param categoriaRepository repositorio JPA de categorías
     * @param subcategoriaRepository repositorio JPA de subcategorías
     */
    public PublicacionService(PublicacionRepository publicacionRepository,
                              UsuarioRepository usuarioRepository,
                              CategoriaRepository categoriaRepository,
                              SubcategoriaRepository subcategoriaRepository) {
        this.publicacionRepository = publicacionRepository;
        this.usuarioRepository = usuarioRepository;
        this.categoriaRepository = categoriaRepository;
        this.subcategoriaRepository = subcategoriaRepository;
    }

    /**
     * Crea una nueva publicación en el sistema validando todas las reglas de negocio de Story 1.
     *
     * @param usuarioId ID del usuario vendedor
     * @param categoriaId ID de la categoría raíz
     * @param subcategoriaId ID de la subcategoría
     * @param precio precio en centavos (entero > 0)
     * @param stock cantidad inicial disponible (entero >= 1)
     * @param descripcion descripción del producto
     * @return la entidad {@link Publicacion} creada y persistida con estado 'pendiente_revisión'
     * @throws PrecioInvalidoException si precio <= 0
     * @throws StockInvalidoException si stock < 1
     * @throws UsuarioNoEncontradoException si el usuario no existe
     * @throws CategoriaNoEncontradaException si la categoría o subcategoría no existen
     * @throws SubcategoriaNoPerteneceACategoriaException si la subcategoría no pertenece a la categoría especificada
     */
    @Transactional
    public Publicacion crearPublicacion(Long usuarioId, Long categoriaId, Long subcategoriaId, long precio, int stock, String descripcion) {
        if (precio <= 0) {
            throw new PrecioInvalidoException("El precio debe ser un monto entero positivo mayor a cero");
        }
        if (stock < 1) {
            throw new StockInvalidoException("El stock inicial debe ser al menos de 1 unidad");
        }

        Usuario usuario = usuarioRepository.findById(usuarioId)
            .orElseThrow(() -> new UsuarioNoEncontradoException("Usuario vendedor con ID " + usuarioId + " no encontrado"));

        Categoria categoria = categoriaRepository.findById(categoriaId)
            .orElseThrow(() -> new CategoriaNoEncontradaException("Categoría raíz con ID " + categoriaId + " no encontrada"));

        Subcategoria subcategoria = subcategoriaRepository.findById(subcategoriaId)
            .orElseThrow(() -> new CategoriaNoEncontradaException("Subcategoría con ID " + subcategoriaId + " no encontrada"));

        if (!subcategoria.getCategoria().getId().equals(categoriaId)) {
            throw new SubcategoriaNoPerteneceACategoriaException(
                "La subcategoría '" + subcategoria.getNombre() + "' (ID " + subcategoriaId + ") no pertenece a la categoría con ID " + categoriaId
            );
        }

        Publicacion publicacion = new Publicacion(usuario, categoria, subcategoria, precio, stock, descripcion);
        return publicacionRepository.save(publicacion);
    }

    /**
     * Obtiene una publicación por su ID único.
     *
     * @param id ID de la publicación
     * @return la entidad {@link Publicacion} encontrada
     * @throws PublicacionNoEncontradaException si la publicación no existe
     */
    @Transactional(readOnly = true)
    public Publicacion obtenerPublicacionPorId(Long id) {
        return publicacionRepository.findById(id)
            .orElseThrow(() -> new PublicacionNoEncontradaException("Publicación con ID " + id + " no encontrada"));
    }

    /**
     * Realiza el cambio de estado de una publicación conforme a la máquina de estados oficial (Stories 2 y 3, spec.md).
     *
     * <p><strong>Matriz de Transiciones Válidas (Stories 2 y 3):</strong>
     * <ul>
     *   <li>{@link EstadoPublicacion#PENDIENTE_REVISION} &rarr; {@link EstadoPublicacion#APROBADA} (Moderación: Aprobación por admin).</li>
     *   <li>{@link EstadoPublicacion#PENDIENTE_REVISION} &rarr; {@link EstadoPublicacion#CAMBIOS_SOLICITADOS} (Moderación: Solicitud de cambios con motivo).</li>
     *   <li>{@link EstadoPublicacion#PENDIENTE_REVISION} &rarr; {@link EstadoPublicacion#RECHAZADA} (Moderación: Rechazo por producto prohibido con motivo).</li>
     *   <li>{@link EstadoPublicacion#CAMBIOS_SOLICITADOS} &rarr; {@link EstadoPublicacion#PENDIENTE_REVISION} (Vendedor: Reenvío tras corregir categoría/subcategoría).</li>
     *   <li>{@link EstadoPublicacion#RECHAZADA} &rarr; {@link EstadoPublicacion#PENDIENTE_REVISION} (Vendedor: Reenvío tras edición).</li>
     * </ul>
     * Cualquier otra combinación entre estado actual y nuevo estado se considera inválida,
     * incluyendo intentar transicionar al mismo estado en que ya se encuentra la publicación.
     * La transición {@link EstadoPublicacion#APROBADA} &harr; {@link EstadoPublicacion#OCULTA} pertenece a PHA02TSK07 (gestión de stock)
     * y no está habilitada en este servicio.
     * </p>
     *
     * @param publicacionId ID de la publicación a actualizar
     * @param nuevoEstado nuevo estado al que se desea transicionar
     * @param motivo motivo opcional u obligatorio según la transición (ej. requerido en RECHAZADA o CAMBIOS_SOLICITADOS)
     * @return la entidad {@link Publicacion} actualizada y persistida
     * @throws PublicacionNoEncontradaException si la publicación no existe
     * @throws TransicionEstadoInvalidaException si la transición desde el estado actual no está permitida
     * @throws MotivoRequeridoException si el nuevo estado es RECHAZADA o CAMBIOS_SOLICITADOS y el motivo es nulo o en blanco
     */
    @Transactional
    public Publicacion cambiarEstado(Long publicacionId, EstadoPublicacion nuevoEstado, String motivo) {
        Publicacion publicacion = obtenerPublicacionPorId(publicacionId);
        EstadoPublicacion estadoActual = publicacion.getEstado();

        if (nuevoEstado == EstadoPublicacion.RECHAZADA || nuevoEstado == EstadoPublicacion.CAMBIOS_SOLICITADOS) {
            if (motivo == null || motivo.isBlank()) {
                throw new MotivoRequeridoException("Se requiere un motivo explícito para pasar a estado " + nuevoEstado);
            }
        }

        boolean esValida = esTransicionValida(estadoActual, nuevoEstado);
        if (!esValida) {
            throw new TransicionEstadoInvalidaException(
                "Transición de estado no permitida desde '" + estadoActual + "' hacia '" + nuevoEstado + "'"
            );
        }

        publicacion.setEstado(nuevoEstado);
        logger.info("Transición de publicación ID {}: {} -> {} | Motivo: {}", publicacionId, estadoActual, nuevoEstado, motivo);

        return publicacionRepository.save(publicacion);
    }

    private boolean esTransicionValida(EstadoPublicacion actual, EstadoPublicacion nuevo) {
        return switch (actual) {
            case PENDIENTE_REVISION -> nuevo == EstadoPublicacion.APROBADA ||
                                      nuevo == EstadoPublicacion.CAMBIOS_SOLICITADOS ||
                                      nuevo == EstadoPublicacion.RECHAZADA;
            case CAMBIOS_SOLICITADOS -> nuevo == EstadoPublicacion.PENDIENTE_REVISION;
            case RECHAZADA -> nuevo == EstadoPublicacion.PENDIENTE_REVISION;
            case APROBADA -> false;
            case OCULTA -> false;
        };
    }

    /**
     * Edita los campos permitidos (precio, stock, descripción) de una publicación en estado APROBADA (Story 3, spec.md).
     *
     * <p>Reglas de negocio:
     * <ul>
     *   <li>La publicación debe estar estrictamente en estado {@link EstadoPublicacion#APROBADA}.</li>
     *   <li>Bloquea cualquier intento de cambiar la categoría o subcategoría respecto a sus valores actuales.</li>
     *   <li>Valida precio estrictamente mayor a cero (centavos entero &gt; 0).</li>
     *   <li>Valida stock mayor o igual a cero (stock &ge; 0, permitiendo agotamiento de stock a 0).</li>
     *   <li>No altera el estado de la publicación ni dispara efectos secundarios de máquina de estados.</li>
     * </ul>
     * </p>
     *
     * @param publicacionId ID de la publicación a editar
     * @param nuevoPrecio nuevo precio en centavos (> 0) o null para conservar
     * @param nuevoStock nuevo stock (>= 0) o null para conservar
     * @param nuevaDescripcion nueva descripción del producto o null para conservar
     * @param categoriaIdEnviada ID de la categoría enviada en la solicitud
     * @param subcategoriaIdEnviada ID de la subcategoría enviada en la solicitud
     * @return la entidad {@link Publicacion} actualizada y persistida
     * @throws PublicacionNoEncontradaException si la publicación no existe
     * @throws EstadoPublicacionNoEditableException si la publicación no está en estado APROBADA
     * @throws CategoriaPublicacionInmutableException si la categoría o subcategoría difieren de las actuales
     * @throws PrecioInvalidoException si nuevoPrecio <= 0
     * @throws StockInvalidoException si nuevoStock < 0
     */
    /**
     * Edita los campos de una publicación por su vendedor propietario y gestiona la visibilidad por stock (Stories 3 y 10, spec.md).
     *
     * <p>Orquesta la edición de campos permitidos y la actualización atómica de visibilidad:
     * <ul>
     *   <li>Si el DTO contiene actualización de stock, invoca primero {@link #actualizarStock(Long, int)} para aplicar la regla de Story 10 (stock 0 transiciona a {@link EstadoPublicacion#OCULTA}).</li>
     *   <li>Posteriormente invoca {@link #editarPublicacion(Long, Long, Integer, String, Long, Long)} para actualizar precio y descripción en estado APROBADA (o conservando OCULTA si el stock pasó a 0).</li>
     * </ul>
     * </p>
     *
     * @param publicacionId ID de la publicación
     * @param usuarioId ID del usuario solicitante
     * @param nuevoPrecio nuevo precio opcional (> 0)
     * @param nuevoStock nuevo stock opcional (>= 0)
     * @param nuevaDescripcion nueva descripción opcional
     * @return la entidad {@link Publicacion} actualizada
     * @throws com.easymarket.marketplace.exception.NoEsElPropietarioException si el usuarioId no coincide con el dueño
     */
    @Transactional
    public Publicacion editarPublicacionDuenio(Long publicacionId,
                                               Long usuarioId,
                                               Long nuevoPrecio,
                                               Integer nuevoStock,
                                               String nuevaDescripcion) {
        Publicacion publicacion = obtenerPublicacionPorId(publicacionId);

        if (!publicacion.getUsuario().getId().equals(usuarioId)) {
            throw new com.easymarket.marketplace.exception.NoEsElPropietarioException(
                "El usuario con ID " + usuarioId + " no es el propietario de la publicación " + publicacionId
            );
        }

        if (nuevoStock != null) {
            actualizarStock(publicacionId, nuevoStock);
        }

        Publicacion aEditar = obtenerPublicacionPorId(publicacionId);
        if (aEditar.getEstado() == EstadoPublicacion.OCULTA) {
            if (nuevoPrecio != null) {
                if (nuevoPrecio <= 0) {
                    throw new PrecioInvalidoException("El precio debe ser un monto entero positivo mayor a cero");
                }
                aEditar.setPrecio(nuevoPrecio);
            }
            if (nuevaDescripcion != null) {
                aEditar.setDescripcion(nuevaDescripcion);
            }
            logger.info("Edición de publicación oculta ID {}: precio={}, stock={}, descripción editada", publicacionId, aEditar.getPrecio(), aEditar.getStock());
            return publicacionRepository.save(aEditar);
        }

        return editarPublicacion(publicacionId, nuevoPrecio, nuevoStock, nuevaDescripcion, null, null);
    }

    @Transactional
    public Publicacion editarPublicacion(Long publicacionId,
                                         Long nuevoPrecio,
                                         Integer nuevoStock,
                                         String nuevaDescripcion,
                                         Long categoriaIdEnviada,
                                         Long subcategoriaIdEnviada) {
        Publicacion publicacion = obtenerPublicacionPorId(publicacionId);

        if (publicacion.getEstado() != EstadoPublicacion.APROBADA) {
            throw new EstadoPublicacionNoEditableException(
                "Solo se pueden editar publicaciones en estado 'APROBADA'. Estado actual: " + publicacion.getEstado()
            );
        }

        if (categoriaIdEnviada != null && !categoriaIdEnviada.equals(publicacion.getCategoria().getId())) {
            throw new CategoriaPublicacionInmutableException(
                "No se permite modificar la categoría o subcategoría de una publicación aprobada"
            );
        }

        if (subcategoriaIdEnviada != null && !subcategoriaIdEnviada.equals(publicacion.getSubcategoria().getId())) {
            throw new CategoriaPublicacionInmutableException(
                "No se permite modificar la categoría o subcategoría de una publicación aprobada"
            );
        }

        if (nuevoPrecio != null) {
            if (nuevoPrecio <= 0) {
                throw new PrecioInvalidoException("El precio debe ser un monto entero positivo mayor a cero");
            }
            publicacion.setPrecio(nuevoPrecio);
        }

        if (nuevoStock != null) {
            if (nuevoStock < 0) {
                throw new StockInvalidoException("El stock no puede ser negativo");
            }
            publicacion.setStock(nuevoStock);
        }

        if (nuevaDescripcion != null) {
            publicacion.setDescripcion(nuevaDescripcion);
        }

        logger.info("Edición de publicación ID {}: precio={}, stock={}, descripción editada", publicacionId, publicacion.getPrecio(), publicacion.getStock());

        return publicacionRepository.save(publicacion);
    }

    /**
     * Actualiza el stock disponible de una publicación y gestiona las transiciones automáticas de ocultamiento/reaparición por agotamiento o reposición de stock (Story 10, spec.md).
     *
     * <p>Reglas de negocio:
     * <ul>
     *   <li>Valida que el nuevo stock no sea negativo (stock &ge; 0).</li>
     *   <li>Si el nuevo stock es 0 y la publicación está en estado {@link EstadoPublicacion#APROBADA}, transiciona automáticamente a {@link EstadoPublicacion#OCULTA}.</li>
     *   <li>Si el nuevo stock es &ge; 1 y la publicación está en estado {@link EstadoPublicacion#OCULTA}, transiciona automáticamente de vuelta a {@link EstadoPublicacion#APROBADA}.</li>
     *   <li>En cualquier otro estado (ej. {@link EstadoPublicacion#PENDIENTE_REVISION}, {@link EstadoPublicacion#CAMBIOS_SOLICITADOS}, {@link EstadoPublicacion#RECHAZADA}), actualiza el valor del stock sin alterar el estado.</li>
     *   <li>La actualización de stock y cambio de estado se realiza de forma atómica en una única transacción.</li>
     * </ul>
     * </p>
     *
     * @param publicacionId ID de la publicación
     * @param nuevoStock cantidad de stock a establecer (entero >= 0)
     * @return la entidad {@link Publicacion} actualizada y persistida
     * @throws StockInvalidoException si nuevoStock < 0
     * @throws PublicacionNoEncontradaException si la publicación no existe
     */
    @Transactional
    public Publicacion actualizarStock(Long publicacionId, int nuevoStock) {
        if (nuevoStock < 0) {
            throw new StockInvalidoException("El stock no puede ser negativo");
        }

        Publicacion publicacion = obtenerPublicacionPorId(publicacionId);
        EstadoPublicacion estadoActual = publicacion.getEstado();

        publicacion.setStock(nuevoStock);

        if (nuevoStock == 0 && estadoActual == EstadoPublicacion.APROBADA) {
            publicacion.setEstado(EstadoPublicacion.OCULTA);
            logger.info("Publicación ID {} pasa a estado OCULTA automáticamente por stock=0", publicacionId);
        } else if (nuevoStock >= 1 && estadoActual == EstadoPublicacion.OCULTA) {
            publicacion.setEstado(EstadoPublicacion.APROBADA);
            logger.info("Publicación ID {} pasa a estado APROBADA automáticamente por reposición de stock={}", publicacionId, nuevoStock);
        } else {
            logger.info("Stock actualizado para publicación ID {}: stock={}", publicacionId, nuevoStock);
        }

        return publicacionRepository.save(publicacion);
    }

    /**
     * Retorna todas las publicaciones cuyo estado coincida con el valor indicado (Story 2, spec.md).
     *
     * <p>La responsabilidad de autorización (por ejemplo, restricción del estado
     * {@link EstadoPublicacion#PENDIENTE_REVISION} al rol {@code ADMIN}) recae en la capa
     * de controlador; este método aplica el filtro de forma agnóstica al rol del solicitante.</p>
     *
     * @param estado estado de publicación a filtrar; no debe ser {@code null}
     * @return lista inmutable de publicaciones en ese estado; vacía si no hay ninguna
     */
    @Transactional(readOnly = true)
    public java.util.List<Publicacion> listarPorEstado(EstadoPublicacion estado) {
        return publicacionRepository.findByEstado(estado);
    }

    /**
     * Retorna todas las publicaciones de un usuario específico, ordenadas de la más reciente
     * a la más antigua (Stories 1, 3, spec.md).
     *
     * <p>Retorna publicaciones en todos los estados (incluyendo ocultas, pendientes, etc.)
     * ya que el propietario tiene derecho a ver todo su historial e inventario.</p>
     *
     * @param usuarioId ID del usuario vendedor
     * @return lista inmutable de publicaciones; vacía si no tiene ninguna
     */
    @Transactional(readOnly = true)
    public java.util.List<Publicacion> listarPorUsuario(Long usuarioId) {
        return publicacionRepository.findByUsuarioIdOrderByCreatedAtDescIdDesc(usuarioId);
    }
}


