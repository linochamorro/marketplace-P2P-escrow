package com.easymarket.marketplace.controller;

import com.easymarket.marketplace.dto.LoginRequestDto;
import com.easymarket.marketplace.model.Categoria;
import com.easymarket.marketplace.model.EstadoPublicacion;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración TDD para PHA06TSK05 (Story 3, spec.md): corrección y eliminación de
 * publicación propia y el histórico append-only de motivos de moderación.
 *
 * <p>Verifica el contrato HTTP de {@code PATCH /publicaciones/{id}/corregir} (cuerpo exclusivo
 * {@code categoriaId}/{@code subcategoriaId}, reenvío a {@code PENDIENTE_REVISION}) y de
 * {@code DELETE /publicaciones/{id}} (204 al eliminar una {@code RECHAZADA}; 409 vía
 * {@code PublicacionNoEliminableException} en cualquier otro estado). También verifica que la
 * moderación a {@code CAMBIOS_SOLICITADOS} o {@code RECHAZADA} inserta la fila append-only en
 * {@code publicacion_motivos_historicos} con el motivo literal y el admin como actor, y que la
 * moderación a {@code APROBADA} no inserta ninguna.</p>
 *
 * <p>Desde PHA12 (decisión de Lino 2026-08-23) verifica además que el {@code DELETE} sobre una
 * publicación con al menos una transacción asociada responde 409 Conflict vía
 * {@code PublicacionConTransaccionesException}, sin eliminar la fila ni su transacción.</p>
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
public class CorregirEliminarPublicacionControllerIntegrationTests {

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
    private TransaccionRepository transaccionRepository;

    @Autowired
    private com.easymarket.marketplace.repository.AdminAccionRepository adminAccionRepository;

