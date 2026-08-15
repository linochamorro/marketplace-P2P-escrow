package com.easymarket.marketplace.controller;

import com.easymarket.marketplace.dto.LoginRequestDto;
import com.easymarket.marketplace.model.Categoria;
import com.easymarket.marketplace.model.EstadoPublicacion;
import com.easymarket.marketplace.model.EstadoTransaccion;
import com.easymarket.marketplace.model.Publicacion;
import com.easymarket.marketplace.model.Rol;
import com.easymarket.marketplace.model.Subcategoria;
import com.easymarket.marketplace.model.Transaccion;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.CategoriaRepository;
import com.easymarket.marketplace.repository.PublicacionRepository;
import com.easymarket.marketplace.repository.SubcategoriaRepository;
import com.easymarket.marketplace.repository.TransaccionRepository;
import com.easymarket.marketplace.repository.UsuarioRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración HTTP para el listado público filtrable de Story 11.
 *
 * <p>Ejercita el controlador, seguridad JWT, serialización y PostgreSQL real mediante
 * Testcontainers; los resultados se verifican desde el cuerpo HTTP y no mediante mocks del
 * servicio de dominio.</p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=clave-secreta-para-pruebas-de-listado-publico-min-32-chars",
    "ADMIN_EMAIL=admin.seed@easymarket.com",
    "ADMIN_PASSWORD_HASH=$2a$10$R9h/cIPz0gi.URNNxrkh2OPST9/PgBkqquzi.Ss7KIUgO2t0jWMUW"
})
class ListadoPublicacionesControllerIntegrationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private CategoriaRepository categoriaRepository;

    @Autowired
    private SubcategoriaRepository subcategoriaRepository;

    @Autowired
    private PublicacionRepository publicacionRepository;

    @Autowired
    private TransaccionRepository transaccionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String passwordRaw = "PasswordSeguro123!";
    private MockMvc mockMvc;
    private Usuario usuario;
    private Usuario admin;
    private Categoria categoriaElectronica;
    private Categoria categoriaHogar;
    private Subcategoria subcategoriaTelefonos;
    private Subcategoria subcategoriaComputadoras;

    /**
     * Restablece las tablas de fixtures y crea la jerarquía y usuarios autenticables de cada caso.
     */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        jdbcTemplate.execute("TRUNCATE TABLE transacciones, publicaciones, subcategorias, categorias, usuarios CASCADE");

        usuario = guardarUsuario("usuario-listado@easymarket.com", Rol.USUARIO);
        admin = guardarUsuario("admin-listado@easymarket.com", Rol.ADMIN);
        categoriaElectronica = categoriaRepository.save(new Categoria("Electrónica"));
        categoriaHogar = categoriaRepository.save(new Categoria("Hogar"));
        subcategoriaTelefonos = subcategoriaRepository.save(new Subcategoria(categoriaElectronica, "Teléfonos"));
        subcategoriaComputadoras = subcategoriaRepository.save(new Subcategoria(categoriaElectronica, "Computadoras"));
    }

    /**
     * Verifica filtros combinados, límites inclusivos, precio ascendente y exclusión de estados no públicos.
     *
     * @throws Exception si la petición HTTP no puede ejecutarse
     */
    @Test
    @DisplayName("GET /publicaciones combina categoría, subcategoría y rango inclusivo con precio ascendente")
    void listar_CombinacionFiltrosPrecioAscendente_DevuelveSoloAprobadasEnOrden() throws Exception {
        Publicacion limiteMinimo = guardarPublicacion(usuario, categoriaElectronica, subcategoriaTelefonos, 100L, "Límite mínimo", EstadoPublicacion.APROBADA);
        Publicacion limiteMaximo = guardarPublicacion(usuario, categoriaElectronica, subcategoriaTelefonos, 300L, "Límite máximo", EstadoPublicacion.APROBADA);
        guardarPublicacion(usuario, categoriaElectronica, subcategoriaTelefonos, 99L, "Fuera por mínimo", EstadoPublicacion.APROBADA);
        guardarPublicacion(usuario, categoriaElectronica, subcategoriaComputadoras, 200L, "Fuera por subcategoría", EstadoPublicacion.APROBADA);
        guardarPublicacion(usuario, categoriaHogar, subcategoriaTelefonos, 200L, "Fuera por categoría", EstadoPublicacion.APROBADA);
        guardarPublicacion(usuario, categoriaElectronica, subcategoriaTelefonos, 200L, "Pendiente", EstadoPublicacion.PENDIENTE_REVISION);
        guardarPublicacion(usuario, categoriaElectronica, subcategoriaTelefonos, 200L, "Oculta", EstadoPublicacion.OCULTA);

        mockMvc.perform(get("/publicaciones")
                        .cookie(obtenerCookieJwt(usuario))
                        .param("categoriaId", categoriaElectronica.getId().toString())
                        .param("subcategoriaId", subcategoriaTelefonos.getId().toString())
                        .param("precioMinimo", "100")
                        .param("precioMaximo", "300")
                        .param("orden", "PRECIO_ASCENDENTE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(limiteMinimo.getId()))
                .andExpect(jsonPath("$[0].precio").value(100))
                .andExpect(jsonPath("$[1].id").value(limiteMaximo.getId()))
                .andExpect(jsonPath("$[1].precio").value(300));
    }

    /**
     * Verifica que el orden descendente por precio se materializa en la respuesta HTTP.
     *
     * @throws Exception si la petición HTTP no puede ejecutarse
     */
    @Test
    @DisplayName("GET /publicaciones con PRECIO_DESCENDENTE devuelve precios de mayor a menor")
    void listar_PrecioDescendente_DevuelvePreciosDeMayorAMenor() throws Exception {
        Publicacion menor = guardarPublicacion(usuario, categoriaElectronica, subcategoriaTelefonos, 100L, "Menor", EstadoPublicacion.APROBADA);
        Publicacion mayor = guardarPublicacion(usuario, categoriaElectronica, subcategoriaTelefonos, 300L, "Mayor", EstadoPublicacion.APROBADA);
        Publicacion medio = guardarPublicacion(usuario, categoriaElectronica, subcategoriaTelefonos, 200L, "Medio", EstadoPublicacion.APROBADA);

        mockMvc.perform(get("/publicaciones").cookie(obtenerCookieJwt(usuario)).param("orden", "PRECIO_DESCENDENTE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(mayor.getId()))
                .andExpect(jsonPath("$[1].id").value(medio.getId()))
                .andExpect(jsonPath("$[2].id").value(menor.getId()));
    }

    /**
     * Verifica que más vendido usa únicamente los tres estados finales definidos para Story 11.
     *
     * @throws Exception si la petición HTTP no puede ejecutarse
     */
    @Test
    @DisplayName("GET /publicaciones con MAS_VENDIDO ordena por ventas finalizadas descendentes")
    void listar_MasVendido_OrdenaPorEstadosFinalesDefinidos() throws Exception {
        Usuario comprador = guardarUsuario("comprador-listado@easymarket.com", Rol.USUARIO);
        Publicacion unaVenta = guardarPublicacion(usuario, categoriaElectronica, subcategoriaTelefonos, 100L, "Una venta", EstadoPublicacion.APROBADA);
        Publicacion tresVentas = guardarPublicacion(usuario, categoriaElectronica, subcategoriaTelefonos, 200L, "Tres ventas", EstadoPublicacion.APROBADA);
        Publicacion sinVentas = guardarPublicacion(usuario, categoriaElectronica, subcategoriaTelefonos, 300L, "Sin ventas", EstadoPublicacion.APROBADA);
        guardarTransaccion(comprador, unaVenta, EstadoTransaccion.COMPLETADA);
        guardarTransaccion(comprador, tresVentas, EstadoTransaccion.COMPLETADA);
        guardarTransaccion(comprador, tresVentas, EstadoTransaccion.RECIBIDO);
        guardarTransaccion(comprador, tresVentas, EstadoTransaccion.RECIBIDO_SIN_RESPUESTA);
        guardarTransaccion(comprador, sinVentas, EstadoTransaccion.RESERVADA);

        mockMvc.perform(get("/publicaciones").cookie(obtenerCookieJwt(usuario)).param("orden", "MAS_VENDIDO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(tresVentas.getId()))
                .andExpect(jsonPath("$[1].id").value(unaVenta.getId()))
                .andExpect(jsonPath("$[2].id").value(sinVentas.getId()));
    }

    /**
     * Verifica que la ausencia de orden conserva el subconjunto filtrado sin exigir un orden no contratado.
     *
     * @throws Exception si la petición HTTP no puede ejecutarse
     */
    @Test
    @DisplayName("GET /publicaciones sin orden devuelve el subconjunto aprobado sin imponer orden")
    void listar_SinOrden_DevuelveSubconjuntoAprobado() throws Exception {
        Publicacion primera = guardarPublicacion(usuario, categoriaElectronica, subcategoriaTelefonos, 100L, "Primera", EstadoPublicacion.APROBADA);
        Publicacion segunda = guardarPublicacion(usuario, categoriaElectronica, subcategoriaComputadoras, 200L, "Segunda", EstadoPublicacion.APROBADA);
        guardarPublicacion(usuario, categoriaHogar, subcategoriaTelefonos, 300L, "Otra categoría", EstadoPublicacion.APROBADA);

        MvcResult resultado = mockMvc.perform(get("/publicaciones")
                        .cookie(obtenerCookieJwt(usuario))
                        .param("categoriaId", categoriaElectronica.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andReturn();

        List<Long> ids = objectMapper.readTree(resultado.getResponse().getContentAsString())
                .findValuesAsText("id").stream().map(Long::valueOf).toList();
        assertThat(ids).containsExactlyInAnyOrder(primera.getId(), segunda.getId());
    }

    /**
     * Verifica que los límites invertidos de precio se traducen al contrato HTTP 400.
     *
     * @throws Exception si la petición HTTP no puede ejecutarse
     */
    @Test
    @DisplayName("GET /publicaciones con rango de precio invertido retorna 400 Bad Request")
    void listar_RangoPrecioInvertido_Retorna400BadRequest() throws Exception {
        mockMvc.perform(get("/publicaciones")
                        .cookie(obtenerCookieJwt(usuario))
                        .param("precioMinimo", "301")
                        .param("precioMaximo", "300"))
                .andExpect(status().isBadRequest());
    }

    /**
     * Verifica la regresión del endpoint por estado, incluida la autorización administrativa existente.
     *
     * @throws Exception si la petición HTTP no puede ejecutarse
     */
    @Test
    @DisplayName("GET /publicaciones?estado=PENDIENTE_REVISION conserva el listado exclusivo de ADMIN")
    void listarPorEstado_PendienteRevision_AdminConservaContratoExistente() throws Exception {
        Publicacion pendiente = guardarPublicacion(usuario, categoriaElectronica, subcategoriaTelefonos, 100L, "Pendiente", EstadoPublicacion.PENDIENTE_REVISION);
        guardarPublicacion(usuario, categoriaElectronica, subcategoriaTelefonos, 200L, "Aprobada", EstadoPublicacion.APROBADA);

        mockMvc.perform(get("/publicaciones").cookie(obtenerCookieJwt(admin)).param("estado", "PENDIENTE_REVISION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(pendiente.getId()));
    }

    /**
     * Persiste un usuario con contraseña BCrypt apta para el login real del test.
     *
     * @param email dirección de correo única del usuario
     * @param rol rol de autorización del usuario
     * @return usuario persistido
     */
    private Usuario guardarUsuario(String email, Rol rol) {
        return usuarioRepository.save(new Usuario(email, passwordEncoder.encode(passwordRaw), rol, 0L, ZonedDateTime.now()));
    }

    /**
     * Persiste una publicación con el estado seleccionado para el escenario HTTP.
     *
     * @param vendedor dueño persistido de la publicación
     * @param categoria categoría persistida de la publicación
     * @param subcategoria subcategoría persistida de la publicación
     * @param precio precio entero en centavos
     * @param descripcion texto que identifica la publicación de fixture
     * @param estado estado persistido de la publicación
     * @return publicación persistida
     */
    private Publicacion guardarPublicacion(Usuario vendedor, Categoria categoria, Subcategoria subcategoria,
                                           long precio, String descripcion, EstadoPublicacion estado) {
        Publicacion publicacion = new Publicacion(vendedor, categoria, subcategoria, precio, 5, descripcion);
        publicacion.setEstado(estado);
        return publicacionRepository.save(publicacion);
    }

    /**
     * Persiste una transacción con un estado explícito para comprobar el conteo de más vendido.
     *
     * @param comprador usuario comprador persistido
     * @param publicacion publicación persistida adquirida
     * @param estado estado de transacción que se persistirá
     */
    private void guardarTransaccion(Usuario comprador, Publicacion publicacion, EstadoTransaccion estado) {
        Transaccion transaccion = new Transaccion(comprador, publicacion, publicacion.getPrecio(), ZonedDateTime.now());
        transaccion.setEstado(estado);
        transaccionRepository.save(transaccion);
    }

    /**
     * Ejecuta el login real y extrae la cookie JWT que autentica una petición posterior.
     *
     * @param usuario usuario cuyas credenciales se usarán
     * @return cookie HTTP-only JWT emitida por el endpoint de login
     * @throws Exception si el login HTTP falla o no emite la cookie
     */
    private Cookie obtenerCookieJwt(Usuario usuario) throws Exception {
        MvcResult resultado = mockMvc.perform(post("/auth/login")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequestDto(usuario.getEmail(), passwordRaw))))
                .andExpect(status().isOk())
                .andReturn();
        Cookie cookie = resultado.getResponse().getCookie("jwt");
        assertThat(cookie).isNotNull();
        return cookie;
    }
}
