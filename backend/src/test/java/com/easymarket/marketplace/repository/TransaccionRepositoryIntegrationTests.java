package com.easymarket.marketplace.repository;

import com.easymarket.marketplace.model.Categoria;
import com.easymarket.marketplace.model.EstadoTransaccion;
import com.easymarket.marketplace.model.Publicacion;
import com.easymarket.marketplace.model.Rol;
import com.easymarket.marketplace.model.Subcategoria;
import com.easymarket.marketplace.model.Transaccion;
import com.easymarket.marketplace.model.Usuario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link TransaccionRepository} against PostgreSQL via Testcontainers.
 *
 * <p>Flyway creates the real schema and Hibernate validates it before the repository query is
 * exercised. The fixtures isolate their data with a nanosecond suffix and assert each publication
 * count directly rather than relying on repository result ordering.</p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=test-secret-key-32-chars-minimum-ok"
})
class TransaccionRepositoryIntegrationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TransaccionRepository transaccionRepository;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private CategoriaRepository categoriaRepository;

    @Autowired
    private SubcategoriaRepository subcategoriaRepository;

    @Autowired
    private PublicacionRepository publicacionRepository;

    /**
     * Counts only the three final transaction states defined by Story 11 for each publication.
     */
    @Test
    @DisplayName("Cuenta completada, recibido y recibido_sin_respuesta por publicación y excluye los demás estados")
    void contarTransaccionesCompletadasPorPublicacion_EstadosFinalesYNoFinalesEnDosPublicaciones_DevuelveConteoExacto() {
        String sufijo = Long.toString(System.nanoTime());
        Usuario vendedor = crearUsuario("vendedor-conteo-" + sufijo);
        Usuario comprador = crearUsuario("comprador-conteo-" + sufijo);
        Categoria categoria = categoriaRepository.save(new Categoria("Categoría conteo " + sufijo));
        Subcategoria subcategoria = subcategoriaRepository.save(new Subcategoria(categoria, "Subcategoría conteo " + sufijo));
        Publicacion primeraPublicacion = crearPublicacion(vendedor, categoria, subcategoria, "Primera " + sufijo);
        Publicacion segundaPublicacion = crearPublicacion(vendedor, categoria, subcategoria, "Segunda " + sufijo);

        guardarTransaccion(comprador, primeraPublicacion, EstadoTransaccion.COMPLETADA);
        guardarTransaccion(comprador, primeraPublicacion, EstadoTransaccion.RECIBIDO);
        guardarTransaccion(comprador, primeraPublicacion, EstadoTransaccion.RECIBIDO_SIN_RESPUESTA);
        guardarTransaccion(comprador, primeraPublicacion, EstadoTransaccion.RESERVADA);
        guardarTransaccion(comprador, primeraPublicacion, EstadoTransaccion.ENVIADO);
        guardarTransaccion(comprador, primeraPublicacion, EstadoTransaccion.ENTREGADO);
        guardarTransaccion(comprador, primeraPublicacion, EstadoTransaccion.DISPUTA);
        guardarTransaccion(comprador, primeraPublicacion, EstadoTransaccion.CANCELADA);
        guardarTransaccion(comprador, segundaPublicacion, EstadoTransaccion.COMPLETADA);
        guardarTransaccion(comprador, segundaPublicacion, EstadoTransaccion.RESERVADA);

        assertThat(transaccionRepository.contarTransaccionesCompletadasPorPublicacion(primeraPublicacion.getId()))
            .isEqualTo(3L);
        assertThat(transaccionRepository.contarTransaccionesCompletadasPorPublicacion(segundaPublicacion.getId()))
            .isEqualTo(1L);
    }

    /**
     * Persists a regular user suitable as seller or buyer in an isolated integration-test fixture.
     *
     * @param prefijoEmail unique prefix used to derive the user's email address
     * @return persisted regular user with a zero integer-cent balance
     */
    private Usuario crearUsuario(String prefijoEmail) {
        return usuarioRepository.save(new Usuario(prefijoEmail + "@example.com", "hash", Rol.USUARIO, 0L, ZonedDateTime.now()));
    }

    /**
     * Persists a publication associated with the supplied seller and category hierarchy.
     *
     * @param vendedor persisted owner of the publication
     * @param categoria persisted root category
     * @param subcategoria persisted child category of {@code categoria}
     * @param descripcion publication description used to distinguish the fixture rows
     * @return persisted publication
     */
    private Publicacion crearPublicacion(Usuario vendedor, Categoria categoria, Subcategoria subcategoria, String descripcion) {
        return publicacionRepository.save(new Publicacion(vendedor, categoria, subcategoria, 12_345L, 20, descripcion));
    }

    /**
     * Persists one transaction for a publication with an explicitly selected valid state.
     *
     * @param comprador persisted buyer of the transaction
     * @param publicacion persisted publication identified by the query under test
     * @param estado one of the states defined by {@link EstadoTransaccion}
     */
    private void guardarTransaccion(Usuario comprador, Publicacion publicacion, EstadoTransaccion estado) {
        Transaccion transaccion = new Transaccion(comprador, publicacion, 12_345L, ZonedDateTime.now());
        transaccion.setEstado(estado);
        transaccionRepository.save(transaccion);
    }
}
