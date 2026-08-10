package com.easymarket.marketplace.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test de migración Flyway para el log append-only de transiciones incorporado en V10.
 *
 * <p>Verifica sobre PostgreSQL real mediante Testcontainers los criterios de PHA04TSK22:
 * {@code transaccion_eventos} acepta referencias válidas, rechaza ambas claves foráneas
 * inexistentes y no permite actualizar ni eliminar eventos ya insertados. También confirma
 * que {@code actor_id} admite {@code NULL} para transiciones ejecutadas por jobs del sistema,
 * según la sección "Auditoría de transacciones" de {@code plan.md}.</p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=test-secret-key-32-chars-minimum-ok"
})
class FlywayMigrationV10Tests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Verifica que un evento puede referir una transacción y un actor existentes, y que un job
     * del sistema puede registrar el mismo tipo de evento dejando {@code actor_id} en {@code NULL}.
     */
    @Test
    @DisplayName("Debe insertar eventos con FKs válidas y actor de sistema nullable")
    void testInsertaEventosConReferenciasValidasYActorSistemaNullable() {
        Long actorId = crearUsuarioTest("actor-v10-valido@example.com");
        Long transaccionId = crearTransaccionTest(
            "comprador-v10-valido@example.com", "vendedor-v10-valido@example.com"
        );

        Long eventoConActorId = insertarEvento(transaccionId, actorId, "reservada", "enviado", null);
        Long eventoSistemaId = insertarEvento(transaccionId, null, "enviado", "entregado", null);

        assertThat(leerActorId(eventoConActorId)).isEqualTo(actorId);
        assertThat(leerActorId(eventoSistemaId)).isNull();
    }

    /**
     * Verifica que {@code transaccion_eventos.transaccion_id} rechaza una transacción inexistente.
     */
    @Test
    @DisplayName("Debe rechazar transaccion_id inexistente por FK")
    void testRechazaTransaccionInexistentePorFk() {
        Long actorId = crearUsuarioTest("actor-v10-transaccion-inexistente@example.com");

        assertThatThrownBy(() -> insertarEvento(999999L, actorId, "reservada", "enviado", null))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Verifica que {@code transaccion_eventos.actor_id} rechaza un usuario inexistente.
     */
    @Test
    @DisplayName("Debe rechazar actor_id inexistente por FK")
    void testRechazaActorInexistentePorFk() {
        Long transaccionId = crearTransaccionTest(
            "comprador-v10-actor-inexistente@example.com", "vendedor-v10-actor-inexistente@example.com"
        );

        assertThatThrownBy(() -> insertarEvento(transaccionId, 999999L, "reservada", "enviado", null))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Verifica que PostgreSQL rechaza cualquier actualización de un evento de auditoría persistido.
     */
    @Test
    @DisplayName("Debe rechazar UPDATE sobre un evento append-only")
    void testRechazaUpdateSobreEventoAppendOnly() {
        Long eventoId = crearEventoTest("update");

        assertThatThrownBy(() -> jdbcTemplate.update(
            "UPDATE transaccion_eventos SET motivo = ? WHERE id = ?", "motivo alterado", eventoId
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Verifica que PostgreSQL rechaza cualquier eliminación de un evento de auditoría persistido.
     */
    @Test
    @DisplayName("Debe rechazar DELETE sobre un evento append-only")
    void testRechazaDeleteSobreEventoAppendOnly() {
        Long eventoId = crearEventoTest("delete");

        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM transaccion_eventos WHERE id = ?", eventoId))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Inserta un evento de transición para una transacción existente.
     *
     * @param transaccionId ID de la transacción auditada.
     * @param actorId ID nullable del usuario o admin responsable; {@code null} para un job del sistema.
     * @param estadoOrigen estado de la transacción antes de la transición.
     * @param estadoDestino estado de la transacción después de la transición.
     * @param motivo motivo nullable asociado a la transición.
     * @return ID del evento insertado.
     */
    private Long insertarEvento(Long transaccionId, Long actorId, String estadoOrigen, String estadoDestino, String motivo) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO transaccion_eventos "
                + "(transaccion_id, actor_id, estado_origen, estado_destino, motivo) "
                + "VALUES (?, ?, ?, ?, ?) RETURNING id",
            Long.class, transaccionId, actorId, estadoOrigen, estadoDestino, motivo
        );
    }

    /**
     * Lee el actor responsable de un evento insertado.
     *
     * @param eventoId ID del evento de auditoría.
     * @return ID del actor o {@code null} si el evento fue creado por un job del sistema.
     */
    private Long leerActorId(Long eventoId) {
        return jdbcTemplate.queryForObject(
            "SELECT actor_id FROM transaccion_eventos WHERE id = ?", Long.class, eventoId
        );
    }

    /**
     * Crea un evento de prueba con actor de sistema para verificar el trigger append-only.
     *
     * @param sufijo valor único para los datos auxiliares del evento.
     * @return ID del evento creado.
     */
    private Long crearEventoTest(String sufijo) {
        Long transaccionId = crearTransaccionTest(
            "comprador-v10-" + sufijo + "@example.com", "vendedor-v10-" + sufijo + "@example.com"
        );
        return insertarEvento(transaccionId, null, "reservada", "enviado", "evento para " + sufijo);
    }

    /**
     * Crea un usuario de prueba en {@code usuarios}.
     *
     * @param email email único del usuario de prueba.
     * @return ID del usuario creado.
     */
    private Long crearUsuarioTest(String email) {
        jdbcTemplate.update(
            "INSERT INTO usuarios (email, password_hash, rol) VALUES (?, ?, ?)",
            email, "$2a$10$abcdefghijklmnopqrstuuu", "USUARIO"
        );
        return jdbcTemplate.queryForObject("SELECT id FROM usuarios WHERE email = ?", Long.class, email);
    }

    /**
     * Crea una categoría de prueba.
     *
     * @param nombre nombre único de la categoría.
     * @return ID de la categoría creada.
     */
    private Long crearCategoriaTest(String nombre) {
        jdbcTemplate.update("INSERT INTO categorias (nombre) VALUES (?)", nombre);
        return jdbcTemplate.queryForObject("SELECT id FROM categorias WHERE nombre = ?", Long.class, nombre);
    }

    /**
     * Crea una subcategoría de prueba para una categoría existente.
     *
     * @param categoriaId ID de la categoría padre.
     * @param nombre nombre único de la subcategoría dentro de la categoría.
     * @return ID de la subcategoría creada.
     */
    private Long crearSubcategoriaTest(Long categoriaId, String nombre) {
        jdbcTemplate.update("INSERT INTO subcategorias (categoria_id, nombre) VALUES (?, ?)", categoriaId, nombre);
        return jdbcTemplate.queryForObject(
            "SELECT id FROM subcategorias WHERE categoria_id = ? AND nombre = ?", Long.class, categoriaId, nombre
        );
    }

    /**
     * Crea una publicación aprobada para un vendedor existente.
     *
     * @param vendedorId ID del usuario dueño de la publicación.
     * @param sufijo sufijo único para los nombres auxiliares de la publicación.
     * @return ID de la publicación creada.
     */
    private Long crearPublicacionParaVendedor(Long vendedorId, String sufijo) {
        Long categoriaId = crearCategoriaTest("Categoría V10 " + sufijo);
        Long subcategoriaId = crearSubcategoriaTest(categoriaId, "Subcategoría V10 " + sufijo);
        jdbcTemplate.update(
            "INSERT INTO publicaciones (usuario_id, categoria_id, subcategoria_id, precio, stock, estado, descripcion) VALUES (?, ?, ?, ?, ?, ?, ?)",
            vendedorId, categoriaId, subcategoriaId, 150000L, 10, "aprobada", "Publicación de prueba V10"
        );
        return jdbcTemplate.queryForObject(
            "SELECT id FROM publicaciones WHERE usuario_id = ?", Long.class, vendedorId
        );
    }

    /**
     * Crea una transacción reservada de prueba con comprador y vendedor nuevos.
     *
     * @param compradorEmail email único del comprador.
     * @param vendedorEmail email único del vendedor.
     * @return ID de la transacción creada.
     */
    private Long crearTransaccionTest(String compradorEmail, String vendedorEmail) {
        Long vendedorId = crearUsuarioTest(vendedorEmail);
        Long compradorId = crearUsuarioTest(compradorEmail);
        Long publicacionId = crearPublicacionParaVendedor(vendedorId, compradorEmail);
        jdbcTemplate.update(
            "INSERT INTO transacciones (estado, comprador_id, publicacion_id, precio_snapshot, fecha_reservada) VALUES (?, ?, ?, ?, ?)",
            "reservada", compradorId, publicacionId, 10000L, OffsetDateTime.now()
        );
        return jdbcTemplate.queryForObject(
            "SELECT id FROM transacciones WHERE comprador_id = ? AND publicacion_id = ?", Long.class, compradorId, publicacionId
        );
    }
}
