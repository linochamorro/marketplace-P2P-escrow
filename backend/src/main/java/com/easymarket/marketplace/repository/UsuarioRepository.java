package com.easymarket.marketplace.repository;

import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.model.Rol;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * Busca la única cuenta que posee un rol del sistema.
     *
     * <p>La creación de publicaciones usa {@link Rol#ADMIN}; el plan establece que existe una
     * única cuenta ADMIN provisionada fuera del registro público.</p>
     *
     * @param rol rol de la cuenta que se busca
     * @return cuenta con ese rol, o vacío si la provisión del dato es inconsistente
     */
    Optional<Usuario> findByRol(Rol rol);

    /**
     * Incrementa atómicamente el saldo disponible cacheado de un vendedor en centavos.
     *
     * <p>La expresión de actualización se evalúa en PostgreSQL sobre el valor actual de la fila,
     * por lo que créditos simultáneos de transacciones distintas no se pierden. El servicio que lo
     * invoca también inserta el movimiento append-only dentro de su misma transacción.</p>
     *
     * @param vendedorId identificador del vendedor que recibe el crédito
     * @param monto monto positivo en centavos a sumar
     */
    @Modifying
    @Query("update Usuario u set u.saldoDisponible = u.saldoDisponible + :monto where u.id = :vendedorId")
    void incrementarSaldoDisponible(@Param("vendedorId") Long vendedorId, @Param("monto") long monto);
}
