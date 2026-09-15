package com.easymarket.marketplace.controller;

import com.easymarket.marketplace.dto.LoginRequestDto;
import com.easymarket.marketplace.model.Categoria;
import com.easymarket.marketplace.model.EstadoTransaccion;
import com.easymarket.marketplace.model.IdempotencyKey;
import com.easymarket.marketplace.model.Publicacion;
import com.easymarket.marketplace.model.Rol;
import com.easymarket.marketplace.model.Subcategoria;
import com.easymarket.marketplace.model.Transaccion;
import com.easymarket.marketplace.model.Usuario;
import com.easymarket.marketplace.repository.CategoriaRepository;
import com.easymarket.marketplace.repository.IdempotencyKeyRepository;
import com.easymarket.marketplace.repository.MovimientoSaldoRepository;
import com.easymarket.marketplace.repository.PublicacionRepository;
import com.easymarket.marketplace.repository.StripeRefundOutboxRepository;
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
import org.springframework.beans.factory.annotation.Value;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración del endpoint de resolución administrativa de disputas
 * (PHA04TSK15, Story 9).
 *
 * <p>Usa PostgreSQL real mediante Testcontainers e identidades emitidas por el login JWT real;
 * la cookie {@code jwt} obtenida por HTTP autentica cada petición. Verifica que el endpoint exige
 * rol ADMIN de forma estricta (matcher de cadena de seguridad y {@code @PreAuthorize}), obtiene el
 * ID del admin exclusivamente del principal autenticado, delega la decisión binaria y el motivo
 * obligatorio en {@code ResolucionDisputaService} y traduce correctamente sus excepciones de
 * dominio. Cada caso verifica los efectos reales en base de datos (estado, saldo, movimiento,
 * stock, evento append-only y orden durable de reembolso), no solo el código HTTP.</p>
 *
 * <p><strong>Política única de fixtures ADMIN (PHA12TSK06).</strong> Esta clase NO crea ninguna
 * fila ADMIN: el actor autorizado de los gates {@code hasRole("ADMIN")} es exclusivamente el
 * administrador único provisionado por el contexto de test (seed V6 con
 * {@code ADMIN_EMAIL}/{@code ADMIN_PASSWORD_HASH}, cuyo hash corresponde a la contraseña plana
 * compartida {@code PASSWORD_RAW}), resuelto mediante {@link #obtenerAdminUnico()}. Los usuarios
 * auxiliares (vendedor/comprador) nacen todos {@code Rol.USUARIO}. Motivo:
 * {@code UsuarioRepository.findByRol(Rol.ADMIN)} es Optional por diseño (invariante de admin
 * único, PHA06TSK02) y cualquier fixture con un ADMIN adicional rompe esa invariante.</p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.jwt.secret=clave-secreta-pruebas-resolver-disputas-minimo-32-caracteres",
    "ADMIN_EMAIL=admin.seed@easymarket.com",
    "ADMIN_PASSWORD_HASH=$2a$10$ezbtTwVogv0lR8nXJuHRk.RGzMVbhLBUjDAt4zzBdJhbqR.U53k6u"
})
class DisputaResolverControllerIntegrationTests {

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
    @Autowired private IdempotencyKeyRepository idempotencyKeyRepository;
    @Autowired private StripeRefundOutboxRepository stripeRefundOutboxRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    /** Email del ADMIN único provisionado por el contexto de test (propiedad {@code ADMIN_EMAIL}, seed V6). */
    @Value("${ADMIN_EMAIL}")
    private String adminEmail;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;
    private Usuario vendedor;
    private Usuario comprador;

    /** ADMIN único provisionado por el seed V6; se resuelve en cada escenario (política PHA12TSK06). */
    private Usuario admin;
    private Categoria categoria;
    private Subcategoria subcategoria;

