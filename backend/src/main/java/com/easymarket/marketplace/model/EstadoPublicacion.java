package com.easymarket.marketplace.model;

/**
 * Enumeración que representa los estados válidos de una publicación en la máquina de estados del sistema (Story 1-3, spec.md).
 */
public enum EstadoPublicacion {

    /**
     * Estado inicial al crear una publicación. Pendiente de moderación.
     */
    PENDIENTE_REVISION("pendiente_revisión"),

    /**
     * Publicación aprobada por el moderador/admin y visible en el catálogo.
     */
    APROBADA("aprobada"),

    /**
     * Publicación devuelta al vendedor para correcciones requeridas.
     */
    CAMBIOS_SOLICITADOS("cambios_solicitados"),

    /**
     * Publicación rechazada definitivamente por el moderador.
     */
    RECHAZADA("rechazada"),

    /**
     * Publicación oculta por el vendedor o por agotamiento de stock (stock=0).
     */
    OCULTA("oculta");

    private final String codigo;

    EstadoPublicacion(String codigo) {
        this.codigo = codigo;
    }

    /**
     * Obtiene la representación en cadena exacta requerida por la base de datos PostgreSQL.
     *
     * @return código de estado en base de datos
     */
    public String getCodigo() {
        return codigo;
    }

    /**
     * Busca el valor de la enumeración a partir de su código en base de datos.
     *
     * @param codigo código en base de datos
     * @return el valor de {@link EstadoPublicacion} correspondiente
     * @throws IllegalArgumentException si el código no coincide con ningún estado válido
     */
    public static EstadoPublicacion fromCodigo(String codigo) {
        for (EstadoPublicacion estado : values()) {
            if (estado.codigo.equals(codigo)) {
                return estado;
            }
        }
        throw new IllegalArgumentException("Estado de publicación desconocido: " + codigo);
    }
}
