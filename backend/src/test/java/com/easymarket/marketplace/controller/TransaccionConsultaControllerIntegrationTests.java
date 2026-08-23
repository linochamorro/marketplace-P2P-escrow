package com.easymarket.marketplace.controller;

import com.easymarket.marketplace.dto.LoginRequestDto;
import com.easymarket.marketplace.model.Categoria;
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
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración de las consultas de transacciones (PHA06TSK06, Stories 5, 6a-6d y 7).
 *
 * <p>Usa PostgreSQL real mediante Testcontainers e identidades emitidas por el login JWT real.
 * Verifica el criterio de la fila de tarea: {@code GET /transacciones/compras} queda aislada al
 * comprador autenticado, {@code GET /transacciones/ventas} al vendedor autenticado (dueño de la
 * publicación), y {@code GET /transacciones/{id}} admite comprador, vendedor y {@code ADMIN}
 * mientras que un tercero autenticado o un ID inexistente reciben HTTP 404 reutilizando
 * {@code TransaccionNoEncontradaException} (ocultamiento de existencia). Cada lista y cada campo
 * del detalle se cotejan contra PostgreSQL real mediante los métodos del repositorio y las
 * entidades persistidas; el Red phase de esta clase depende de que esos métodos de consulta
 * ({@code findByCompradorIdOrderByFechaReservadaDesc} y
 * {@code findByPublicacionUsuarioIdOrderByFechaReservadaDesc}) aún no existan en
 * {@code TransaccionRepository} y por eso no compila hasta que la implementación se agrega.</p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=clave-secreta-pruebas-consulta-transacciones-min-32-caracteres",
    "ADMIN_EMAIL=admin.seed@easymarket.com",
    "ADMIN_PASSWORD_HASH=$2a$10$R9h/cIPz0gi.URNNXRkh2OPST9/PgBkqquzi.Ss7KIUgO2t0jWMUW"
})
class TransaccionConsultaControllerIntegrationTests {

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
    @Autowired private PasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;
    private Usuario vendedor;
    private Usuario vendedor2;
    private Usuario comprador;
    private Usuario comprador2;
    private Usuario tercero;
    private Usuario admin;
    private Categoria categoria;
    private Subcategoria subcategoria;