    /** Cliente SQL usado para vaciar fixtures append-only y verificar el histórico de motivos. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Usuario usuarioDuenio;
    private Usuario usuarioAdmin;
    private Categoria categoriaVehiculos;
    private Subcategoria subcategoriaAutos;
    private Categoria categoriaElectronica;
    private Subcategoria subcategoriaSmartphones;
    private final String passwordRaw = "PasswordSeguro123!";

    /**
     * Prepara un contexto HTTP y un conjunto aislado de usuarios, categorías y subcategorías para
     * cada caso de integración.
     *
     * <p>Vacía primero las tablas append-only ({@code publicacion_eventos},
     * {@code publicacion_motivos_historicos}, {@code avisos_envio_pendiente} y
     * {@code notificaciones}) mediante {@code TRUNCATE} — DDL exclusivo de Testcontainers que no
     * dispara los triggers de inmutabilidad — y luego elimina las filas padre en orden referencial.</p>
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

        usuarioDuenio = new Usuario(
                "duenio.prueba@easymarket.com",
                passwordEncoder.encode(passwordRaw),
                Rol.USUARIO,
                0L,
                ZonedDateTime.now(ZoneId.of("America/Lima"))
        );
        usuarioDuenio = usuarioRepository.save(usuarioDuenio);

        usuarioAdmin = new Usuario(
                "admin.test.corregir@easymarket.com",
                passwordEncoder.encode(passwordRaw),
                Rol.ADMIN,
                0L,
                ZonedDateTime.now(ZoneId.of("America/Lima"))
        );
        usuarioAdmin = usuarioRepository.save(usuarioAdmin);

        categoriaVehiculos = categoriaRepository.save(new Categoria("Vehículos"));
        subcategoriaAutos = subcategoriaRepository.save(new Subcategoria(categoriaVehiculos, "Autos"));

        categoriaElectronica = categoriaRepository.save(new Categoria("Electrónica"));
        subcategoriaSmartphones = subcategoriaRepository.save(new Subcategoria(categoriaElectronica, "Smartphones"));
    }

    /**
     * Vacía únicamente tablas de fixture antes de borrar sus filas padre.
     *
     * <p>{@code TRUNCATE} es DDL de limpieza exclusivo de Testcontainers: no ejecuta {@code DELETE}
     * sobre las tablas append-only, por lo que los triggers de V15/V17/V10/V11 permanecen intactos y no se
     * aplica a producción. Se incluyen juntas las tablas relacionadas por FK que PostgreSQL exige
     * truncar en el mismo comando. Desde PHA12 también incluye {@code transacciones} y las tablas
     * que la referencian ({@code transaccion_eventos}, {@code movimientos_saldo},
     * {@code stripe_refund_outbox}, {@code idempotency_keys}) para poder limpiar la transacción
     * de fixture antes del {@code deleteAll} de publicaciones.</p>
     */
    private void limpiarFixturesAppendOnly() {
        jdbcTemplate.execute(
            "TRUNCATE TABLE publicacion_eventos, publicacion_motivos_historicos, avisos_envio_pendiente, "
                + "notificaciones, transacciones, transaccion_eventos, movimientos_saldo, stripe_refund_outbox, "
                + "idempotency_keys RESTART IDENTITY"
        );
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

    /**
     * Crea una publicación persistida con el estado indicado para el dueño de fixture.
     *
     * @param estado estado inicial de la publicación
     * @return entidad persistida
     */
    private Publicacion crearPublicacion(EstadoPublicacion estado) {
        Publicacion publicacion = new Publicacion(
                usuarioDuenio,
                categoriaVehiculos,
                subcategoriaAutos,
                150000L,
                5,
                "Publicación de prueba"
        );
        publicacion.setEstado(estado);
        return publicacionRepository.save(publicacion);
    }

    /**
     * Crea un segundo usuario vendedor distinto del dueño para escenarios de autorización.
     *
     * @return usuario persistido
     */
    private Usuario crearOtroUsuario() {
        return usuarioRepository.save(new Usuario(
                "otro.usuario@easymarket.com",
                passwordEncoder.encode(passwordRaw),
                Rol.USUARIO,
                0L,
                ZonedDateTime.now(ZoneId.of("America/Lima"))
        ));
    }

    /**
     * Crea una transacción {@code reservada} persistida asociada a la publicación indicada,
     * imitando la fila que produce el webhook {@code payment_intent.succeeded} (PHA03TSK08):
     * constructor canónico {@link Transaccion#Transaccion(Usuario, Publicacion, long, ZonedDateTime)}
     * con snapshot del precio de la publicación en centavos enteros (constitution, principio 3).
     *
     * @param comprador usuario comprador (distinto del dueño, coherente con la regla de no auto-compra)
     * @param publicacion publicación adquirida
     * @return transacción persistida en estado {@code RESERVADA}
     */
    private Transaccion crearTransaccionAsociada(Usuario comprador, Publicacion publicacion) {
        return transaccionRepository.saveAndFlush(new Transaccion(
                comprador,
                publicacion,
                publicacion.getPrecio(),
                ZonedDateTime.now(ZoneId.of("America/Lima"))
        ));
    }

    // =========================================================================
    // PATCH /publicaciones/{id}/corregir
    // =========================================================================

    /** Dueño corrige publicación en CAMBIOS_SOLICITADOS y es reenviada a revisión. */
    @Test
    @DisplayName("PATCH corregir de publicación CAMBIOS_SOLICITADOS por el dueño retorna 200 y reenvía a PENDIENTE_REVISION")
    void corregir_DuenioCambiosSolicitados_Retorna200YReenvia() throws Exception {
        Publicacion publicacion = crearPublicacion(EstadoPublicacion.CAMBIOS_SOLICITADOS);
        Cookie cookieDuenio = obtenerCookieJwtPostLogin(usuarioDuenio.getEmail(), passwordRaw);

        mockMvc.perform(patch("/publicaciones/" + publicacion.getId() + "/corregir")
                        .cookie(cookieDuenio)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "categoriaId", categoriaElectronica.getId(),
                                "subcategoriaId", subcategoriaSmartphones.getId()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(publicacion.getId()))
                .andExpect(jsonPath("$.estado").value("PENDIENTE_REVISION"))
                .andExpect(jsonPath("$.categoriaId").value(categoriaElectronica.getId()))
                .andExpect(jsonPath("$.subcategoriaId").value(subcategoriaSmartphones.getId()));

        Publicacion actualizada = publicacionRepository.findById(publicacion.getId()).orElseThrow();
        assertThat(actualizada.getEstado()).isEqualTo(EstadoPublicacion.PENDIENTE_REVISION);
        assertThat(actualizada.getCategoria().getId()).isEqualTo(categoriaElectronica.getId());
        assertThat(actualizada.getSubcategoria().getId()).isEqualTo(subcategoriaSmartphones.getId());
    }

    /** Dueño corrige publicación en RECHAZADA y es reenviada a revisión. */
    @Test
    @DisplayName("PATCH corregir de publicación RECHAZADA por el dueño retorna 200 y reenvía a PENDIENTE_REVISION")
    void corregir_DuenioRechazada_Retorna200YReenvia() throws Exception {
        Publicacion publicacion = crearPublicacion(EstadoPublicacion.RECHAZADA);
        Cookie cookieDuenio = obtenerCookieJwtPostLogin(usuarioDuenio.getEmail(), passwordRaw);

        mockMvc.perform(patch("/publicaciones/" + publicacion.getId() + "/corregir")
                        .cookie(cookieDuenio)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "categoriaId", categoriaElectronica.getId(),
                                "subcategoriaId", subcategoriaSmartphones.getId()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("PENDIENTE_REVISION"))
                .andExpect(jsonPath("$.categoriaId").value(categoriaElectronica.getId()))
                .andExpect(jsonPath("$.subcategoriaId").value(subcategoriaSmartphones.getId()));
    }

    /** Usuario distinto del dueño no puede corregir. */
    @Test
    @DisplayName("PATCH corregir por un usuario que no es el dueño retorna 403 Forbidden")
    void corregir_NoDuenio_Retorna403() throws Exception {
        Usuario otroUsuario = crearOtroUsuario();
        Publicacion publicacion = crearPublicacion(EstadoPublicacion.CAMBIOS_SOLICITADOS);
        Cookie cookieOtro = obtenerCookieJwtPostLogin(otroUsuario.getEmail(), passwordRaw);

        mockMvc.perform(patch("/publicaciones/" + publicacion.getId() + "/corregir")
                        .cookie(cookieOtro)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "categoriaId", categoriaElectronica.getId(),
                                "subcategoriaId", subcategoriaSmartphones.getId()
                        ))))
                .andExpect(status().isForbidden());
    }

    /** La categoría/subcategoría de una publicación aprobada es inmutable. */
    @Test
    @DisplayName("PATCH corregir de publicación APROBADA retorna 400 Bad Request")
    void corregir_Aprobada_Retorna400() throws Exception {
        Publicacion publicacion = crearPublicacion(EstadoPublicacion.APROBADA);
        Cookie cookieDuenio = obtenerCookieJwtPostLogin(usuarioDuenio.getEmail(), passwordRaw);

        mockMvc.perform(patch("/publicaciones/" + publicacion.getId() + "/corregir")
                        .cookie(cookieDuenio)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "categoriaId", categoriaElectronica.getId(),
                                "subcategoriaId", subcategoriaSmartphones.getId()
                        ))))
                .andExpect(status().isBadRequest());
    }

    /** Solo CAMBIOS_SOLICITADOS o RECHAZADA son corregibles. */
    @Test
    @DisplayName("PATCH corregir de publicación PENDIENTE_REVISION retorna 409 Conflict")
    void corregir_PendienteRevision_Retorna409() throws Exception {
        Publicacion publicacion = crearPublicacion(EstadoPublicacion.PENDIENTE_REVISION);
        Cookie cookieDuenio = obtenerCookieJwtPostLogin(usuarioDuenio.getEmail(), passwordRaw);

        mockMvc.perform(patch("/publicaciones/" + publicacion.getId() + "/corregir")
                        .cookie(cookieDuenio)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "categoriaId", categoriaElectronica.getId(),
                                "subcategoriaId", subcategoriaSmartphones.getId()
                        ))))
                .andExpect(status().isConflict());
    }

    /** Categoría inexistente en la corrección. */
    @Test
    @DisplayName("PATCH corregir con categoría inexistente retorna 404 Not Found")
    void corregir_CategoriaInexistente_Retorna404() throws Exception {
        Publicacion publicacion = crearPublicacion(EstadoPublicacion.CAMBIOS_SOLICITADOS);
        Cookie cookieDuenio = obtenerCookieJwtPostLogin(usuarioDuenio.getEmail(), passwordRaw);

        mockMvc.perform(patch("/publicaciones/" + publicacion.getId() + "/corregir")
                        .cookie(cookieDuenio)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "categoriaId", 99999L,
                                "subcategoriaId", subcategoriaSmartphones.getId()
                        ))))
                .andExpect(status().isNotFound());
    }

    /** Subcategoría que no pertenece a la categoría indicada. */
    @Test
    @DisplayName("PATCH corregir con subcategoría no perteneciente a la categoría retorna 400 Bad Request")
    void corregir_SubcategoriaNoPertenece_Retorna400() throws Exception {
        Publicacion publicacion = crearPublicacion(EstadoPublicacion.CAMBIOS_SOLICITADOS);
        Cookie cookieDuenio = obtenerCookieJwtPostLogin(usuarioDuenio.getEmail(), passwordRaw);

        mockMvc.perform(patch("/publicaciones/" + publicacion.getId() + "/corregir")
                        .cookie(cookieDuenio)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "categoriaId", categoriaVehiculos.getId(),
                                "subcategoriaId", subcategoriaSmartphones.getId()
                        ))))
                .andExpect(status().isBadRequest());
    }

    /** Cuerpo sin categoriaId es rechazado por la validación @Valid. */
    @Test
    @DisplayName("PATCH corregir sin categoriaId retorna 400 Bad Request")
    void corregir_SinCategoriaId_Retorna400() throws Exception {
        Publicacion publicacion = crearPublicacion(EstadoPublicacion.CAMBIOS_SOLICITADOS);
        Cookie cookieDuenio = obtenerCookieJwtPostLogin(usuarioDuenio.getEmail(), passwordRaw);

        mockMvc.perform(patch("/publicaciones/" + publicacion.getId() + "/corregir")
                        .cookie(cookieDuenio)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "subcategoriaId", subcategoriaSmartphones.getId()
                        ))))
                .andExpect(status().isBadRequest());
    }

    /** Cuerpo sin subcategoriaId es rechazado por la validación @Valid. */
    @Test
    @DisplayName("PATCH corregir sin subcategoriaId retorna 400 Bad Request")
    void corregir_SinSubcategoriaId_Retorna400() throws Exception {
        Publicacion publicacion = crearPublicacion(EstadoPublicacion.CAMBIOS_SOLICITADOS);
        Cookie cookieDuenio = obtenerCookieJwtPostLogin(usuarioDuenio.getEmail(), passwordRaw);

        mockMvc.perform(patch("/publicaciones/" + publicacion.getId() + "/corregir")
                        .cookie(cookieDuenio)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "categoriaId", categoriaElectronica.getId()
                        ))))
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // DELETE /publicaciones/{id}
    // =========================================================================

    /** Dueño elimina publicación RECHAZADA: 204 y la fila desaparece. */
    @Test
    @DisplayName("DELETE de publicación RECHAZADA por el dueño retorna 204 y elimina la fila")
    void eliminar_RechazadaDuenio_Retorna204YDesaparece() throws Exception {
        Publicacion publicacion = crearPublicacion(EstadoPublicacion.RECHAZADA);
        Cookie cookieDuenio = obtenerCookieJwtPostLogin(usuarioDuenio.getEmail(), passwordRaw);

        mockMvc.perform(delete("/publicaciones/" + publicacion.getId())
                        .cookie(cookieDuenio))
                .andExpect(status().isNoContent());

        assertThat(publicacionRepository.existsById(publicacion.getId())).isFalse();
    }

    /** Dueño elimina publicación APROBADA: 204 y la fila desaparece (cualquier estado es eliminable). */
    @Test
    @DisplayName("DELETE de publicación APROBADA por el dueño retorna 204 y elimina la fila")
    void eliminar_AprobadaDuenio_Retorna204YDesaparece() throws Exception {
        Publicacion publicacion = crearPublicacion(EstadoPublicacion.APROBADA);
        Cookie cookieDuenio = obtenerCookieJwtPostLogin(usuarioDuenio.getEmail(), passwordRaw);

        mockMvc.perform(delete("/publicaciones/" + publicacion.getId())
                        .cookie(cookieDuenio))
                .andExpect(status().isNoContent());

        assertThat(publicacionRepository.existsById(publicacion.getId())).isFalse();
    }

    /** Dueño elimina publicación PENDIENTE_REVISION: 204 y la fila desaparece (cualquier estado es eliminable). */
    @Test
    @DisplayName("DELETE de publicación PENDIENTE_REVISION por el dueño retorna 204 y elimina la fila")
    void eliminar_PendienteRevisionDuenio_Retorna204YDesaparece() throws Exception {
        Publicacion publicacion = crearPublicacion(EstadoPublicacion.PENDIENTE_REVISION);
        Cookie cookieDuenio = obtenerCookieJwtPostLogin(usuarioDuenio.getEmail(), passwordRaw);

        mockMvc.perform(delete("/publicaciones/" + publicacion.getId())
                        .cookie(cookieDuenio))
                .andExpect(status().isNoContent());

        assertThat(publicacionRepository.existsById(publicacion.getId())).isFalse();
    }

    /** Dueño elimina publicación OCULTA: 204 y la fila desaparece (cualquier estado es eliminable). */
    @Test
    @DisplayName("DELETE de publicación OCULTA por el dueño retorna 204 y elimina la fila")
    void eliminar_OcultaDuenio_Retorna204YDesaparece() throws Exception {
        Publicacion publicacion = crearPublicacion(EstadoPublicacion.OCULTA);
        Cookie cookieDuenio = obtenerCookieJwtPostLogin(usuarioDuenio.getEmail(), passwordRaw);

        mockMvc.perform(delete("/publicaciones/" + publicacion.getId())
                        .cookie(cookieDuenio))
                .andExpect(status().isNoContent());

        assertThat(publicacionRepository.existsById(publicacion.getId())).isFalse();
    }

    /** Dueño elimina publicación CAMBIOS_SOLICITADOS: 204 y la fila desaparece (cualquier estado es eliminable). */
    @Test
    @DisplayName("DELETE de publicación CAMBIOS_SOLICITADOS por el dueño retorna 204 y elimina la fila")
    void eliminar_CambiosSolicitadosDuenio_Retorna204YDesaparece() throws Exception {
        Publicacion publicacion = crearPublicacion(EstadoPublicacion.CAMBIOS_SOLICITADOS);
        Cookie cookieDuenio = obtenerCookieJwtPostLogin(usuarioDuenio.getEmail(), passwordRaw);

        mockMvc.perform(delete("/publicaciones/" + publicacion.getId())
                        .cookie(cookieDuenio))
                .andExpect(status().isNoContent());

        assertThat(publicacionRepository.existsById(publicacion.getId())).isFalse();
    }

    /** Usuario distinto del dueño no puede eliminar. */
    @Test
    @DisplayName("DELETE por un usuario que no es el dueño retorna 403 Forbidden")
    void eliminar_NoDuenio_Retorna403() throws Exception {
        Usuario otroUsuario = crearOtroUsuario();
        Publicacion publicacion = crearPublicacion(EstadoPublicacion.RECHAZADA);
        Cookie cookieOtro = obtenerCookieJwtPostLogin(otroUsuario.getEmail(), passwordRaw);

        mockMvc.perform(delete("/publicaciones/" + publicacion.getId())
                        .cookie(cookieOtro))
                .andExpect(status().isForbidden());
    }

    /** Usuario distinto del dueño no puede eliminar (en cualquier estado). */
    @Test
    @DisplayName("DELETE de publicación APROBADA por un usuario que no es el dueño retorna 403 Forbidden")
    void eliminar_AprobadaNoDuenio_Retorna403() throws Exception {
        Usuario otroUsuario = crearOtroUsuario();
        Publicacion publicacion = crearPublicacion(EstadoPublicacion.APROBADA);
        Cookie cookieOtro = obtenerCookieJwtPostLogin(otroUsuario.getEmail(), passwordRaw);

        mockMvc.perform(delete("/publicaciones/" + publicacion.getId())
                        .cookie(cookieOtro))
                .andExpect(status().isForbidden());
    }

    /** Eliminar una publicación inexistente. */
    @Test
    @DisplayName("DELETE de publicación inexistente retorna 404 Not Found")
    void eliminar_Inexistente_Retorna404() throws Exception {
        Cookie cookieDuenio = obtenerCookieJwtPostLogin(usuarioDuenio.getEmail(), passwordRaw);

        mockMvc.perform(delete("/publicaciones/999999")
                        .cookie(cookieDuenio))
                .andExpect(status().isNotFound());
    }

    /** Dueño intenta eliminar una publicación con transacción asociada: 409 y la fila sobrevive (PHA12). */
    @Test
    @DisplayName("DELETE de publicación con transacción asociada retorna 409 Conflict y la publicación sigue existiendo en BD")
    void eliminar_ConTransaccionAsociada_Retorna409YLaPublicacionSigueExistiendo() throws Exception {
        Publicacion publicacion = crearPublicacion(EstadoPublicacion.APROBADA);
        Usuario comprador = crearOtroUsuario();
        crearTransaccionAsociada(comprador, publicacion);
        Cookie cookieDuenio = obtenerCookieJwtPostLogin(usuarioDuenio.getEmail(), passwordRaw);

        mockMvc.perform(delete("/publicaciones/" + publicacion.getId())
                        .cookie(cookieDuenio))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value(
                        "La publicación con ID " + publicacion.getId()
                                + " no puede eliminarse porque tiene al menos una transacción asociada"));

        assertThat(publicacionRepository.existsById(publicacion.getId())).isTrue();
        assertThat(transaccionRepository.existsByPublicacionId(publicacion.getId())).isTrue();
    }

    // =========================================================================
    // Publicacion_motivos_historicos (insert de la moderación, misma transacción)
    // =========================================================================

    /** Moderar a solicitar-cambios inserta el motivo histórico con el admin como actor. */
    @Test
    @DisplayName("Moderar a solicitar-cambios inserta fila CAMBIOS_SOLICITADOS en publicacion_motivos_historicos")
    void moderar_SolicitarCambios_InsertaMotivoHistorico() throws Exception {
        Publicacion publicacion = crearPublicacion(EstadoPublicacion.PENDIENTE_REVISION);
        Cookie cookieAdmin = obtenerCookieJwtPostLogin(usuarioAdmin.getEmail(), passwordRaw);

        mockMvc.perform(patch("/publicaciones/" + publicacion.getId() + "/moderar")
                        .cookie(cookieAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "accion", "solicitar-cambios",
                                "motivo", "La categoría es incorrecta"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("CAMBIOS_SOLICITADOS"));

        Integer filas = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM publicacion_motivos_historicos "
                + "WHERE publicacion_id = ? AND accion = 'CAMBIOS_SOLICITADOS' AND actor_id = ?",
            Integer.class, publicacion.getId(), usuarioAdmin.getId()
        );
        assertThat(filas).isEqualTo(1);
        String motivo = jdbcTemplate.queryForObject(
            "SELECT motivo FROM publicacion_motivos_historicos WHERE publicacion_id = ?",
            String.class, publicacion.getId()
        );
        assertThat(motivo).isEqualTo("La categoría es incorrecta");
    }

    /** Moderar a rechazar inserta el motivo histórico con el admin como actor. */
    @Test
    @DisplayName("Moderar a rechazar inserta fila RECHAZADA en publicacion_motivos_historicos")
    void moderar_Rechazar_InsertaMotivoHistorico() throws Exception {
        Publicacion publicacion = crearPublicacion(EstadoPublicacion.PENDIENTE_REVISION);
        Cookie cookieAdmin = obtenerCookieJwtPostLogin(usuarioAdmin.getEmail(), passwordRaw);

        mockMvc.perform(patch("/publicaciones/" + publicacion.getId() + "/moderar")
                        .cookie(cookieAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "accion", "rechazar",
                                "motivo", "Producto prohibido: armas de fuego"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("RECHAZADA"));

        Integer filas = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM publicacion_motivos_historicos "
                + "WHERE publicacion_id = ? AND accion = 'RECHAZADA' AND actor_id = ?",
            Integer.class, publicacion.getId(), usuarioAdmin.getId()
        );
        assertThat(filas).isEqualTo(1);
        String motivo = jdbcTemplate.queryForObject(
            "SELECT motivo FROM publicacion_motivos_historicos WHERE publicacion_id = ?",
            String.class, publicacion.getId()
        );
        assertThat(motivo).isEqualTo("Producto prohibido: armas de fuego");
    }

    /** Moderar a aprobar no inserta ninguna fila en el histórico de motivos. */
    @Test
    @DisplayName("Moderar a aprobar no inserta fila en publicacion_motivos_historicos")
    void moderar_Aprobar_NoInsertaMotivoHistorico() throws Exception {
        Publicacion publicacion = crearPublicacion(EstadoPublicacion.PENDIENTE_REVISION);
        Cookie cookieAdmin = obtenerCookieJwtPostLogin(usuarioAdmin.getEmail(), passwordRaw);

        mockMvc.perform(patch("/publicaciones/" + publicacion.getId() + "/moderar")
                        .cookie(cookieAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "accion", "aprobar"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("APROBADA"));

        Integer total = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM publicacion_motivos_historicos", Integer.class
        );
        assertThat(total).isZero();
    }
}
