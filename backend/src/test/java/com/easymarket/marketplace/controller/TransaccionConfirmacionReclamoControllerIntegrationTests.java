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
import com.easymarket.marketplace.repository.MovimientoSaldoRepository;
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
 * Pruebas de integración de confirmación de recepción y reclamo (PHA04TSK13, Stories 6c y 6d).
 *
 * <p>Usa PostgreSQL real mediante Testcontainers e identidades emitidas por el login JWT real.
 * Verifica que los endpoints obtienen el actor exclusivamente de la cookie JWT y delegan los
 * fondos, las transiciones y la auditoría append-only a sus respectivos servicios de dominio.</p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=clave-secreta-pruebas-confirmar-reclamar-minimo-32-caracteres",
    "ADMIN_EMAIL=admin.seed@easymarket.com",
    "ADMIN_PASSWORD_HASH=$2a$10$R9h/cIPz0gi.URNNXRkh2OPST9/PgBkqquzi.Ss7KIUgO2t0jWMUW"
})
class TransaccionConfirmacionReclamoControllerIntegrationTests {

    private static final String PASSWORD_RAW = "PasswordSeguro123!";
    private static final long PRECIO_CENTAVOS = 125_000L;

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
    @Autowired private MovimientoSaldoRepository movimientoSaldoRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;
    private Usuario vendedor;
    private Usuario comprador;
    private Usuario tercero;
    /**
     * Identidad auxiliar no compradora para verificar el rechazo 403 de los endpoints.
     *
     * <p>Nace con {@link Rol#USUARIO}, no con {@code ADMIN}: el sistema garantiza una única
     * cuenta ADMIN provisionada fuera del registro público (invariante declarada en
     * {@code UsuarioRepository.findByRol}, PHA06TSK02), y desde PHA12TSK03 el reclamo exitoso
     * emite DISPUTA_PENDIENTE_RESOLVER al admin vía {@code findByRol(Rol.ADMIN)}, que exige
     * resultado único. Crear ADMINs adicionales aquí rompería esa invariante.</p>
     */
    private Usuario auxiliar;
    private Categoria categoria;
    private Subcategoria subcategoria;