    /** Configura identidades y catálogo aislados para cada escenario HTTP. */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        String sufijo = Long.toUnsignedString(System.nanoTime());
        vendedor = guardarUsuario("vendedor.consulta." + sufijo + "@easymarket.com", Rol.USUARIO);
        vendedor2 = guardarUsuario("vendedor2.consulta." + sufijo + "@easymarket.com", Rol.USUARIO);
        comprador = guardarUsuario("comprador.consulta." + sufijo + "@easymarket.com", Rol.USUARIO);
        comprador2 = guardarUsuario("comprador2.consulta." + sufijo + "@easymarket.com", Rol.USUARIO);
        tercero = guardarUsuario("tercero.consulta." + sufijo + "@easymarket.com", Rol.USUARIO);
        admin = guardarUsuario("admin.consulta." + sufijo + "@easymarket.com", Rol.ADMIN);
        categoria = categoriaRepository.save(new Categoria("Categoría consulta " + sufijo));
        subcategoria = subcategoriaRepository.save(new Subcategoria(categoria, "Subcategoría consulta " + sufijo));
    }

    /**
     * Verifica el criterio de aislamiento del listado de compras: el endpoint devuelve únicamente
     * las transacciones cuyo comprador es el actor autenticado, y el query del repositorio que lo
     * alimenta devuelve exactamente lo mismo contra PostgreSQL real (sin incluir las transacciones
     * de otro comprador persistidas en la misma base).
     *
     * @throws Exception si falla la interacción HTTP o el parseo de la respuesta
     */
    @Test
    @DisplayName("GET /transacciones/compras devuelve solo las del comprador autenticado")
    void compras_SoloDelCompradorAutenticado_NoIncluyeLasDeOtroComprador() throws Exception {
        Publicacion publicacionComprada = guardarPublicacion(vendedor, "Publicación comprada por el actor");
        Transaccion mia = guardarTransaccion(comprador, publicacionComprada, EstadoTransaccion.RESERVADA);
        Publicacion publicacionDeOtro = guardarPublicacion(vendedor2, "Publicación de otro comprador");
        Transaccion deOtroComprador = guardarTransaccion(comprador2, publicacionDeOtro, EstadoTransaccion.RESERVADA);
        Cookie cookieComprador = obtenerCookieJwtPostLogin(comprador);

        MvcResult resultado = mockMvc.perform(get("/transacciones/compras").cookie(cookieComprador))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andReturn();

        List<Long> idsHttp = idsDeLaRespuesta(resultado);
        assertThat(idsHttp).containsExactly(mia.getId());
        assertThat(idsHttp).doesNotContain(deOtroComprador.getId());

        List<Long> idsBd = transaccionRepository.findByCompradorIdOrderByFechaReservadaDesc(comprador.getId())
                .stream().map(Transaccion::getId).toList();
        assertThat(idsBd).containsExactly(mia.getId());
        assertThat(idsBd).doesNotContain(deOtroComprador.getId());
    }

    /**
     * Verifica el criterio de aislamiento del listado de ventas: el endpoint devuelve únicamente
     * las transacciones cuya publicación pertenece al actor autenticado, y el query del repositorio
     * que lo alimenta devuelve exactamente lo mismo contra PostgreSQL real (sin incluir las ventas
     * de otro vendedor persistidas en la misma base).
     *
     * @throws Exception si falla la interacción HTTP o el parseo de la respuesta
     */
    @Test
    @DisplayName("GET /transacciones/ventas devuelve solo las del vendedor autenticado")
    void ventas_SoloDelVendedorAutenticado_NoIncluyeLasDeOtroVendedor() throws Exception {
        Publicacion publicacionPropia = guardarPublicacion(vendedor, "Publicación del vendedor actor");
        Transaccion mia = guardarTransaccion(comprador, publicacionPropia, EstadoTransaccion.RESERVADA);
        Publicacion publicacionDeOtro = guardarPublicacion(vendedor2, "Publicación de otro vendedor");
        Transaccion deOtroVendedor = guardarTransaccion(comprador2, publicacionDeOtro, EstadoTransaccion.RESERVADA);
        Cookie cookieVendedor = obtenerCookieJwtPostLogin(vendedor);

        MvcResult resultado = mockMvc.perform(get("/transacciones/ventas").cookie(cookieVendedor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andReturn();

        List<Long> idsHttp = idsDeLaRespuesta(resultado);
        assertThat(idsHttp).containsExactly(mia.getId());
        assertThat(idsHttp).doesNotContain(deOtroVendedor.getId());

        List<Long> idsBd = transaccionRepository.findByPublicacionUsuarioIdOrderByFechaReservadaDesc(vendedor.getId())
                .stream().map(Transaccion::getId).toList();
        assertThat(idsBd).containsExactly(mia.getId());
        assertThat(idsBd).doesNotContain(deOtroVendedor.getId());
    }

    /**
     * Verifica que un mismo usuario que es comprador en unas transacciones y vendedor en otras
     * recibe en cada listado exactamente las de su rol correspondiente, sin mezcla entre ambas.
     *
     * @throws Exception si falla la interacción HTTP o el parseo de la respuesta
     */
    @Test
    @DisplayName("GET compras y ventas del mismo usuario no se mezclan entre sí")
    void comprasYVentas_DelMismoUsuario_NoSeMezclan() throws Exception {
        Publicacion publicacionAjena = guardarPublicacion(vendedor, "Publicación ajena comprada por el actor");
        Transaccion comoComprador = guardarTransaccion(comprador, publicacionAjena, EstadoTransaccion.RESERVADA);
        Publicacion publicacionPropia = guardarPublicacion(comprador, "Publicación propia vendida por el actor");
        Transaccion comoVendedor = guardarTransaccion(comprador2, publicacionPropia, EstadoTransaccion.ENVIADO);
        Cookie cookieActor = obtenerCookieJwtPostLogin(comprador);

        MvcResult compras = mockMvc.perform(get("/transacciones/compras").cookie(cookieActor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andReturn();
        assertThat(idsDeLaRespuesta(compras)).containsExactly(comoComprador.getId());

        MvcResult ventas = mockMvc.perform(get("/transacciones/ventas").cookie(cookieActor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andReturn();
        assertThat(idsDeLaRespuesta(ventas)).containsExactly(comoVendedor.getId());
    }

    /**
     * Verifica que un usuario sin transacciones como comprador ni como vendedor recibe HTTP 200
     * con listas vacías en ambos endpoints (sin error, sin discriminación de ausencia).
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("GET compras y ventas de un usuario sin transacciones retornan 200 con lista vacía")
    void comprasYVentas_UsuarioSinTransacciones_Retornan200ConListaVacia() throws Exception {
        Cookie cookieTercero = obtenerCookieJwtPostLogin(tercero);

        mockMvc.perform(get("/transacciones/compras").cookie(cookieTercero))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/transacciones/ventas").cookie(cookieTercero))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /**
     * Verifica que los endpoints de consulta quedan protegidos por
     * {@code anyRequest().authenticated()} de {@code SecurityConfig}: una petición sin cookie JWT
     * recibe HTTP 403 (mismo assert de "sin autenticación" de los tests de integración existentes).
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("GET /transacciones/compras sin cookie retorna 403 Forbidden")
    void compras_SinCookie_Retorna403Forbidden() throws Exception {
        mockMvc.perform(get("/transacciones/compras"))
                .andExpect(status().isForbidden());
    }

    /**
     * Verifica el detalle visto por el comprador: HTTP 200 con el DTO mapeado campo a campo contra
     * la entidad persistida en base de datos — {@code estado} como código String en minúscula,
     * {@code precioSnapshot} como entero largo en centavos, las tres fechas con el instante exacto
     * y la descripción literal de la publicación.
     *
     * @throws Exception si falla la interacción HTTP o el parseo de la respuesta
     */
    @Test
    @DisplayName("GET /transacciones/{id} por comprador retorna 200 con todos los campos contra BD")
    void detalle_PorComprador_Retorna200ConCamposVerificadosContraBd() throws Exception {
        ZonedDateTime reservada = ZonedDateTime.now(ZoneId.of("America/Lima")).minusDays(3);
        ZonedDateTime enviado = ZonedDateTime.now(ZoneId.of("America/Lima")).minusDays(2);
        ZonedDateTime entregado = ZonedDateTime.now(ZoneId.of("America/Lima")).minusHours(10);
        Publicacion publicacion = guardarPublicacion(vendedor, "Auriculares inalámbricos con descripción única");
        Transaccion transaccion = guardarTransaccion(comprador, publicacion, EstadoTransaccion.ENTREGADO,
                reservada, enviado, entregado);
        Transaccion guardada = transaccionRepository.findById(transaccion.getId()).orElseThrow();
        Cookie cookieComprador = obtenerCookieJwtPostLogin(comprador);

        MvcResult resultado = mockMvc.perform(get("/transacciones/{id}", transaccion.getId()).cookie(cookieComprador))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(guardada.getId()))
                .andExpect(jsonPath("$.estado").value("entregado"))
                .andExpect(jsonPath("$.precioSnapshot").value(guardada.getPrecioSnapshot()))
                .andReturn();

        JsonNode json = objectMapper.readTree(resultado.getResponse().getContentAsString());
        assertThat(instantDe(json.get("fechaReservada"))).isEqualTo(guardada.getFechaReservada().toInstant());
        assertThat(instantDe(json.get("fechaEnviado"))).isEqualTo(guardada.getFechaEnviado().toInstant());
        assertThat(instantDe(json.get("fechaEntregado"))).isEqualTo(guardada.getFechaEntregado().toInstant());
        assertThat(json.get("publicacionDescripcion").asText())
                .isEqualTo(publicacionRepository.findById(guardada.getPublicacion().getId())
                        .orElseThrow().getDescripcion());
        assertThat(guardada.getPrecioSnapshot()).isEqualTo(PRECIO_CENTAVOS);
    }

    /**
     * Verifica que el vendedor dueño de la publicación de la transacción puede consultar el
     * detalle (HTTP 200 con el mismo DTO).
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("GET /transacciones/{id} por vendedor retorna 200")
    void detalle_PorVendedor_Retorna200() throws Exception {
        Transaccion transaccion = guardarTransaccion(comprador,
                guardarPublicacion(vendedor, "Publicación vendida"), EstadoTransaccion.RESERVADA);
        Cookie cookieVendedor = obtenerCookieJwtPostLogin(vendedor);

        mockMvc.perform(get("/transacciones/{id}", transaccion.getId()).cookie(cookieVendedor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(transaccion.getId()))
                .andExpect(jsonPath("$.estado").value("reservada"));
    }

    /**
     * Verifica que el {@code ADMIN} puede consultar el detalle de cualquier transacción
     * (HTTP 200 con el mismo DTO), según el contrato de plan.md "Lectura de transacciones".
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("GET /transacciones/{id} por ADMIN retorna 200")
    void detalle_PorAdmin_Retorna200() throws Exception {
        Transaccion transaccion = guardarTransaccion(comprador,
                guardarPublicacion(vendedor, "Publicación visible para el admin"), EstadoTransaccion.DISPUTA);
        Cookie cookieAdmin = obtenerCookieJwtPostLogin(admin);

        mockMvc.perform(get("/transacciones/{id}", transaccion.getId()).cookie(cookieAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(transaccion.getId()))
                .andExpect(jsonPath("$.estado").value("disputa"));
    }

    /**
     * Verifica el ocultamiento de existencia: un tercero autenticado que no es comprador, vendedor
     * ni admin recibe HTTP 404 con el mensaje de dominio de {@code TransaccionNoEncontradaException}
     * (decisión de contrato de PHA06TSK06), y la fila sigue existiendo en la base de datos.
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("GET /transacciones/{id} por tercero autenticado retorna 404 ocultando la existencia")
    void detalle_TerceroAutenticado_Retorna404ConMensajeDeDominio() throws Exception {
        Transaccion transaccion = guardarTransaccion(comprador,
                guardarPublicacion(vendedor, "Transacción privada"), EstadoTransaccion.RESERVADA);
        Cookie cookieTercero = obtenerCookieJwtPostLogin(tercero);

        mockMvc.perform(get("/transacciones/{id}", transaccion.getId()).cookie(cookieTercero))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.mensaje").value("Transacción con ID " + transaccion.getId() + " no encontrada"));

        assertThat(transaccionRepository.findById(transaccion.getId())).isPresent();
    }

    /**
     * Verifica que un {@code id} inexistente devuelve HTTP 404 con el mismo mensaje de dominio que
     * el tercero no autorizado (mismo contrato de ocultamiento).
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("GET /transacciones/{id} inexistente retorna 404 Not Found con mensaje de dominio")
    void detalle_TransaccionInexistente_Retorna404() throws Exception {
        Cookie cookieComprador = obtenerCookieJwtPostLogin(comprador);

        mockMvc.perform(get("/transacciones/999999").cookie(cookieComprador))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.mensaje").value("Transacción con ID 999999 no encontrada"));
    }

    /**
     * Verifica la regresión de las rutas previas: los PATCH existentes de
     * {@code /transacciones/{id}/...} siguen funcionando tras agregar los nuevos GET sobre el mismo
     * espacio de rutas, y el nuevo detalle {@code GET /transacciones/{id}} convive con ellos
     * (Spring prioriza los literales {@code /compras} y {@code /ventas} sobre el template
     * {@code /{id}}, y los PATCH no colisionan con los GET por diferir en método HTTP).
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("Regresión: PATCH /transacciones/{id}/enviar sigue funcionando junto a los nuevos GET")
    void patch_Envio_SigueFuncionandoJuntoALosNuevosGets() throws Exception {
        Publicacion publicacion = guardarPublicacion(vendedor, "Artículo de regresión de rutas");
        Transaccion transaccion = guardarTransaccion(comprador, publicacion, EstadoTransaccion.RESERVADA);
        Cookie cookieVendedor = obtenerCookieJwtPostLogin(vendedor);

        mockMvc.perform(patch("/transacciones/{id}/enviar", transaccion.getId()).cookie(cookieVendedor))
                .andExpect(status().isOk());

        assertThat(transaccionRepository.findById(transaccion.getId()).orElseThrow().getEstado())
                .isEqualTo(EstadoTransaccion.ENVIADO);

        mockMvc.perform(get("/transacciones/{id}", transaccion.getId()).cookie(cookieVendedor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("enviado"));
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
     * Persiste una publicación del vendedor indicado con la descripción dada y stock 1.
     *
     * @param vendedor dueño persistido de la publicación
     * @param descripcion texto literal que se verificará contra el DTO
     * @return publicación persistida
     */
    private Publicacion guardarPublicacion(Usuario vendedor, String descripcion) {
        return publicacionRepository.save(new Publicacion(vendedor, categoria, subcategoria,
                PRECIO_CENTAVOS, 1, descripcion));
    }

    /**
     * Crea una transacción de la publicación del vendedor para el comprador indicado con las tres
     * fechas explícitas, permitiendo verificar el instante exacto de cada campo opcional del DTO.
     *
     * @param comprador comprador persistido de la transacción
     * @param publicacion publicación adquirida
     * @param estado estado inicial de la transacción
     * @param fechaReservada instante de creación de la reserva
     * @param fechaEnviado instante de envío, o {@code null} si el estado no lo pobló
     * @param fechaEntregado instante de entrega, o {@code null} si el estado no lo pobló
     * @return transacción persistida
     */
    private Transaccion guardarTransaccion(Usuario comprador, Publicacion publicacion, EstadoTransaccion estado,
                                           ZonedDateTime fechaReservada, ZonedDateTime fechaEnviado,
                                           ZonedDateTime fechaEntregado) {
        Transaccion transaccion = new Transaccion(comprador, publicacion, PRECIO_CENTAVOS, fechaReservada);
        transaccion.setEstado(estado);
        transaccion.setFechaEnviado(fechaEnviado);
        transaccion.setFechaEntregado(fechaEntregado);
        return transaccionRepository.saveAndFlush(transaccion);
    }

    /**
     * Crea una transacción con fechas por defecto (reserva ahora, envío y entrega nulos) para los
     * escenarios que no verifican instantes.
     *
     * @param comprador comprador persistido de la transacción
     * @param publicacion publicación adquirida
     * @param estado estado inicial de la transacción
     * @return transacción persistida
     */
    private Transaccion guardarTransaccion(Usuario comprador, Publicacion publicacion, EstadoTransaccion estado) {
        return guardarTransaccion(comprador, publicacion, estado, ZonedDateTime.now(), null, null);
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
     * Extrae los IDs numéricos de una respuesta JSON que es un arreglo de DTOs.
     *
     * @param resultado respuesta HTTP ya ejecutada
     * @return lista ordenada de IDs en el orden del arreglo
     * @throws Exception si el cuerpo de la respuesta no es JSON parseable
     */
    private List<Long> idsDeLaRespuesta(MvcResult resultado) throws Exception {
        return objectMapper.readTree(resultado.getResponse().getContentAsString())
                .findValuesAsText("id").stream().map(Long::valueOf).toList();
    }

    /**
     * Convierte la representación ISO-8601 de una fecha serializada por Jackson 3 a instante.
     *
     * @param iso nodo JSON textual del campo de fecha
     * @return instante UTC equivalente al valor persistido
     */
    private java.time.Instant instantDe(JsonNode iso) {
        return ZonedDateTime.parse(iso.asText()).toInstant();
    }
}
