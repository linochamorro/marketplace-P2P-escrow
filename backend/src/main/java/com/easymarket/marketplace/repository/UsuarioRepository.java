package com.easymarket.marketplace.repository;

import com.easymarket.marketplace.model.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repositorio Spring Data JPA para la entidad {@link Usuario}.
 */
@Repository
public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

    /**
     * Verifica si existe un usuario registrado con el email especificado.
     *
     * @param email correo electrónico a verificar
     * @return {@code true} si el email ya está registrado, {@code false} en caso contrario
     */
    boolean existsByEmail(String email);

    /**
     * Busca un usuario por su correo electrónico.
     *
     * @param email correo electrónico del usuario
     * @return un {@link Optional} conteniendo el usuario si fue encontrado, o vacío en caso contrario
     */
    Optional<Usuario> findByEmail(String email);
}
