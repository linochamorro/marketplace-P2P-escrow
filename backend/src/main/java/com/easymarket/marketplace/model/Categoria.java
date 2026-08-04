package com.easymarket.marketplace.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Objects;

/**
 * Entidad JPA que representa a una categoría raíz en el catálogo de productos.
 */
@Entity
@Table(name = "categorias")
public class Categoria {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nombre", nullable = false, unique = true, length = 100)
    private String nombre;

    /**
     * Constructor por defecto requerido por JPA.
     */
    public Categoria() {
    }

    /**
     * Constructor con nombre obligatorio.
     *
     * @param nombre nombre único de la categoría raíz
     */
    public Categoria(String nombre) {
        this.nombre = nombre;
    }

    /**
     * Obtiene el ID único de la categoría.
     *
     * @return ID de la categoría
     */
    public Long getId() {
        return id;
    }

    /**
     * Establece el ID único de la categoría.
     *
     * @param id ID de la categoría
     */
    public void setId(Long id) {
        this.id = id;
    }

    /**
     * Obtiene el nombre de la categoría.
     *
     * @return nombre de la categoría
     */
    public String getNombre() {
        return nombre;
    }

    /**
     * Establece el nombre de la categoría.
     *
     * @param nombre nombre de la categoría
     */
    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Categoria categoria = (Categoria) o;
        return Objects.equals(id, categoria.id) && Objects.equals(nombre, categoria.nombre);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, nombre);
    }
}
