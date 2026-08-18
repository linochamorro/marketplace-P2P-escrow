package com.easymarket.marketplace.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * Entidad JPA que representa una publicación en el marketplace.
 */
@Entity
@Table(name = "publicaciones")
public class Publicacion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "categoria_id", nullable = false)
    private Categoria categoria;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subcategoria_id", nullable = false)
    private Subcategoria subcategoria;

    @Column(name = "precio", nullable = false)
    private long precio;

    @Column(name = "stock", nullable = false)
    private int stock;

    @Convert(converter = EstadoPublicacionConverter.class)
    @Column(name = "estado", nullable = false, length = 50)
    private EstadoPublicacion estado;

    @Column(name = "descripcion", nullable = false, columnDefinition = "TEXT")
    private String descripcion;

    @Column(name = "imagen_filename")
    private String imagenFilename;

    @Column(name = "created_at", nullable = false)
    private ZonedDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private ZonedDateTime updatedAt;

    /**
     * Constructor por defecto requerido por JPA.
     */
    public Publicacion() {
    }

    /**
     * Constructor para la creación de una publicación con estado por defecto {@link EstadoPublicacion#PENDIENTE_REVISION}.
     *
     * @param usuario usuario vendedor propietario de la publicación
     * @param categoria categoría raíz a la que pertenece
     * @param subcategoria subcategoría vinculada (debe pertenecer a la categoría)
     * @param precio precio en centavos (entero > 0)
     * @param stock cantidad de unidades disponibles (entero >= 1 al crear)
     * @param descripcion descripción del artículo
     */
    public Publicacion(Usuario usuario, Categoria categoria, Subcategoria subcategoria, long precio, int stock, String descripcion) {
        this.usuario = usuario;
        this.categoria = categoria;
        this.subcategoria = subcategoria;
        this.precio = precio;
        this.stock = stock;
        this.descripcion = descripcion;
        this.estado = EstadoPublicacion.PENDIENTE_REVISION;
        this.createdAt = ZonedDateTime.now();
        this.updatedAt = ZonedDateTime.now();
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = ZonedDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = ZonedDateTime.now();
        }
        if (estado == null) {
            estado = EstadoPublicacion.PENDIENTE_REVISION;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = ZonedDateTime.now();
    }

    /**
     * Obtiene el ID único de la publicación.
     *
     * @return ID de la publicación
     */
    public Long getId() {
        return id;
    }

    /**
     * Establece el ID único de la publicación.
     *
     * @param id ID de la publicación
     */
    public void setId(Long id) {
        this.id = id;
    }

    /**
     * Obtiene el usuario vendedor.
     *
     * @return usuario vendedor
     */
    public Usuario getUsuario() {
        return usuario;
    }

    /**
     * Establece el usuario vendedor.
     *
     * @param usuario usuario vendedor
     */
    public void setUsuario(Usuario usuario) {
        this.usuario = usuario;
    }

    /**
     * Obtiene la categoría raíz.
     *
     * @return categoría raíz
     */
    public Categoria getCategoria() {
        return categoria;
    }

    /**
     * Establece la categoría raíz.
     *
     * @param categoria categoría raíz
     */
    public void setCategoria(Categoria categoria) {
        this.categoria = categoria;
    }

    /**
     * Obtiene la subcategoría.
     *
     * @return subcategoría
     */
    public Subcategoria getSubcategoria() {
        return subcategoria;
    }

    /**
     * Establece la subcategoría.
     *
     * @param subcategoria subcategoría
     */
    public void setSubcategoria(Subcategoria subcategoria) {
        this.subcategoria = subcategoria;
    }

    /**
     * Obtiene el precio en centavos (entero de 64 bits).
     *
     * @return precio en centavos
     */
    public long getPrecio() {
        return precio;
    }

    /**
     * Establece el precio en centavos.
     *
     * @param precio precio en centavos
     */
    public void setPrecio(long precio) {
        this.precio = precio;
    }

    /**
     * Obtiene el stock disponible.
     *
     * @return stock disponible
     */
    public int getStock() {
        return stock;
    }

    /**
     * Establece el stock disponible.
     *
     * @param stock stock disponible
     */
    public void setStock(int stock) {
        this.stock = stock;
    }

    /**
     * Obtiene el estado actual de la publicación.
     *
     * @return estado de la publicación
     */
    public EstadoPublicacion getEstado() {
        return estado;
    }

    /**
     * Establece el estado de la publicación.
     *
     * @param estado estado de la publicación
     */
    public void setEstado(EstadoPublicacion estado) {
        this.estado = estado;
    }

    /**
     * Obtiene la descripción detallada.
     *
     * @return descripción
     */
    public String getDescripcion() {
        return descripcion;
    }

    /**
     * Establece la descripción detallada.
     *
     * @param descripcion descripción
     */
    public void setDescripcion(String descripcion) {
        this.descripcion = descripcion;
    }

    /**
     * Obtiene el nombre del archivo de imagen de la publicación.
     *
     * <p>La BD conserva únicamente el nombre de archivo (sin prefijo de URL); el
     * frontend construye la ruta estática {@code /imagenes/publicaciones/{nombre}}.
     * {@code null} indica que la publicación aún no tiene imagen asignada.</p>
     *
     * @return nombre de archivo de imagen, o {@code null} si no tiene
     */
    public String getImagenFilename() {
        return imagenFilename;
    }

    /**
     * Establece el nombre del archivo de imagen de la publicación.
     *
     * @param imagenFilename nombre de archivo de imagen (sin prefijo de URL)
     */
    public void setImagenFilename(String imagenFilename) {
        this.imagenFilename = imagenFilename;
    }

    /**
     * Obtiene la fecha y hora de creación.
     *
     * @return marca temporal de creación
     */
    public ZonedDateTime getCreatedAt() {
        return createdAt;
    }

    /**
     * Establece la fecha y hora de creación.
     *
     * @param createdAt fecha y hora de creación
     */
    public void setCreatedAt(ZonedDateTime createdAt) {
        this.createdAt = createdAt;
    }

    /**
     * Obtiene la fecha y hora de última modificación.
     *
     * @return marca temporal de última actualización
     */
    public ZonedDateTime getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Establece la fecha y hora de última modificación.
     *
     * @param updatedAt fecha y hora de última actualización
     */
    public void setUpdatedAt(ZonedDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Publicacion that = (Publicacion) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
