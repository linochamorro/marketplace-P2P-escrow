package com.easymarket.marketplace.controller;

import com.easymarket.marketplace.dto.LoginRequestDto;
import com.easymarket.marketplace.model.Categoria;
import com.easymarket.marketplace.model.EstadoPublicacion;
import com.easymarket.marketplace.model.Publicacion;
import com.easymarket.marketplace.model.Rol;
import com.easymarket.marketplace.model.Subcategoria;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.CategoriaRepository;
import com.easymarket.marketplace.repository.PublicacionRepository;
import com.easymarket.marketplace.repository.SubcategoriaRepository;
import com.easymarket.marketplace.repository.UsuarioRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
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

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


/**
 * Pruebas de integración TDD para el controlador REST {@code POST /publicaciones} (Story 1, spec.md).
 *
 * <p>Verifica la creación de publicaciones por usuarios autenticados:
 * <ul>
 *   <li>Rechazo con HTTP 401 Unauthorized cuando la petición carece de autenticación JWT.</li>
 *   <li>Respuesta HTTP 201 Created con DTO completo para peticiones válidas enviadas por cualquier usuario autenticado.</li>
 *   <li>Rechazo con HTTP 400 Bad Request si el precio es invalido ({@code precio <= 0}).</li>
 *   <li>Rechazo con HTTP 400 Bad Request si el stock es invalido ({@code stock <= 0}).</li>
 *   <li>Rechazo con HTTP 404 Not Found si la categoría especificada no existe.</li>
 *   <li>Rechazo con HTTP 400 Bad Request si la subcategoría no pertenece a la categoría raíz indicada.</li>
 * </ul>
 * </p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=clave-secreta-para-pruebas-de-integracion-publicaciones-min-32-chars",
    "ADMIN_EMAIL=admin.seed@easymarket.com",
    "ADMIN_PASSWORD_HASH=$2a$10$R9h/cIPz0gi.URNNXRkh2OPST9/PgBkqquzi.Ss7KIUgO2t0jWMUW"
})
public class PublicacionControllerIntegrationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private CategoriaRepository categoriaRepository;

    @Autowired
    private SubcategoriaRepository subcategoriaRepository;

    @Autowired
    private PublicacionRepository publicacionRepository;

    @Autowired
    private com.easymarket.marketplace.repository.AdminAccionRepository adminAccionRepository;

    /** Cliente SQL usado solo para vaciar fixtures append-only entre pruebas de integración. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Usuario usuarioRegular;
    private Usuario usuarioAdmin;
    private Categoria categoriaVehiculos;
    private Subcategoria subcategoriaAutos;
    private Subcategoria subcategoriaMotosCatIncompatible;
    private final String passwordRaw = "PasswordSeguro123!";

    /**
     * Prepara un contexto HTTP y un conjunto aislado de usuarios, categorías y subcategorías para
     * cada caso de integración.
     *
     * <p>Antes de recrear los datos padre, vacía las proyecciones y auditorías de fixture mediante
     * {@link #limpiarFixturesAppendOnly()} para que las FKs de los efectos creados por Story 1 no
     * interfieran con otro caso. Esta limpieza existe exclusivamente en Testcontainers.</p>
     */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        limpiarFixturesAppendOnly();
        adminAccionRepository.deleteAll();
        publicacionRepository.deleteAll();
        subcategoriaRepository.deleteAll();
        categoriaRepository.deleteAll();
        usuarioRepository.deleteAll();

        usuarioRegular = new Usuario(
                "vendedor.prueba@easymarket.com",
                passwordEncoder.encode(passwordRaw),
                Rol.USUARIO,
                0L,
                ZonedDateTime.now(ZoneId.of("America/Lima"))
        );
        usuarioRegular = usuarioRepository.save(usuarioRegular);

        // Admin user (rol ADMIN) para tests de endpoints protegidos
        usuarioAdmin = new Usuario(
                "admin.test.publicacion@easymarket.com",
                passwordEncoder.encode(passwordRaw),
                Rol.ADMIN,
                0L,
                ZonedDateTime.now(ZoneId.of("America/Lima"))
        );
        usuarioAdmin = usuarioRepository.save(usuarioAdmin);

        categoriaVehiculos = categoriaRepository.save(new Categoria("Vehículos"));
        subcategoriaAutos = subcategoriaRepository.save(new Subcategoria(categoriaVehiculos, "Autos"));

        Categoria categoriaElectronica = categoriaRepository.save(new Categoria("Electrónica"));
        subcategoriaMotosCatIncompatible = subcategoriaRepository.save(new Subcategoria(categoriaElectronica, "Smartphones"));
    }

    /**
     * Vacía únicamente tablas de fixture antes de borrar sus filas padre.
     *
     * <p>{@code TRUNCATE} es DDL de limpieza exclusivo de Testcontainers: no ejecuta {@code DELETE}
     * sobre {@code publicacion_eventos}, por lo que el trigger append-only de V15 permanece intacto
     * y no se aplica a producción. Se incluye {@code avisos_envio_pendiente} porque referencia
     * {@code notificaciones} y PostgreSQL exige truncar juntas las tablas relacionadas.</p>
     */
    private void limpiarFixturesAppendOnly() {
        jdbcTemplate.execute("TRUNCATE TABLE publicacion_eventos, avisos_envio_pendiente, notificaciones RESTART IDENTITY");
    }

    /**
     * Inicia sesión mediante MockMvc y obtiene la cookie JWT emitida para las solicitudes autenticadas del escenario.
     *
     * @param email correo de la cuenta de prueba que inicia sesión
     * @param password contraseña en texto plano de la cuenta de prueba
     * @return cookie {@code jwt} devuelta por el endpoint de inicio de sesión
     * @throws Exception si MockMvc o la serialización de la solicitud no pueden completar el inicio de sesión
     */
    private Cookie obtenerCookieJwtPostLogin(String email, String password) throws Exception {
        LoginRequestDto loginRequest = new LoginRequestDto(email, password);

        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        Cookie cookieJwt = result.getResponse().getCookie("jwt");
        assertThat(cookieJwt).isNotNull();
        return cookieJwt;
    }

    @Test
    @DisplayName("POST /publicaciones sin autenticación retorna 403 Forbidden")
    void crearPublicacion_UsuarioNoAutenticado_Retorna403Forbidden() throws Exception {
        mockMvc.perform(post("/publicaciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "precio", 1500000L,
                                "stock", 2,
                                "categoriaId", categoriaVehiculos.getId(),
                                "subcategoriaId", subcategoriaAutos.getId(),
                                "descripcion", "Auto sedan en excelente estado"
                        ))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /publicaciones por usuario autenticado con datos válidos retorna 201 Created")
    void crearPublicacion_UsuarioAutenticadoRequestValido_Retorna201Created() throws Exception {
        Cookie cookieVendedor = obtenerCookieJwtPostLogin(usuarioRegular.getEmail(), passwordRaw);

        mockMvc.perform(post("/publicaciones")
                        .cookie(cookieVendedor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "precio", 2500000L,
                                "stock", 3,
                                "categoriaId", categoriaVehiculos.getId(),
                                "subcategoriaId", subcategoriaAutos.getId(),
                                "descripcion", "Toyota Corolla 2022 seminuevo"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.precio").value(2500000L))
                .andExpect(jsonPath("$.stock").value(3))
                .andExpect(jsonPath("$.estado").value("PENDIENTE_REVISION"))
                .andExpect(jsonPath("$.descripcion").value("Toyota Corolla 2022 seminuevo"))
                .andExpect(jsonPath("$.categoriaId").value(categoriaVehiculos.getId()))
                .andExpect(jsonPath("$.subcategoriaId").value(subcategoriaAutos.getId()))
                .andExpect(jsonPath("$.usuarioId").value(usuarioRegular.getId()));

        assertThat(publicacionRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("POST /publicaciones con precio inválido (<= 0) retorna 400 Bad Request")
    void crearPublicacion_PrecioInvalido_Retorna400BadRequest() throws Exception {
        Cookie cookieVendedor = obtenerCookieJwtPostLogin(usuarioRegular.getEmail(), passwordRaw);

        mockMvc.perform(post("/publicaciones")
                        .cookie(cookieVendedor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "precio", 0L,
                                "stock", 2,
                                "categoriaId", categoriaVehiculos.getId(),
                                "subcategoriaId", subcategoriaAutos.getId(),
                                "descripcion", "Publicación con precio cero"
                        ))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /publicaciones con stock inválido (<= 0) retorna 400 Bad Request")
    void crearPublicacion_StockInvalido_Retorna400BadRequest() throws Exception {
        Cookie cookieVendedor = obtenerCookieJwtPostLogin(usuarioRegular.getEmail(), passwordRaw);

        mockMvc.perform(post("/publicaciones")
                        .cookie(cookieVendedor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "precio", 100000L,
                                "stock", 0,
                                "categoriaId", categoriaVehiculos.getId(),
                                "subcategoriaId", subcategoriaAutos.getId(),
                                "descripcion", "Publicación con stock cero"
                        ))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /publicaciones con categoría inexistente retorna 404 Not Found")
    void crearPublicacion_CategoriaInexistente_Retorna404NotFound() throws Exception {
        Cookie cookieVendedor = obtenerCookieJwtPostLogin(usuarioRegular.getEmail(), passwordRaw);

        mockMvc.perform(post("/publicaciones")
                        .cookie(cookieVendedor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "precio", 50000L,
                                "stock", 1,
                                "categoriaId", 99999L,
                                "subcategoriaId", subcategoriaAutos.getId(),
                                "descripcion", "Categoría fantasma"
                        ))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /publicaciones cuando la subcategoría no pertenece a la categoría retorna 400 Bad Request")
    void crearPublicacion_SubcategoriaNoPerteneceACategoria_Retorna400BadRequest() throws Exception {
        Cookie cookieVendedor = obtenerCookieJwtPostLogin(usuarioRegular.getEmail(), passwordRaw);

        mockMvc.perform(post("/publicaciones")
                        .cookie(cookieVendedor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "precio", 50000L,
                                "stock", 1,
                                "categoriaId", categoriaVehiculos.getId(),
                                "subcategoriaId", subcategoriaMotosCatIncompatible.getId(),
                                "descripcion", "Subcategoría cruzada incompatible"
                        ))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH /publicaciones/{id}/moderar sin autenticación retorna 403 Forbidden")
    void moderarPublicacion_SinAutenticacion_Retorna403Forbidden() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/publicaciones/1/moderar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("accion", "aprobar"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PATCH /publicaciones/{id}/moderar con rol USUARIO (no ADMIN) retorna 403 Forbidden")
    void moderarPublicacion_UsuarioNoAdmin_Retorna403Forbidden() throws Exception {
        Cookie cookieVendedor = obtenerCookieJwtPostLogin(usuarioRegular.getEmail(), passwordRaw);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/publicaciones/1/moderar")
                        .cookie(cookieVendedor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("accion", "aprobar"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PATCH /publicaciones/{id}/moderar por ADMIN aprueba publicación correctamente (200 OK) y registra auditoría admin_acciones")
    void moderarPublicacion_AdminAprobar_Retorna200OKYRegistraAuditoria() throws Exception {
        // Usuario Admin real sembrado o creado
        Usuario admin = usuarioRepository.save(new Usuario(
                "admin.modera@easymarket.com",
                passwordEncoder.encode(passwordRaw),
                Rol.ADMIN,
                0L,
                ZonedDateTime.now(ZoneId.of("America/Lima"))
        ));

        com.easymarket.marketplace.model.Publicacion publicacion = publicacionRepository.save(
                new com.easymarket.marketplace.model.Publicacion(
                        usuarioRegular,
                        categoriaVehiculos,
                        subcategoriaAutos,
                        100000L,
                        1,
                        "Producto para aprobar"
                )
        );

        Cookie cookieAdmin = obtenerCookieJwtPostLogin(admin.getEmail(), passwordRaw);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/publicaciones/" + publicacion.getId() + "/moderar")
                        .cookie(cookieAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("accion", "aprobar"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(publicacion.getId()))
                .andExpect(jsonPath("$.estado").value("APROBADA"));

        com.easymarket.marketplace.model.Publicacion actualizada = publicacionRepository.findById(publicacion.getId()).orElseThrow();
        assertThat(actualizada.getEstado()).isEqualTo(com.easymarket.marketplace.model.EstadoPublicacion.APROBADA);

        assertThat(adminAccionRepository.findAll())
                .extracting(com.easymarket.marketplace.model.AdminAccion::getAccion)
                .contains("MODERACION_APROBAR");
    }

    @Test
    @DisplayName("PATCH /publicaciones/{id}/moderar por ADMIN solicita cambios con motivo (200 OK)")
    void moderarPublicacion_AdminSolicitarCambiosConMotivo_Retorna200OK() throws Exception {
        Usuario admin = usuarioRepository.save(new Usuario(
                "admin.modera2@easymarket.com",
                passwordEncoder.encode(passwordRaw),
                Rol.ADMIN,
                0L,
                ZonedDateTime.now(ZoneId.of("America/Lima"))
        ));

        com.easymarket.marketplace.model.Publicacion publicacion = publicacionRepository.save(
                new com.easymarket.marketplace.model.Publicacion(
                        usuarioRegular,
                        categoriaVehiculos,
                        subcategoriaAutos,
                        100000L,
                        1,
                        "Producto para solicitar cambios"
                )
        );

        Cookie cookieAdmin = obtenerCookieJwtPostLogin(admin.getEmail(), passwordRaw);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/publicaciones/" + publicacion.getId() + "/moderar")
                        .cookie(cookieAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "accion", "solicitar-cambios",
                                "motivo", "Mejorar la descripción del producto"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("CAMBIOS_SOLICITADOS"));
    }

    @Test
    @DisplayName("PATCH /publicaciones/{id}/moderar sin motivo en solicitar-cambios retorna 400 Bad Request")
    void moderarPublicacion_AdminSolicitarCambiosSinMotivo_Retorna400BadRequest() throws Exception {
        Usuario admin = usuarioRepository.save(new Usuario(
                "admin.modera3@easymarket.com",
                passwordEncoder.encode(passwordRaw),
                Rol.ADMIN,
                0L,
                ZonedDateTime.now(ZoneId.of("America/Lima"))
        ));

        com.easymarket.marketplace.model.Publicacion publicacion = publicacionRepository.save(
                new com.easymarket.marketplace.model.Publicacion(
                        usuarioRegular,
                        categoriaVehiculos,
                        subcategoriaAutos,
                        100000L,
                        1,
                        "Producto para solicitar cambios sin motivo"
                )
        );

        Cookie cookieAdmin = obtenerCookieJwtPostLogin(admin.getEmail(), passwordRaw);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/publicaciones/" + publicacion.getId() + "/moderar")
                        .cookie(cookieAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "accion", "solicitar-cambios"
                        ))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH /publicaciones/{id}/moderar cuando la publicación no está pendiente_revisión retorna 409 Conflict")
    void moderarPublicacion_EstadoNoPendienteRevision_Retorna409Conflict() throws Exception {
        Usuario admin = usuarioRepository.save(new Usuario(
                "admin.modera4@easymarket.com",
                passwordEncoder.encode(passwordRaw),
                Rol.ADMIN,
                0L,
                ZonedDateTime.now(ZoneId.of("America/Lima"))
        ));

        com.easymarket.marketplace.model.Publicacion publicacion = publicacionRepository.save(
                new com.easymarket.marketplace.model.Publicacion(
                        usuarioRegular,
                        categoriaVehiculos,
                        subcategoriaAutos,
                        100000L,
                        1,
                        "Producto ya aprobado"
                )
        );
        publicacion.setEstado(com.easymarket.marketplace.model.EstadoPublicacion.APROBADA);
        publicacionRepository.save(publicacion);

        Cookie cookieAdmin = obtenerCookieJwtPostLogin(admin.getEmail(), passwordRaw);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/publicaciones/" + publicacion.getId() + "/moderar")
                        .cookie(cookieAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("accion", "aprobar"))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("PATCH /publicaciones/{id} por usuario que no es el dueño retorna 403 Forbidden")
    void editarPublicacion_NoEsElDuenio_Retorna403Forbidden() throws Exception {
        Usuario otroVendedor = usuarioRepository.save(new Usuario(
                "otro.vendedor@easymarket.com",
                passwordEncoder.encode(passwordRaw),
                Rol.USUARIO,
                0L,
                ZonedDateTime.now(ZoneId.of("America/Lima"))
        ));

        com.easymarket.marketplace.model.Publicacion publicacion = publicacionRepository.save(
                new com.easymarket.marketplace.model.Publicacion(
                        usuarioRegular,
                        categoriaVehiculos,
                        subcategoriaAutos,
                        100000L,
                        5,
                        "Publicación original"
                )
        );
        publicacion.setEstado(com.easymarket.marketplace.model.EstadoPublicacion.APROBADA);
        publicacionRepository.save(publicacion);

        Cookie cookieOtroVendedor = obtenerCookieJwtPostLogin(otroVendedor.getEmail(), passwordRaw);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/publicaciones/" + publicacion.getId())
                        .cookie(cookieOtroVendedor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "precio", 120000L
                        ))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PATCH /publicaciones/{id} por dueño edita precio, stock y descripción en estado APROBADA (200 OK)")
    void editarPublicacion_DuenioRequestValido_Retorna200OK() throws Exception {
        com.easymarket.marketplace.model.Publicacion publicacion = publicacionRepository.save(
                new com.easymarket.marketplace.model.Publicacion(
                        usuarioRegular,
                        categoriaVehiculos,
                        subcategoriaAutos,
                        100000L,
                        5,
                        "Descripción previa"
                )
        );
        publicacion.setEstado(com.easymarket.marketplace.model.EstadoPublicacion.APROBADA);
        publicacionRepository.save(publicacion);

        Cookie cookieDuenio = obtenerCookieJwtPostLogin(usuarioRegular.getEmail(), passwordRaw);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/publicaciones/" + publicacion.getId())
                        .cookie(cookieDuenio)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "precio", 150000L,
                                "stock", 10,
                                "descripcion", "Descripción editada por el vendedor"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(publicacion.getId()))
                .andExpect(jsonPath("$.precio").value(150000L))
                .andExpect(jsonPath("$.stock").value(10))
                .andExpect(jsonPath("$.descripcion").value("Descripción editada por el vendedor"))
                .andExpect(jsonPath("$.estado").value("APROBADA"));
    }

    @Test
    @DisplayName("PATCH /publicaciones/{id} cuando stock se edita a 0 transiciona automáticamente a OCULTA (Story 10)")
    void editarPublicacion_StockCero_TransicionaAEstadoOculta() throws Exception {
        com.easymarket.marketplace.model.Publicacion publicacion = publicacionRepository.save(
                new com.easymarket.marketplace.model.Publicacion(
                        usuarioRegular,
                        categoriaVehiculos,
                        subcategoriaAutos,
                        100000L,
                        5,
                        "Producto para agotar"
                )
        );
        publicacion.setEstado(com.easymarket.marketplace.model.EstadoPublicacion.APROBADA);
        publicacionRepository.save(publicacion);

        Cookie cookieDuenio = obtenerCookieJwtPostLogin(usuarioRegular.getEmail(), passwordRaw);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/publicaciones/" + publicacion.getId())
                        .cookie(cookieDuenio)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "stock", 0
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(0))
                .andExpect(jsonPath("$.estado").value("OCULTA"));
    }

    @Test
    @DisplayName("PATCH /publicaciones/{id} en estado no APROBADA retorna 409 Conflict")
    void editarPublicacion_EstadoNoAprobada_Retorna409Conflict() throws Exception {
        com.easymarket.marketplace.model.Publicacion publicacion = publicacionRepository.save(
                new com.easymarket.marketplace.model.Publicacion(
                        usuarioRegular,
                        categoriaVehiculos,
                        subcategoriaAutos,
                        100000L,
                        5,
                        "Producto pendiente"
                )
        ); // Estado por defecto PENDIENTE_REVISION

        Cookie cookieDuenio = obtenerCookieJwtPostLogin(usuarioRegular.getEmail(), passwordRaw);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/publicaciones/" + publicacion.getId())
                        .cookie(cookieDuenio)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "precio", 150000L
                        ))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("PATCH /publicaciones/{id} en publicación ya OCULTA sin enviar stock edita precio/descripción (200 OK)")
    void editarPublicacion_PublicacionYaOcultaSinStock_Retorna200OK() throws Exception {
        com.easymarket.marketplace.model.Publicacion publicacion = publicacionRepository.save(
                new com.easymarket.marketplace.model.Publicacion(
                        usuarioRegular,
                        categoriaVehiculos,
                        subcategoriaAutos,
                        100000L,
                        0,
                        "Producto oculto previamente"
                )
        );
        publicacion.setEstado(com.easymarket.marketplace.model.EstadoPublicacion.OCULTA);
        publicacionRepository.save(publicacion);

        Cookie cookieDuenio = obtenerCookieJwtPostLogin(usuarioRegular.getEmail(), passwordRaw);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/publicaciones/" + publicacion.getId())
                        .cookie(cookieDuenio)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "descripcion", "Descripción editada en publicación ya oculta"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(publicacion.getId()))
                .andExpect(jsonPath("$.descripcion").value("Descripción editada en publicación ya oculta"))
                .andExpect(jsonPath("$.stock").value(0))
                .andExpect(jsonPath("$.estado").value("OCULTA"));
    }

    // =========================================================================
    // PHA02TSK16 — GET /publicaciones?estado=X (Story 2, spec.md)
    // =========================================================================

    /**
     * Verifica que un usuario no autenticado que solicita publicaciones en estado
     * {@code PENDIENTE_REVISION} recibe HTTP 403 Forbidden (Story 2, spec.md).
     *
     * <p>Un usuario anónimo no puede ver publicaciones pendientes de revisión porque
     * ese estado es exclusivo del flujo de moderación admin.</p>
     *
     * @throws Exception si falla la petición HTTP
     */
    @Test
    @DisplayName("GET /publicaciones?estado=PENDIENTE_REVISION sin autenticación retorna 403 Forbidden")
    void listarPorEstado_SinAutenticacion_PendienteRevision_Retorna403() throws Exception {
        mockMvc.perform(get("/publicaciones")
                        .param("estado", "PENDIENTE_REVISION"))
                .andExpect(status().isForbidden());
    }

    /**
     * Verifica que un usuario con rol {@code USUARIO} que solicita publicaciones en estado
     * {@code PENDIENTE_REVISION} recibe HTTP 403 Forbidden (Story 2, spec.md).
     *
     * <p>Solo los administradores tienen permiso para ver publicaciones pendientes de moderación.
     * Un vendedor o comprador autenticado no puede acceder a la cola de moderación.</p>
     *
     * @throws Exception si falla la petición HTTP
     */
    @Test
    @DisplayName("GET /publicaciones?estado=PENDIENTE_REVISION por usuario no-admin retorna 403 Forbidden")
    void listarPorEstado_UsuarioNoAdmin_PendienteRevision_Retorna403() throws Exception {
        Cookie cookieUsuario = obtenerCookieJwtPostLogin(usuarioRegular.getEmail(), passwordRaw);

        mockMvc.perform(get("/publicaciones")
                        .cookie(cookieUsuario)
                        .param("estado", "PENDIENTE_REVISION"))
                .andExpect(status().isForbidden());
    }

    /**
     * Verifica que un usuario con rol {@code ADMIN} que solicita publicaciones en estado
     * {@code PENDIENTE_REVISION} recibe HTTP 200 OK con la lista correcta de publicaciones (Story 2, spec.md).
     *
     * <p>El admin debe ver exactamente las publicaciones en ese estado — ni más, ni menos.</p>
     *
     * @throws Exception si falla la petición HTTP
     */
    @Test
    @DisplayName("GET /publicaciones?estado=PENDIENTE_REVISION por admin retorna 200 OK con publicaciones correctas")
    void listarPorEstado_Admin_PendienteRevision_Retorna200ConPublicaciones() throws Exception {
        // Crear una publicación en PENDIENTE_REVISION (estado inicial)
        Publicacion pendiente = new Publicacion(
                usuarioRegular,
                categoriaVehiculos,
                subcategoriaAutos,
                3500000L,
                2,
                "Moto Honda CB500 en excelente estado"
        );
        publicacionRepository.save(pendiente);

        // Crear otra publicación en APROBADA (NO debe aparecer en el resultado)
        Publicacion aprobada = new Publicacion(
                usuarioRegular,
                categoriaVehiculos,
                subcategoriaAutos,
                1500000L,
                1,
                "Bicicleta de montaña usada"
        );
        aprobada.setEstado(EstadoPublicacion.APROBADA);
        publicacionRepository.save(aprobada);

        Cookie cookieAdmin = obtenerCookieJwtPostLogin(usuarioAdmin.getEmail(), passwordRaw);

        mockMvc.perform(get("/publicaciones")
                        .cookie(cookieAdmin)
                        .param("estado", "PENDIENTE_REVISION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(pendiente.getId()))
                .andExpect(jsonPath("$[0].estado").value("PENDIENTE_REVISION"));
    }

    /**
     * Verifica que un usuario autenticado (rol {@code USUARIO}) que solicita publicaciones
     * en estado {@code APROBADA} recibe HTTP 200 OK con solo las publicaciones en ese estado (Story 2, spec.md).
     *
     * <p>Las publicaciones aprobadas constituyen el catálogo público del marketplace;
     * cualquier usuario autenticado puede verlas.</p>
     *
     * @throws Exception si falla la petición HTTP
     */
    @Test
    @DisplayName("GET /publicaciones?estado=APROBADA por usuario autenticado retorna 200 OK con solo aprobadas")
    void listarPorEstado_UsuarioAutenticado_Aprobada_Retorna200() throws Exception {
        // Crear publicación en PENDIENTE_REVISION (NO debe aparecer)
        Publicacion pendiente = new Publicacion(
                usuarioRegular,
                categoriaVehiculos,
                subcategoriaAutos,
                1000000L,
                1,
                "Tablet pendiente de revisión"
        );
        publicacionRepository.save(pendiente);

        // Crear publicación en APROBADA (SÍ debe aparecer)
        Publicacion aprobada = new Publicacion(
                usuarioRegular,
                categoriaVehiculos,
                subcategoriaAutos,
                2000000L,
                3,
                "Laptop aprobada en catálogo"
        );
        aprobada.setEstado(EstadoPublicacion.APROBADA);
        publicacionRepository.save(aprobada);

        Cookie cookieUsuario = obtenerCookieJwtPostLogin(usuarioRegular.getEmail(), passwordRaw);

        mockMvc.perform(get("/publicaciones")
                        .cookie(cookieUsuario)
                        .param("estado", "APROBADA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(aprobada.getId()))
                .andExpect(jsonPath("$[0].estado").value("APROBADA"));
    }

    /**
     * Verifica que una petición con valor de estado que no pertenece al enum
     * {@link EstadoPublicacion} recibe HTTP 400 Bad Request con mensaje explicativo (Story 2, spec.md).
     *
     * @throws Exception si falla la petición HTTP
     */
    @Test
    @DisplayName("GET /publicaciones?estado=ESTADO_INEXISTENTE retorna 400 Bad Request")
    void listarPorEstado_EstadoInvalido_Retorna400() throws Exception {
        Cookie cookieUsuario = obtenerCookieJwtPostLogin(usuarioRegular.getEmail(), passwordRaw);

        mockMvc.perform(get("/publicaciones")
                        .cookie(cookieUsuario)
                        .param("estado", "ESTADO_INEXISTENTE"))
                .andExpect(status().isBadRequest());
    }

    /**
     * Verifica que una petición a {@code GET /publicaciones} sin parámetro {@code estado}
     * se resuelve por el listado público de Story 11 y recibe HTTP 200 OK.
     *
     * @throws Exception si falla la petición HTTP
     */
    @Test
    @DisplayName("GET /publicaciones sin parámetro estado retorna 200 OK mediante el listado público")
    void listarSinEstado_UsaListadoPublico_Retorna200() throws Exception {
        Cookie cookieUsuario = obtenerCookieJwtPostLogin(usuarioRegular.getEmail(), passwordRaw);

        mockMvc.perform(get("/publicaciones")
                        .cookie(cookieUsuario))
                .andExpect(status().isOk());
    }

    // =========================================================================================
    // PHA02TSK17: Listar publicaciones del usuario autenticado (GET /publicaciones/mias)
    // =========================================================================================

    /**
     * Verifica que un usuario autenticado puede listar sus propias publicaciones
     * y no ve las de otros usuarios.
     *
     * @throws Exception si falla la petición HTTP
     */
    @Test
    @DisplayName("GET /publicaciones/mias por usuario autenticado retorna 200 OK con sus publicaciones ordenadas por fecha descendente")
    void listarPorUsuario_UsuarioAutenticado_Retorna200ConPublicaciones() throws Exception {
        // Publicación del usuario regular (debería aparecer, más antigua)
        Publicacion miPublicacionAntigua = new Publicacion(
                usuarioRegular, categoriaVehiculos, subcategoriaAutos, 1000000L, 1, "Mi auto viejo"
        );
        miPublicacionAntigua.setEstado(EstadoPublicacion.APROBADA);
        publicacionRepository.save(miPublicacionAntigua);

        // Publicación del usuario regular (debería aparecer, más reciente, en estado RECHAZADA)
        Publicacion miPublicacionReciente = new Publicacion(
                usuarioRegular, categoriaVehiculos, subcategoriaAutos, 2000000L, 2, "Mi auto nuevo"
        );
        miPublicacionReciente.setEstado(EstadoPublicacion.RECHAZADA);
        publicacionRepository.save(miPublicacionReciente);

        // Publicación de OTRO usuario (el admin, NO debería aparecer)
        Publicacion publicacionAdmin = new Publicacion(
                usuarioAdmin, categoriaVehiculos, subcategoriaAutos, 5000000L, 1, "Auto del admin"
        );
        publicacionRepository.save(publicacionAdmin);

        Cookie cookieUsuario = obtenerCookieJwtPostLogin(usuarioRegular.getEmail(), passwordRaw);

        mockMvc.perform(get("/publicaciones/mias")
                        .cookie(cookieUsuario))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                // La más reciente primero
                .andExpect(jsonPath("$[0].id").value(miPublicacionReciente.getId()))
                .andExpect(jsonPath("$[0].estado").value("RECHAZADA"))
                .andExpect(jsonPath("$[1].id").value(miPublicacionAntigua.getId()))
                .andExpect(jsonPath("$[1].estado").value("APROBADA"));
    }

    /**
     * Verifica que un usuario sin publicaciones recibe 200 OK con un array vacío.
     *
     * @throws Exception si falla la petición HTTP
     */
    @Test
    @DisplayName("GET /publicaciones/mias sin publicaciones retorna 200 OK con array vacío")
    void listarPorUsuario_UsuarioSinPublicaciones_Retorna200ConListaVacia() throws Exception {
        Cookie cookieAdmin = obtenerCookieJwtPostLogin(usuarioAdmin.getEmail(), passwordRaw);

        mockMvc.perform(get("/publicaciones/mias")
                        .cookie(cookieAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /**
     * Verifica que un usuario no autenticado recibe HTTP 403 Forbidden.
     *
     * @throws Exception si falla la petición HTTP
     */
    @Test
    @DisplayName("GET /publicaciones/mias sin autenticación retorna 403 Forbidden")
    void listarPorUsuario_SinAutenticacion_Retorna403() throws Exception {
        mockMvc.perform(get("/publicaciones/mias"))
                .andExpect(status().isForbidden());
    }
}
