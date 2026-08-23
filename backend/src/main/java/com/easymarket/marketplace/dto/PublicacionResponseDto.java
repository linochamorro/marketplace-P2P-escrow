package com.easymarket.marketplace.dto;

import com.easymarket.marketplace.model.Publicacion;

/**
 * DTO de respuesta HTTP para la exposición de publicaciones en el API (Story 1, spec.md).
 *
 * @param id identificador único de la publicación
 * @param precio monto en centavos de moneda entera
 * @param stock unidades disponibles
 * @param estado estado actual en el ciclo de vida de la publicación (ej. "PENDIENTE_REVISION")
 * @param descripcion detalles descriptivos del producto
 * @param categoriaId ID de la categoría raíz
 * @param subcategoriaId ID de la subcategoría
 * @param usuarioId ID del usuario vendedor propietario de la publicación
 * @param usuarioEmail email del vendedor propietario (PHA09TSK02), mostrado en las cards del marketplace
 * @param imagenFilename nombre de archivo de imagen (sin prefijo de URL); puede ser {@code null}
 */
public record PublicacionResponseDto(
        Long id,
        Long precio,
        Integer stock,
        String estado,
        String descripcion,
        Long categoriaId,
        Long subcategoriaId,
        Long usuarioId,
        String usuarioEmail,
        String imagenFilename
) {
    /**
     * Mapea una entidad JPA {@link Publicacion} a su representación DTO de respuesta.
     *
     * @param publicacion entidad JPA de origen
     * @return DTO {@link PublicacionResponseDto} resultante
     */
    public static PublicacionResponseDto fromEntity(Publicacion publicacion) {
        return new PublicacionResponseDto(
                publicacion.getId(),
                publicacion.getPrecio(),
                publicacion.getStock(),
                publicacion.getEstado().name(),
                publicacion.getDescripcion(),
                publicacion.getCategoria().getId(),
                publicacion.getSubcategoria().getId(),
                publicacion.getUsuario().getId(),
                publicacion.getUsuario().getEmail(),
                publicacion.getImagenFilename()
        );
    }
}