    /** Configura usuarios y catálogo aislados para cada petición HTTP. */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        String sufijo = Long.toUnsignedString(System.nanoTime());
        vendedor = guardarUsuario("vendedor.confirmacion." + sufijo + "@easymarket.com", Rol.USUARIO);
        comprador = guardarUsuario("comprador.confirmacion." + sufijo + "@easymarket.com", Rol.USUARIO);
        tercero = guardarUsuario("tercero.confirmacion." + sufijo + "@easymarket.com", Rol.USUARIO);
        auxiliar = guardarUsuario("auxiliar.confirmacion." + sufijo + "@easymarket.com", Rol.USUARIO);
        categoria = categoriaRepository.save(new Categoria("Categoría " + sufijo));
        subcategoria = subcategoriaRepository.save(new Subcategoria(categoria, "Subcategoría " + sufijo));
    }

    /**
     * Confirma por HTTP la recepción dentro de plazo y comprueba el crédito y evento del servicio.
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("PATCH confirmar por comprador entregado retorna 200, acredita y audita")
    void confirmar_CompradorEntregado_Retorna200AcreditaYAdita() throws Exception {
        Transaccion transaccion = guardarTransaccion(EstadoTransaccion.ENTREGADO, ZonedDateTime.now());

        mockMvc.perform(patch("/transacciones/{id}/confirmar", transaccion.getId())
                        .cookie(obtenerCookieJwtPostLogin(comprador)))
                .andExpect(status().isOk());

        assertThat(transaccionRepository.findById(transaccion.getId()).orElseThrow().getEstado())
                .isEqualTo(EstadoTransaccion.RECIBIDO);
        assertThat(usuarioRepository.findById(vendedor.getId()).orElseThrow().getSaldoDisponible())
                .isEqualTo(PRECIO_CENTAVOS);
        assertThat(movimientoSaldoRepository.findByVendedorId(vendedor.getId()))
                .singleElement().satisfies(movimiento -> assertThat(movimiento.getMonto()).isEqualTo(PRECIO_CENTAVOS));
        assertEvento(transaccion.getId(), comprador, EstadoTransaccion.ENTREGADO, EstadoTransaccion.RECIBIDO, null);
        assertThat(publicacionRepository.findById(transaccion.getPublicacion().getId()).orElseThrow().getStock())
                .isEqualTo(1);
    }

    /**
     * Reclama por HTTP con los formatos de motivo permitidos y verifica que no altera fondos ni stock.
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("PATCH reclamar acepta motivo texto, body ausente y campo null sin tocar fondos o stock")
    void reclamar_MotivoTextoAusenteONull_TransicionaSinTocarFondosOStock() throws Exception {
        Transaccion conTexto = guardarTransaccion(EstadoTransaccion.ENTREGADO, ZonedDateTime.now());
        Transaccion sinBody = guardarTransaccion(EstadoTransaccion.ENTREGADO, ZonedDateTime.now());
        Transaccion campoNull = guardarTransaccion(EstadoTransaccion.ENTREGADO, ZonedDateTime.now());
        long saldoInicial = usuarioRepository.findById(vendedor.getId()).orElseThrow().getSaldoDisponible();

        mockMvc.perform(patch("/transacciones/{id}/reclamar", conTexto.getId())
                        .cookie(obtenerCookieJwtPostLogin(comprador))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivo\":\"Producto defectuoso\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/transacciones/{id}/reclamar", sinBody.getId())
                        .cookie(obtenerCookieJwtPostLogin(comprador)))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/transacciones/{id}/reclamar", campoNull.getId())
                        .cookie(obtenerCookieJwtPostLogin(comprador))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivo\":null}"))
                .andExpect(status().isOk());

        assertEvento(conTexto.getId(), comprador, EstadoTransaccion.ENTREGADO, EstadoTransaccion.DISPUTA,
                "Producto defectuoso");
        assertEvento(sinBody.getId(), comprador, EstadoTransaccion.ENTREGADO, EstadoTransaccion.DISPUTA, null);
        assertEvento(campoNull.getId(), comprador, EstadoTransaccion.ENTREGADO, EstadoTransaccion.DISPUTA, null);
        assertThat(usuarioRepository.findById(vendedor.getId()).orElseThrow().getSaldoDisponible()).isEqualTo(saldoInicial);
        assertThat(movimientoSaldoRepository.findByVendedorId(vendedor.getId())).isEmpty();
        assertThat(publicacionRepository.findById(conTexto.getPublicacion().getId()).orElseThrow().getStock()).isEqualTo(1);
    }

    /**
     * Comprueba que vendedor, usuario auxiliar no comprador y tercero no pueden actuar como
     * comprador en ambos endpoints.
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("PATCH confirmar y reclamar por vendedor usuario auxiliar o tercero retornan 403")
    void endpoints_ActorNoComprador_Retornan403() throws Exception {
        Cookie cookieVendedor = obtenerCookieJwtPostLogin(vendedor);
        Cookie cookieAuxiliar = obtenerCookieJwtPostLogin(auxiliar);
        Cookie cookieTercero = obtenerCookieJwtPostLogin(tercero);

        mockMvc.perform(patch("/transacciones/{id}/confirmar", guardarTransaccion(EstadoTransaccion.ENTREGADO,
                        ZonedDateTime.now()).getId()).cookie(cookieVendedor)).andExpect(status().isForbidden());
        mockMvc.perform(patch("/transacciones/{id}/confirmar", guardarTransaccion(EstadoTransaccion.ENTREGADO,
                        ZonedDateTime.now()).getId()).cookie(cookieAuxiliar)).andExpect(status().isForbidden());
        mockMvc.perform(patch("/transacciones/{id}/confirmar", guardarTransaccion(EstadoTransaccion.ENTREGADO,
                        ZonedDateTime.now()).getId()).cookie(cookieTercero)).andExpect(status().isForbidden());
        mockMvc.perform(patch("/transacciones/{id}/reclamar", guardarTransaccion(EstadoTransaccion.ENTREGADO,
                        ZonedDateTime.now()).getId()).cookie(cookieVendedor)).andExpect(status().isForbidden());
        mockMvc.perform(patch("/transacciones/{id}/reclamar", guardarTransaccion(EstadoTransaccion.ENTREGADO,
                        ZonedDateTime.now()).getId()).cookie(cookieAuxiliar)).andExpect(status().isForbidden());
        mockMvc.perform(patch("/transacciones/{id}/reclamar", guardarTransaccion(EstadoTransaccion.ENTREGADO,
                        ZonedDateTime.now()).getId()).cookie(cookieTercero)).andExpect(status().isForbidden());
    }

    /**
     * Comprueba los conflictos de estado y de ventana de 48 horas expuestos por los servicios.
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("PATCH confirmar y reclamar fuera de entregado o de plazo retornan 409")
    void endpoints_EstadoNoEntregadoOPlazoExcedido_Retornan409() throws Exception {
        Cookie cookieComprador = obtenerCookieJwtPostLogin(comprador);

        mockMvc.perform(patch("/transacciones/{id}/confirmar", guardarTransaccion(EstadoTransaccion.ENVIADO,
                        null).getId()).cookie(cookieComprador)).andExpect(status().isConflict());
        mockMvc.perform(patch("/transacciones/{id}/reclamar", guardarTransaccion(EstadoTransaccion.ENVIADO,
                        null).getId()).cookie(cookieComprador)).andExpect(status().isConflict());
        mockMvc.perform(patch("/transacciones/{id}/confirmar", guardarTransaccion(EstadoTransaccion.ENTREGADO,
                        ZonedDateTime.now().minusHours(49)).getId()).cookie(cookieComprador))
                .andExpect(status().isConflict());
        mockMvc.perform(patch("/transacciones/{id}/reclamar", guardarTransaccion(EstadoTransaccion.ENTREGADO,
                        ZonedDateTime.now().minusHours(49)).getId()).cookie(cookieComprador))
                .andExpect(status().isConflict());
    }

    /**
     * Persiste un usuario de prueba con el rol solicitado.
     *
     * @param email correo único del usuario
     * @param rol rol persistido para la identidad
     * @return usuario persistido
     */
    private Usuario guardarUsuario(String email, Rol rol) {
        return usuarioRepository.save(new Usuario(email, passwordEncoder.encode(PASSWORD_RAW), rol, 0L,
                ZonedDateTime.now()));
    }

    /**
     * Crea una transacción de prueba del vendedor para el comprador y le asigna estado y entrega.
     *
     * @param estado estado inicial de la transacción
     * @param fechaEntregado inicio opcional de la ventana de 48 horas
     * @return transacción persistida
     */
    private Transaccion guardarTransaccion(EstadoTransaccion estado, ZonedDateTime fechaEntregado) {
        Publicacion publicacion = publicacionRepository.save(new Publicacion(vendedor, categoria, subcategoria,
                PRECIO_CENTAVOS, 1, "Artículo de prueba"));
        Transaccion transaccion = new Transaccion(comprador, publicacion, PRECIO_CENTAVOS, ZonedDateTime.now());
        transaccion.setEstado(estado);
        transaccion.setFechaEntregado(fechaEntregado);
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
     * Comprueba el evento append-only generado para una transacción por su endpoint de comprador.
     *
     * @param transaccionId ID de la transacción auditada
     * @param actor comprador autenticado que realizó la operación
     * @param origen estado de origen esperado
     * @param destino estado de destino esperado
     * @param motivo motivo esperado, incluido {@code null}
     */
    private void assertEvento(Long transaccionId, Usuario actor, EstadoTransaccion origen, EstadoTransaccion destino,
                              String motivo) {
        assertThat(transaccionEventoRepository.findAll())
                .filteredOn(evento -> evento.getTransaccion().getId().equals(transaccionId))
                .singleElement()
                .satisfies(evento -> {
                    assertThat(evento.getActor().getId()).isEqualTo(actor.getId());
                    assertThat(evento.getEstadoOrigen()).isEqualTo(origen);
                    assertThat(evento.getEstadoDestino()).isEqualTo(destino);
                    assertThat(evento.getMotivo()).isEqualTo(motivo);
                });
    }
}
