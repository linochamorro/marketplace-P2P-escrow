package com.easymarket.marketplace.controller;

import com.easymarket.marketplace.dto.LoginRequestDto;
import com.easymarket.marketplace.model.Categoria;
import com.easymarket.marketplace.model.EstadoTransaccion;
import com.easymarket.marketplace.model.Publicacion;
import com.easymarket.marketplace.model.Rol;
import com.easymarket.marketplace.model.Subcategoria;
import com.easymarket.marketplace.model.Transaccion;
import com.easymarket.marketplace.model.TransaccionEvento;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.CategoriaRepository;
import com.easymarket.marketplace.repository.PublicacionRepository;
import com.easymarket.marketplace.repository.SubcategoriaRepository;
import com.easymarket.marketplace.repository.TransaccionEventoRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración de los endpoints de envío y entrega de transacciones (PHA04TSK12,
 * Stories 6a y 6b).
 *
 * <p>Usa PostgreSQL real mediante Testcontainers y obtiene la identidad exclusivamente a través
 * de la cookie JWT emitida por el login real. Verifica que el controller delega las transiciones y
 * la auditoría append-only en {@code TransicionEnvioEntregaService}, sin aceptar un actor en el
 * cuerpo HTTP.</p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=clave-secreta-pruebas-envio-entrega-minimo-32-caracteres",
    "ADMIN_EMAIL=admin.seed@easymarket.com",
    "ADMIN_PASSWORD_HASH=$2a$10$R9h/cIPz0gi.URNNXRkh2OPST9/PgBkqquzi.Ss7KIUgO2t0jWMUW"
})
class TransaccionEnvioEntregaControllerIntegrationTests {

    private static final String PASSWORD_RAW = "PasswordSeguro123!";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private CategoriaRepository categoriaRepository;
    @Autowired private SubcategoriaRepository subcategoriaRepository;
    @Autowired private PublicacionRepository publicacionRepository;
    @Autowired private TransaccionRepository transaccionRepository;
    @Autowired private TransaccionEventoRepository transaccionEventoRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;
    private Usuario vendedor;
    private Usuario comprador;
    private Usuario otroUsuario;
    private Categoria categoria;
    private Subcategoria subcategoria;

