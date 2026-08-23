package com.easymarket.marketplace.dto;

import com.easymarket.marketplace.model.Categoria;
import com.easymarket.marketplace.model.EstadoPublicacion;
import com.easymarket.marketplace.model.Publicacion;
import com.easymarket.marketplace.model.Rol;
import com.easymarket.marketplace.model.Subcategoria;
import com.easymarket.marketplace.model.Usuario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prueba unitaria del mapeo {@link PublicacionResponseDto#fromEntity}.
 */
class PublicacionResponseDtoTest {

    /**
     * Verifica que {@link PublicacionResponseDto#fromEntity} mapea el nombre de archivo
     * de imagen de la entidad al DTO.
     */
    @Test
    @DisplayName("fromEntity mapea el nombre de archivo de imagen de la publicación")
    void fromEntityMapeaNombreArchivoImagen() {
        Categoria categoria = new Categoria("Electrónica");
        categoria.setId(1L);
        Subcategoria subcategoria = new Subcategoria(categoria, "Auriculares");
        subcategoria.setId(2L);
        Usuario vendedor = new Usuario(
                "vendedor@easymarket.dev",
                "$2a$10$hashInnecesarioParaElTest",
                Rol.USUARIO,
                0L,
                ZonedDateTime.now()
        );
        vendedor.setId(3L);

        Publicacion publicacion = new Publicacion(vendedor, categoria, subcategoria, 18_900L, 5, "Auriculares Bluetooth");
        publicacion.setId(7L);
        publicacion.setEstado(EstadoPublicacion.APROBADA);
        publicacion.setImagenFilename("auriculares-bluetooth.jpg");

        PublicacionResponseDto dto = PublicacionResponseDto.fromEntity(publicacion);

        assertThat(dto.id()).isEqualTo(7L);
        assertThat(dto.usuarioEmail()).isEqualTo("vendedor@easymarket.dev");
        assertThat(dto.imagenFilename()).isEqualTo("auriculares-bluetooth.jpg");
    }
}