    /**
     * Configura identidades y catálogo aislados para cada escenario HTTP. Ningún fixture crea
     * filas ADMIN (política PHA12TSK06): el actor administrador es el único provisionado por el
     * contexto de test, resuelto del seed V6.
     */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        String sufijo = Long.toUnsignedString(System.nanoTime());
        vendedor = guardarUsuario("vendedor.disputa." + sufijo + "@easymarket.com", Rol.USUARIO);
        comprador = guardarUsuario("comprador.disputa." + sufijo + "@easymarket.com", Rol.USUARIO);
        admin = obtenerAdminUnico();
        categoria = categoriaRepository.save(new Categoria("Categoría " + sufijo));
        subcategoria = subcategoriaRepository.save(new Subcategoria(categoria, "Subcategoría " + sufijo));
    }

    /**
     * Resuelve la identidad persistida del ADMIN único provisionado por el contexto de test
     * (seed V6, email leído de la propiedad {@code ADMIN_EMAIL}).
     *
     * @return la entidad persistida del único administrador
     * @throws IllegalStateException si el admin sembrado no está presente (provisión de datos inconsistente)
     */
    private Usuario obtenerAdminUnico() {
        return usuarioRepository.findByEmail(adminEmail)
                .orElseThrow(() -> new IllegalStateException(
                        "El ADMIN único provisionado por el contexto (" + adminEmail + ") no está presente"));
    }

    /**
     * Verifica el criterio del task row "403 si no es admin": un usuario autenticado con rol
     * {@code USUARIO} (el comprador de la disputa) no puede resolverla; el endpoint responde 403
     * sin producir ningún efecto persistente.
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("PATCH resolver por usuario no-admin retorna 403 sin efectos")
    void resolver_UsuarioNoAdmin_Retorna403SinEfectos() throws Exception {
        Transaccion transaccion = guardarTransaccionDisputa();
        vincularPaymentIntent(transaccion);

        mockMvc.perform(patch("/disputas/{id}/resolver", transaccion.getId())
                        .cookie(obtenerCookieJwtPostLogin(comprador))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"A_FAVOR_VENDEDOR\",\"motivo\":\"Prueba 403\"}"))
                .andExpect(status().isForbidden());

        assertSinEfectos(transaccion);
    }

    /**
     * Verifica el criterio del task row "409 fuera de disputa": una transacción en estado distinto
     * ({@code RESERVADA} y {@code ENTREGADO}) resuelta por un admin retorna 409 sin efectos, tal
     * como lo rechaza el servicio de dominio antes de escribir.
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("PATCH resolver fuera de disputa retorna 409 sin efectos")
    void resolver_FueraDeDisputa_Retorna409SinEfectos() throws Exception {
        Transaccion reservada = guardarTransaccionDisputa(EstadoTransaccion.RESERVADA);
        vincularPaymentIntent(reservada);
        Transaccion entregada = guardarTransaccionDisputa(EstadoTransaccion.ENTREGADO);
        vincularPaymentIntent(entregada);
        Cookie cookieAdmin = obtenerCookieJwtPostLogin(admin);

        mockMvc.perform(patch("/disputas/{id}/resolver", reservada.getId())
                        .cookie(cookieAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"A_FAVOR_VENDEDOR\",\"motivo\":\"Prueba 409\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(patch("/disputas/{id}/resolver", entregada.getId())
                        .cookie(cookieAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"A_FAVOR_COMPRADOR\",\"motivo\":\"Prueba 409\"}"))
                .andExpect(status().isConflict());

        assertSinEfectos(reservada);
        assertSinEfectos(entregada);
    }

    /**
     * Verifica el criterio de la Story 9 "a favor del vendedor": el admin resuelve la disputa,
     * la transacción pasa a {@code COMPLETADA}, el saldo del vendedor se incrementa en el
     * {@code precio_snapshot} entero, se registra el movimiento append-only y el evento de
     * auditoría con el admin responsable y el motivo; el stock no se restaura y no hay orden
     * durable de reembolso.
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("PATCH resolver a favor del vendedor retorna 200, acredita saldo y audita")
    void resolver_AFavorVendedor_Retorna200AcreditaSaldoYAudita() throws Exception {
        Transaccion transaccion = guardarTransaccionDisputa();
        vincularPaymentIntent(transaccion);

        mockMvc.perform(patch("/disputas/{id}/resolver", transaccion.getId())
                        .cookie(obtenerCookieJwtPostLogin(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"A_FAVOR_VENDEDOR\",\"motivo\":\"La entrega quedó acreditada\"}"))
                .andExpect(status().isOk());

        assertThat(transaccionRepository.findById(transaccion.getId()).orElseThrow().getEstado())
                .isEqualTo(EstadoTransaccion.COMPLETADA);
        assertThat(usuarioRepository.findById(vendedor.getId()).orElseThrow().getSaldoDisponible())
                .isEqualTo(PRECIO_CENTAVOS);
        assertThat(movimientoSaldoRepository.findByVendedorId(vendedor.getId()))
                .singleElement()
                .satisfies(movimiento -> assertThat(movimiento.getMonto()).isEqualTo(PRECIO_CENTAVOS));
        assertEvento(transaccion.getId(), admin, EstadoTransaccion.DISPUTA, EstadoTransaccion.COMPLETADA,
                "A_FAVOR_VENDEDOR: La entrega quedó acreditada");
        assertThat(publicacionRepository.findById(transaccion.getPublicacion().getId()).orElseThrow().getStock())
                .isZero();
        assertThat(stripeRefundOutboxRepository.findAll())
                .filteredOn(orden -> orden.getTransaccion().getId().equals(transaccion.getId()))
                .isEmpty();
    }

    /**
     * Verifica el criterio de la Story 9 "a favor del comprador": el admin resuelve la disputa,
     * la transacción pasa a {@code CANCELADA}, el stock se restaura de 0 a 1, se registra el
     * evento de auditoría con el admin responsable y el motivo, y se persiste la orden durable
     * de reembolso {@code PENDIENTE} con su PaymentIntent correlacionado y su clave de
     * idempotencia {@code refund:<id>}; no se acredita saldo al vendedor.
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("PATCH resolver a favor del comprador retorna 200, restaura stock y crea outbox")
    void resolver_AFavorComprador_Retorna200RestauraStockYCreaOutbox() throws Exception {
        Transaccion transaccion = guardarTransaccionDisputa();
        String paymentIntentId = vincularPaymentIntent(transaccion);

        mockMvc.perform(patch("/disputas/{id}/resolver", transaccion.getId())
                        .cookie(obtenerCookieJwtPostLogin(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"A_FAVOR_COMPRADOR\",\"motivo\":\"El producto no coincide con la publicación\"}"))
                .andExpect(status().isOk());

        assertThat(transaccionRepository.findById(transaccion.getId()).orElseThrow().getEstado())
                .isEqualTo(EstadoTransaccion.CANCELADA);
        assertThat(publicacionRepository.findById(transaccion.getPublicacion().getId()).orElseThrow().getStock())
                .isEqualTo(1);
        assertEvento(transaccion.getId(), admin, EstadoTransaccion.DISPUTA, EstadoTransaccion.CANCELADA,
                "A_FAVOR_COMPRADOR: El producto no coincide con la publicación");
        assertThat(stripeRefundOutboxRepository.findAll())
                .filteredOn(orden -> orden.getTransaccion().getId().equals(transaccion.getId()))
                .singleElement()
                .satisfies(orden -> {
                    assertThat(orden.getEstado()).isEqualTo("PENDIENTE");
                    assertThat(orden.getIntentos()).isZero();
                    assertThat(orden.getPaymentIntentId()).isEqualTo(paymentIntentId);
                    assertThat(orden.getIdempotencyKey()).isEqualTo("refund:" + transaccion.getId());
                });
        assertThat(usuarioRepository.findById(vendedor.getId()).orElseThrow().getSaldoDisponible()).isZero();
        assertThat(movimientoSaldoRepository.findByVendedorId(vendedor.getId())).isEmpty();
    }

    /**
     * Verifica el criterio "motivo obligatorio" de la Story 9: cuerpo sin motivo, {@code motivo:
     * null}, {@code motivo: ""} y {@code motivo: "   "} retornan 400 sin ningún efecto
     * persistente, delegando la validación de la obligatoriedad al servicio de dominio.
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("PATCH resolver con motivo ausente, null o blanco retorna 400 sin efectos")
    void resolver_MotivoAusenteONullOBlanco_Retorna400SinEfectos() throws Exception {
        Transaccion sinMotivo = guardarTransaccionDisputa();
        vincularPaymentIntent(sinMotivo);
        Transaccion campoNull = guardarTransaccionDisputa();
        vincularPaymentIntent(campoNull);
        Transaccion vacio = guardarTransaccionDisputa();
        vincularPaymentIntent(vacio);
        Transaccion soloBlancos = guardarTransaccionDisputa();
        vincularPaymentIntent(soloBlancos);
        Cookie cookieAdmin = obtenerCookieJwtPostLogin(admin);

        mockMvc.perform(patch("/disputas/{id}/resolver", sinMotivo.getId()).cookie(cookieAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"A_FAVOR_VENDEDOR\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/disputas/{id}/resolver", campoNull.getId()).cookie(cookieAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"A_FAVOR_VENDEDOR\",\"motivo\":null}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/disputas/{id}/resolver", vacio.getId()).cookie(cookieAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"A_FAVOR_VENDEDOR\",\"motivo\":\"\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/disputas/{id}/resolver", soloBlancos.getId()).cookie(cookieAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"A_FAVOR_VENDEDOR\",\"motivo\":\"   \"}"))
                .andExpect(status().isBadRequest());

        for (Transaccion transaccion : List.of(sinMotivo, campoNull, vacio, soloBlancos)) {
            assertSinEfectos(transaccion);
        }
    }

    /**
     * Verifica que una decisión ausente, {@code null} o una cadena no perteneciente al enum
     * {@code ResolucionDisputa} retorna 400 sin efectos; el caso de cadena inválida se traduce
     * en el controlador y el caso ausente se delega al dominio, ambos con el mismo código 400.
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("PATCH resolver con decision ausente, null o inválida retorna 400 sin efectos")
    void resolver_DecisionAusenteONullOInvalida_Retorna400SinEfectos() throws Exception {
        Transaccion sinDecision = guardarTransaccionDisputa();
        vincularPaymentIntent(sinDecision);
        Transaccion campoNull = guardarTransaccionDisputa();
        vincularPaymentIntent(campoNull);
        Transaccion invalida = guardarTransaccionDisputa();
        vincularPaymentIntent(invalida);
        Cookie cookieAdmin = obtenerCookieJwtPostLogin(admin);

        mockMvc.perform(patch("/disputas/{id}/resolver", sinDecision.getId()).cookie(cookieAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivo\":\"Motivo válido\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/disputas/{id}/resolver", campoNull.getId()).cookie(cookieAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":null,\"motivo\":\"Motivo válido\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/disputas/{id}/resolver", invalida.getId()).cookie(cookieAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"DECISION_NO_EXISTENTE\",\"motivo\":\"Motivo válido\"}"))
                .andExpect(status().isBadRequest());

        for (Transaccion transaccion : List.of(sinDecision, campoNull, invalida)) {
            assertSinEfectos(transaccion);
        }
    }

    /**
     * Verifica que resolver una transacción inexistente retorna 404 con el mensaje de dominio
     * (delegación del mapeo existente de {@code TransaccionNoEncontradaException}).
     *
     * <p>El body con el mensaje se verifica para distinguir el 404 de dominio (endpoint presente,
     * {@code GlobalExceptionHandler} actuando) del 404 de ruta inexistente del framework, que es
     * el fallo de la fase Red cuando el endpoint aún no está implementado.</p>
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("PATCH resolver transacción inexistente retorna 404 con mensaje de dominio")
    void resolver_TransaccionInexistente_Retorna404() throws Exception {
        mockMvc.perform(patch("/disputas/{id}/resolver", 999_999L)
                        .cookie(obtenerCookieJwtPostLogin(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"A_FAVOR_VENDEDOR\",\"motivo\":\"Prueba 404\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.mensaje").value("Transacción con ID 999999 no encontrada"));
    }

    /**
     * Verifica la decisión del plan.md (2026-08-07) aplicada a la rama compradora: una disputa
     * sin fila en {@code idempotency_keys} con {@code payment_intent_id} rechaza la resolución
     * a favor del comprador con el código mapeado de {@code PaymentIntentTransaccionNoEncontradoException}
     * antes de modificar estado, stock, auditoría u outbox.
     *
     * @throws Exception si falla la interacción HTTP
     */
    @Test
    @DisplayName("PATCH resolver a favor del comprador sin PaymentIntent correlacionado retorna 409 sin efectos")
    void resolver_AFavorCompradorSinPaymentIntent_Retorna409SinEfectos() throws Exception {
        Transaccion transaccion = guardarTransaccionDisputa();

        mockMvc.perform(patch("/disputas/{id}/resolver", transaccion.getId())
                        .cookie(obtenerCookieJwtPostLogin(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"A_FAVOR_COMPRADOR\",\"motivo\":\"Sin correlación\"}"))
                .andExpect(status().isConflict());

        assertSinEfectos(transaccion);
    }

    /**
     * Persiste un usuario auxiliar de prueba con el rol solicitado (política PHA12TSK06: en esta
     * clase solo se invoca con {@link Rol#USUARIO}; el ADMIN único nunca nace aquí).
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
     * Crea una transacción en disputa para el comprador sobre una publicación con stock 0,
     * reproduciendo la condición física de una unidad ya consumida por el webhook
     * {@code payment_intent.succeeded} al reservar y que luego entró en conflicto.
     *
     * <p>La transacción se persiste directamente en {@code DISPUTA}, como hacen los fixtures de
     * los tests de la misma familia (TSK13/TSK14) al forzar el estado requerido por el caso sin
     * reproducir el historial completo de eventos; el evento canónico que el test cuenta es el de
     * la resolución, único para la transacción hasta ese momento.</p>
     *
     * @return transacción persistida en estado {@code DISPUTA}
     */
    private Transaccion guardarTransaccionDisputa() {
        return guardarTransaccionDisputa(EstadoTransaccion.DISPUTA);
    }

    /**
     * Crea una transacción de la publicación del vendedor para el comprador y fuerza el estado
     * requerido por el caso, con la publicación en stock 0 (unidad ya consumida por la reserva).
     *
     * @param estado estado inicial de la transacción
     * @return transacción persistida con la publicación en stock 0
     */
    private Transaccion guardarTransaccionDisputa(EstadoTransaccion estado) {
        Publicacion publicacion = publicacionRepository.save(new Publicacion(vendedor, categoria, subcategoria,
                PRECIO_CENTAVOS, 0, "Artículo de prueba"));
        Transaccion transaccion = new Transaccion(comprador, publicacion, PRECIO_CENTAVOS, ZonedDateTime.now());
        transaccion.setEstado(estado);
        if (estado == EstadoTransaccion.ENTREGADO) {
            transaccion.setFechaEntregado(ZonedDateTime.now());
        }
        return transaccionRepository.saveAndFlush(transaccion);
    }

    /**
     * Crea y vincula la correlación Stripe exigida por el plan.md para la rama compradora: una fila
     * en {@code idempotency_keys} con {@code transaccion_id} poblado y {@code payment_intent_id}
     * no blanco (relación poblada en producción por el webhook {@code payment_intent.succeeded}).
     *
     * @param transaccion transacción cuya correlación se vincula
     * @return {@code payment_intent_id} persistido para verificar la orden durable de reembolso
     */
    private String vincularPaymentIntent(Transaccion transaccion) {
        String paymentIntentId = "pi_" + UUID.randomUUID();
        IdempotencyKey key = new IdempotencyKey(UUID.randomUUID().toString(), paymentIntentId, ZonedDateTime.now());
        key.setTransaccionId(transaccion.getId());
        idempotencyKeyRepository.save(key);
        return paymentIntentId;
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
     * Comprueba el evento append-only generado para una resolución exitosa de disputa.
     *
     * @param transaccionId ID de la transacción auditada
     * @param actor usuario administrador autenticado que realizó la resolución
     * @param origen estado de origen esperado
     * @param destino estado de destino esperado
     * @param motivo texto auditado esperado (decisión explícita seguida del motivo literal)
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

    /**
     * Verifica que un rechazo de resolución no dejó ningún efecto persistente: estado intacto,
     * saldo y movimiento sin cambios, stock sin restaurar (0), sin evento append-only y sin orden
     * durable de reembolso.
     *
     * @param transaccion transacción cuyo estado previo se conserva íntegro
     */
    private void assertSinEfectos(Transaccion transaccion) {
        Transaccion intacta = transaccionRepository.findById(transaccion.getId()).orElseThrow();
        assertThat(intacta.getEstado()).isEqualTo(transaccion.getEstado());
        assertThat(usuarioRepository.findById(vendedor.getId()).orElseThrow().getSaldoDisponible()).isZero();
        assertThat(movimientoSaldoRepository.findByVendedorId(vendedor.getId())).isEmpty();
        assertThat(publicacionRepository.findById(transaccion.getPublicacion().getId()).orElseThrow().getStock())
                .isZero();
        assertThat(transaccionEventoRepository.findAll())
                .filteredOn(evento -> evento.getTransaccion().getId().equals(transaccion.getId()))
                .isEmpty();
        assertThat(stripeRefundOutboxRepository.findAll())
                .filteredOn(orden -> orden.getTransaccion().getId().equals(transaccion.getId()))
                .isEmpty();
    }
}