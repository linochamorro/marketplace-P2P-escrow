package com.easymarket.marketplace.repository;

import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.model.Rol;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
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

    /**
     * Lists every real account with at least one permanently blocked login combination.
     *
     * <p>Read-only query for {@code GET /admin/usuarios/bloqueados} (PHA06TSK07; plan.md,
     * "Lecturas administrativas"): a permanently blocked account is a real {@code usuarios} row
     * for which at least one {@code login_attempts} row with the same email has
     * {@code intentos >= 12} — the same canonical signal that
     * {@code AdminUsuarioController.desbloquearUsuario} uses and the permanent threshold of the
     * atomic upsert {@code LoginAttemptRepository.registrarFalloAtomic}. Emails present in
     * {@code login_attempts} that do not match a real account are not accounts and are excluded.
     * Each real account appears exactly once regardless of how many IPs reached the threshold.
     * The caller maps only {@code id} and {@code email} into the response DTO; the query itself
     * performs no state transition and no ledger write.</p>
     *
     * @return real accounts with permanent block, without contract order
     */
    @Query("select u from Usuario u where exists (select la from LoginAttempt la "
        + "where la.email = u.email and la.intentos >= 12)")
    List<Usuario> findUsuariosConBloqueoPermanente();

    /**
     * Counts every real account with at least one permanently blocked login combination.
     *
     * <p>Read-only aggregate for {@code GET /admin/tablero} (PHA06TSK07; plan.md, "Tablero
     * administrativo", "cuentas bloqueadas permanentemente"). Same canonical definition of
     * permanent block as {@link #findUsuariosConBloqueoPermanente()}: a real {@code usuarios}
     * row with at least one {@code login_attempts.intentos >= 12} for its email. Emails without
     * a real account do not count. The aggregation is read-only; it neither resets counters nor
     * modifies the ledger.</p>
     *
     * @return number of real permanently blocked accounts, including zero when there are none
     */
    @Query("select count(u) from Usuario u where exists (select la from LoginAttempt la "
        + "where la.email = u.email and la.intentos >= 12)")
    long contarUsuariosConBloqueoPermanente();
}
