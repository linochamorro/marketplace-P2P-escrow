package com.easymarket.marketplace.controller;

import com.easymarket.marketplace.dto.LoginRequestDto;
import com.easymarket.marketplace.model.Categoria;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración TDD para el controlador REST de categorías y subcategorías (Story 4, spec.md).
 *
 * <p>Verifica el cumplimiento estricto de las reglas de negocio y autorización por rol:
 * <ul>
 *   <li>Rechazo con HTTP 403 Forbidden cuando un usuario no administrador intenta crear/editar/eliminar.</li>
 *   <li>Permisión con HTTP 201/200/204 para administradores autenticados con JWT real.</li>
 *   <li>Rechazo con HTTP 409 Conflict si la categoría o subcategoría posee publicaciones asociadas.</li>
 *   <li>Rechazo con HTTP 409 Conflict ante duplicación de nombre.</li>
 * </ul>
 * </p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=clave-secreta-para-pruebas-de-integracion-categorias-admin-min-32-chars",
    "ADMIN_EMAIL=admin.seed@easymarket.com",
    "ADMIN_PASSWORD_HASH=$2a$10$R9h/cIPz0gi.URNNXRkh2OPST9/PgBkqquzi.Ss7KIUgO2t0jWMUW"
})
public class CategoriaControllerIntegrationTests {

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
    private PasswordEncoder passwordEncoder;

    private Usuario usuarioRegular;
    private Usuario usuarioAdmin;
    private final String passwordRaw = "PasswordSeguro123!";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        publicacionRepository.deleteAll();
        subcategoriaRepository.deleteAll();
        categoriaRepository.deleteAll();
        usuarioRepository.deleteAll();

        usuarioRegular = new Usuario(
                "usuario.regular@easymarket.com",
                passwordEncoder.encode(passwordRaw),
                Rol.USUARIO,
                0L,
                ZonedDateTime.now(ZoneId.of("America/Lima"))
        );
        usuarioRegular = usuarioRepository.save(usuarioRegular);