    /** Configura identidades y catálogo aislados para cada escenario HTTP. */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        String sufijo = Long.toUnsignedString(System.nanoTime());
        vendedor = guardarUsuario("vendedor.envio." + sufijo + "@easymarket.com");
        comprador = guardarUsuario("comprador.envio." + sufijo + "@easymarket.com");
        otroUsuario = guardarUsuario("otro.envio." + sufijo + "@easymarket.com");
        categoria = categoriaRepository.save(new Categoria("Categoría " + sufijo));
        subcategoria = subcategoriaRepository.save(new Subcategoria(categoria, "Subcategoría " + sufijo));
    }

    /**
     * Verifica que el vendedor dueño puede marcar una reserva como enviada y que el servicio deja
     * el evento canónico append-only.
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("PATCH /transacciones/{id}/enviar por vendedor dueño reservado retorna 200 y audita")
    void marcarEnviado_VendedorDuenioReservada_Retorna200YAdita() throws Exception {
        Transaccion transaccion = guardarTransaccion(EstadoTransaccion.RESERVADA);

        mockMvc.perform(patch("/transacciones/{id}/enviar", transaccion.getId())
                        .cookie(obtenerCookieJwtPostLogin(vendedor)))
                .andExpect(status().isOk());

        Transaccion actualizada = transaccionRepository.findById(transaccion.getId()).orElseThrow();
        assertThat(actualizada.getEstado()).isEqualTo(EstadoTransaccion.ENVIADO);
        assertThat(actualizada.getFechaEnviado()).isNotNull();
        assertThat(transaccionEventoRepository.findAll())
                .filteredOn(evento -> evento.getTransaccion().getId().equals(transaccion.getId()))
                .singleElement()
                .satisfies(evento -> assertEvento(evento, vendedor, EstadoTransaccion.RESERVADA,
                        EstadoTransaccion.ENVIADO));
    }

    /**
     * Verifica que entregar acepta una descripción de prueba y la persiste a través del servicio.
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("PATCH /transacciones/{id}/entregar con texto por vendedor enviado retorna 200 y audita")
    void marcarEntregado_VendedorEnviadoConTexto_Retorna200YAdita() throws Exception {
        Transaccion transaccion = guardarTransaccion(EstadoTransaccion.ENVIADO);

        mockMvc.perform(patch("/transacciones/{id}/entregar", transaccion.getId())
                        .cookie(obtenerCookieJwtPostLogin(vendedor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"descripcionPruebaEntrega\":\"Entregado en recepción\"}"))
                .andExpect(status().isOk());

        Transaccion actualizada = transaccionRepository.findById(transaccion.getId()).orElseThrow();
        assertThat(actualizada.getEstado()).isEqualTo(EstadoTransaccion.ENTREGADO);
        assertThat(actualizada.getDescripcionPruebaEntrega()).isEqualTo("Entregado en recepción");
        assertThat(transaccionEventoRepository.findAll())
                .filteredOn(evento -> evento.getTransaccion().getId().equals(transaccion.getId()))
                .singleElement()
                .satisfies(evento -> assertEvento(evento, vendedor, EstadoTransaccion.ENVIADO,
                        EstadoTransaccion.ENTREGADO));
    }

    /**
     * Verifica que el body ausente se traduce a una descripción nula, según el contrato de plan.md.
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("PATCH /transacciones/{id}/entregar sin body permite descripción nula")
    void marcarEntregado_SinBody_Deleganull() throws Exception {
        Transaccion transaccion = guardarTransaccion(EstadoTransaccion.ENVIADO);

        mockMvc.perform(patch("/transacciones/{id}/entregar", transaccion.getId())
                        .cookie(obtenerCookieJwtPostLogin(vendedor)))
                .andExpect(status().isOk());

        assertThat(transaccionRepository.findById(transaccion.getId()).orElseThrow()
                .getDescripcionPruebaEntrega()).isNull();
    }

    /**
     * Verifica que el campo JSON explícitamente nulo se delega como nulo al servicio de dominio.
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("PATCH /transacciones/{id}/entregar con campo null permite descripción nula")
    void marcarEntregado_CampoNull_Deleganull() throws Exception {
        Transaccion transaccion = guardarTransaccion(EstadoTransaccion.ENVIADO);

        mockMvc.perform(patch("/transacciones/{id}/entregar", transaccion.getId())
                        .cookie(obtenerCookieJwtPostLogin(vendedor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"descripcionPruebaEntrega\":null}"))
                .andExpect(status().isOk());

        assertThat(transaccionRepository.findById(transaccion.getId()).orElseThrow()
                .getDescripcionPruebaEntrega()).isNull();
    }

    /**
     * Verifica que un actor autenticado que no es vendedor dueño recibe 403 para enviar y entregar.
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("PATCH enviar y entregar por actor no dueño retornan 403")
    void transiciones_ActorNoEsVendedorDuenio_Retornan403() throws Exception {
        Transaccion reservada = guardarTransaccion(EstadoTransaccion.RESERVADA);
        Transaccion enviada = guardarTransaccion(EstadoTransaccion.ENVIADO);
        Cookie cookieNoDuenio = obtenerCookieJwtPostLogin(otroUsuario);

        mockMvc.perform(patch("/transacciones/{id}/enviar", reservada.getId()).cookie(cookieNoDuenio))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/transacciones/{id}/entregar", enviada.getId()).cookie(cookieNoDuenio))
                .andExpect(status().isForbidden());

        assertThat(transaccionRepository.findById(reservada.getId()).orElseThrow().getEstado())
                .isEqualTo(EstadoTransaccion.RESERVADA);
        assertThat(transaccionRepository.findById(enviada.getId()).orElseThrow().getEstado())
                .isEqualTo(EstadoTransaccion.ENVIADO);
    }

    /**
     * Verifica que los estados no permitidos por la máquina del servicio llegan como conflicto HTTP.
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("PATCH enviar y entregar fuera de orden retornan 409")
    void transiciones_EstadoFueraDeOrden_Retornan409() throws Exception {
        Transaccion enviada = guardarTransaccion(EstadoTransaccion.ENVIADO);
        Transaccion reservada = guardarTransaccion(EstadoTransaccion.RESERVADA);
        Cookie cookieVendedor = obtenerCookieJwtPostLogin(vendedor);

        mockMvc.perform(patch("/transacciones/{id}/enviar", enviada.getId()).cookie(cookieVendedor))
                .andExpect(status().isConflict());
        mockMvc.perform(patch("/transacciones/{id}/entregar", reservada.getId()).cookie(cookieVendedor))
                .andExpect(status().isConflict());
    }

    /**
     * Persiste un usuario regular de prueba.
     *
     * @param email correo único del usuario
     * @return usuario persistido
     */
    private Usuario guardarUsuario(String email) {
        return usuarioRepository.save(new Usuario(email, passwordEncoder.encode(PASSWORD_RAW), Rol.USUARIO,
                0L, ZonedDateTime.now()));
    }

    /**
     * Crea una transacción de la publicación del vendedor y fuerza el estado requerido por el caso.
     *
     * @param estado estado inicial de la transacción
     * @return transacción persistida
     */
    private Transaccion guardarTransaccion(EstadoTransaccion estado) {
        Publicacion publicacion = publicacionRepository.save(new Publicacion(vendedor, categoria, subcategoria,
                125_000L, 1, "Artículo de prueba"));
        Transaccion transaccion = new Transaccion(comprador, publicacion, 125_000L, ZonedDateTime.now());
        transaccion.setEstado(estado);
        if (estado == EstadoTransaccion.ENVIADO) {
            transaccion.setFechaEnviado(ZonedDateTime.now());
        }
        return transaccionRepository.saveAndFlush(transaccion);
    }

    /**
     * Obtiene mediante el endpoint de login la cookie JWT del usuario indicado.
     *
     * @param usuario usuario cuyas credenciales se autentican
     * @return cookie httpOnly {@code jwt} emitida por la aplicación
     * @throws Exception si el login HTTP no se completa correctamente
     */
    private Cookie obtenerCookieJwtPostLogin(Usuario usuario) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequestDto(usuario.getEmail(), PASSWORD_RAW))))
                .andExpect(status().isOk())
                .andReturn();
        Cookie cookie = result.getResponse().getCookie("jwt");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    /**
     * Comprueba los datos de un evento append-only generado por una transición exitosa.
     *
     * @param evento evento persistido a validar
     * @param actor vendedor autenticado que ejecutó la transición
     * @param origen estado anterior esperado
     * @param destino estado nuevo esperado
     */
    private void assertEvento(TransaccionEvento evento, Usuario actor, EstadoTransaccion origen,
                              EstadoTransaccion destino) {
        assertThat(evento.getActor().getId()).isEqualTo(actor.getId());
        assertThat(evento.getEstadoOrigen()).isEqualTo(origen);
        assertThat(evento.getEstadoDestino()).isEqualTo(destino);
        assertThat(evento.getMotivo()).isNull();
    }
}
