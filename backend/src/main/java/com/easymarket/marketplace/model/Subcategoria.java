package com.easymarket.marketplace.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.Objects;

/**
 * Entidad JPA que representa a una subcategoría perteneciente a una categoría raíz.
 */
@Entity
@Table(
    name = "subcategorias",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_subcategorias_categoria_nombre", columnNames = {"categoria_id", "nombre"})
    }
)
public class Subcategoria {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "categoria_id", nullable = false)
    private Categoria categoria;

    @Column(name = "nombre", nullable = false, length = 100)
    private String nombre;

    /**
     * Constructor por defecto requerido por JPA.
     */
    public Subcategoria() {
    }

    /**
     * Constructor con categoría padre y nombre obligatorio.
     *
     * @param categoria categoría raíz a la que pertenece la subcategoría
     * @param nombre nombre de la subcategoría (único por categoría padre)
     */
    public Subcategoria(Categoria categoria, String nombre) {
        this.categoria = categoria;
        this.nombre = nombre;
    }

    /**
     * Obtiene el ID de la subcategoría.
     *
     * @return ID de la subcategoría
     */
    public Long getId() {
        return id;
    }

    /**
     * Establece el ID de la subcategoría.
     *
     * @param id ID de la subcategoría
     */
    public void setId(Long id) {
        this.id = id;
    }

    /**
     * Obtiene la categoría raíz padre.
     *
     * @return categoría padre
     */
    public Categoria getCategoria() {
        return categoria;
    }

    /**
     * Establece la categoría raíz padre.
     *
     * @param categoria categoría padre
     */
    public void setCategoria(Categoria categoria) {
        this.categoria = categoria;
    }

    /**
     * Obtiene el nombre de la subcategoría.
     *
     * @return nombre de la subcategoría
     */
    public String getNombre() {
        return nombre;
    }

    /**
     * Establece el nombre de la subcategoría.
     *
     * @param nombre nombre de la subcategoría
     */
    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Subcategoria that = (Subcategoria) o;
        return Objects.equals(id, that.id) && Objects.equals(nombre, that.nombre);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, nombre);
    }
}