        usuarioAdmin = new Usuario(
                "admin.cat@easymarket.com",
                passwordEncoder.encode(passwordRaw),
                Rol.ADMIN,
                0L,
                ZonedDateTime.now(ZoneId.of("America/Lima"))
        );
        usuarioAdmin = usuarioRepository.save(usuarioAdmin);
    }

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
    @DisplayName("POST /categorias realizado por usuario regular retorna 403 Forbidden")
    void crearCategoria_UsuarioNoAdmin_Retorna403Forbidden() throws Exception {
        Cookie cookieRegular = obtenerCookieJwtPostLogin(usuarioRegular.getEmail(), passwordRaw);

        mockMvc.perform(post("/categorias")
                        .cookie(cookieRegular)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("nombre", "Electrónica"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /categorias por admin retorna 201 Created con DTO de respuesta")
    void crearCategoria_AdminAutenticado_Retorna201Created() throws Exception {
        Cookie cookieAdmin = obtenerCookieJwtPostLogin(usuarioAdmin.getEmail(), passwordRaw);

        mockMvc.perform(post("/categorias")
                        .cookie(cookieAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("nombre", "Hogar y Muebles"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.nombre").value("Hogar y Muebles"));

        assertThat(categoriaRepository.existsByNombre("Hogar y Muebles")).isTrue();
    }

    @Test
    @DisplayName("POST /categorias con nombre duplicado retorna 409 Conflict")
    void crearCategoria_NombreDuplicado_Retorna409Conflict() throws Exception {
        categoriaRepository.save(new Categoria("Ropa y Calzado"));
        Cookie cookieAdmin = obtenerCookieJwtPostLogin(usuarioAdmin.getEmail(), passwordRaw);

        mockMvc.perform(post("/categorias")
                        .cookie(cookieAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("nombre", "Ropa y Calzado"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value("Ya existe una categoría raíz con el nombre 'Ropa y Calzado'"));
    }

    @Test
    @DisplayName("PUT /categorias/{id} por admin actualiza nombre y retorna 200 OK")
    void editarCategoria_AdminAutenticado_Retorna200OK() throws Exception {
        Categoria cat = categoriaRepository.save(new Categoria("Tecno"));
        Cookie cookieAdmin = obtenerCookieJwtPostLogin(usuarioAdmin.getEmail(), passwordRaw);

        mockMvc.perform(put("/categorias/" + cat.getId())
                        .cookie(cookieAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("nombre", "Tecnología"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(cat.getId()))
                .andExpect(jsonPath("$.nombre").value("Tecnología"));
    }

    @Test
    @DisplayName("DELETE /categorias/{id} con publicaciones asociadas retorna 409 Conflict")
    void eliminarCategoria_ConPublicacionesAsociadas_Retorna409Conflict() throws Exception {
        Categoria cat = categoriaRepository.save(new Categoria("Vehículos"));
        Subcategoria subcat = subcategoriaRepository.save(new Subcategoria(cat, "Autos"));

        // Crear una publicación asociada a esta categoría
        Publicacion pub = new Publicacion(
                usuarioRegular,
                cat,
                subcat,
                5000000L,
                1,
                "Hermoso auto"
        );
        publicacionRepository.save(pub);

        Cookie cookieAdmin = obtenerCookieJwtPostLogin(usuarioAdmin.getEmail(), passwordRaw);

        mockMvc.perform(delete("/categorias/" + cat.getId())
                        .cookie(cookieAdmin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value(org.hamcrest.Matchers.containsString("publicaciones asociadas")));
    }

    @Test
    @DisplayName("DELETE /categorias/{id} sin publicaciones asociadas retorna 204 No Content")
    void eliminarCategoria_SinPublicaciones_Retorna204NoContent() throws Exception {
        Categoria cat = categoriaRepository.save(new Categoria("Deportes"));
        Cookie cookieAdmin = obtenerCookieJwtPostLogin(usuarioAdmin.getEmail(), passwordRaw);

        mockMvc.perform(delete("/categorias/" + cat.getId())
                        .cookie(cookieAdmin))
                .andExpect(status().isNoContent());

        assertThat(categoriaRepository.findById(cat.getId())).isEmpty();
    }

    @Test
    @DisplayName("DELETE /categorias/{id} con subcategorías asociadas (sin publicaciones) retorna 409 Conflict")
    void eliminarCategoria_ConSubcategoriasAsociadas_Retorna409Conflict() throws Exception {
        Categoria cat = categoriaRepository.save(new Categoria("Hogar"));
        subcategoriaRepository.save(new Subcategoria(cat, "Cocina"));
        Cookie cookieAdmin = obtenerCookieJwtPostLogin(usuarioAdmin.getEmail(), passwordRaw);

        mockMvc.perform(delete("/categorias/" + cat.getId())
                        .cookie(cookieAdmin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value(org.hamcrest.Matchers.containsString("subcategorías asociadas")));
    }


    @Test
    @DisplayName("Rutas anidadas subcategorías (POST/PUT/DELETE) ejecutadas por admin")
    void gestionSubcategorias_AdminAutenticado_FlujoCompleto() throws Exception {
        Categoria cat = categoriaRepository.save(new Categoria("Computación"));
        Cookie cookieAdmin = obtenerCookieJwtPostLogin(usuarioAdmin.getEmail(), passwordRaw);

        // 1. POST Subcategoría -> 201
        MvcResult resPost = mockMvc.perform(post("/categorias/" + cat.getId() + "/subcategorias")
                        .cookie(cookieAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("nombre", "Laptops"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nombre").value("Laptops"))
                .andExpect(jsonPath("$.categoriaId").value(cat.getId()))
                .andReturn();

        Long subId = objectMapper.readTree(resPost.getResponse().getContentAsString()).get("id").asLong();

        // 2. PUT Subcategoría -> 200
        mockMvc.perform(put("/categorias/" + cat.getId() + "/subcategorias/" + subId)
                        .cookie(cookieAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("nombre", "Laptops Gamer"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Laptops Gamer"));

        // 3. DELETE Subcategoría -> 204
        mockMvc.perform(delete("/categorias/" + cat.getId() + "/subcategorias/" + subId)
                        .cookie(cookieAdmin))
                .andExpect(status().isNoContent());

        assertThat(subcategoriaRepository.findById(subId)).isEmpty();
    }

    @Test
    @DisplayName("GET /categorias público retorna el árbol completo de categorías con subcategorías anidadas ordenadas alfabéticamente")
    void obtenerArbolCategorias_Publico_RetornaArbolCompletoOrdenado() throws Exception {
        Categoria catB = categoriaRepository.save(new Categoria("Hogar"));
        Categoria catA = categoriaRepository.save(new Categoria("Electrónica"));

        subcategoriaRepository.save(new Subcategoria(catA, "Smartphones"));
        subcategoriaRepository.save(new Subcategoria(catA, "Laptops"));

        subcategoriaRepository.save(new Subcategoria(catB, "Muebles"));
        subcategoriaRepository.save(new Subcategoria(catB, "Decoración"));

        // Sin cookie de autenticación (público)
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/categorias")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nombre").value("Electrónica"))
                .andExpect(jsonPath("$[0].subcategorias[0].nombre").value("Laptops"))
                .andExpect(jsonPath("$[0].subcategorias[1].nombre").value("Smartphones"))
                .andExpect(jsonPath("$[1].nombre").value("Hogar"))
                .andExpect(jsonPath("$[1].subcategorias[0].nombre").value("Decoración"))
                .andExpect(jsonPath("$[1].subcategorias[1].nombre").value("Muebles"));
    }
}
