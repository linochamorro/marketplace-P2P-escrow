package com.easymarket.marketplace.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Filtro HTTP de validación de {@code Origin} contra CSRF por peticiones simples forzables
 * (PHA16TSK05; plan.md, sección "PHA16 — Estabilización de producción y seguridad", fila "CSRF";
 * informe de auditoría 2026-09-13, hallazgo A3; constitution principio 7).
 *
 * <p><strong>Amenaza que cierra.</strong> Las cookies {@code jwt} httpOnly con
 * {@code SameSite=None} se adjuntan a peticiones cross-site, por lo que un sitio malicioso puede
 * forzar en el navegador de la víctima peticiones simples (sin preflight CORS) hacia endpoints que
 * mutan estado o sesión y son alcanzables sin tokens de cabecera: {@code POST /auth/logout}
 * (cierra la sesión de la víctima) y {@code POST /admin/usuarios/{id}/desbloquear} (si la víctima
 * es admin, desbloquea cuentas). Este filtro exige que toda petición de método no seguro porte un
 * origen verificable dentro de la allowlist.</p>
 *
 * <p><strong>Algoritmo exacto (reconciliación plan.md ↔ fila de tarea, ver decisión 1 del
 * Artifact PHA16TSK05-L01-programmer.md).</strong> Solo para métodos no seguros
 * ({@code POST}/{@code PUT}/{@code PATCH}/{@code DELETE}):
 * <ol>
 *   <li>Ruta {@code /webhooks/stripe} (coincidencia exacta) → deja pasar siempre (exención
 *       explícita: Stripe se autentica por firma HMAC, no por origen).</li>
 *   <li>Candidato = header {@code Origin} si presente (no blanco); si no, origen derivado del
 *       header {@code Referer} si presente (fallback de plan.md para navegadores antiguos o
 *       proxies que retiran {@code Origin}); si ambos ausentes → deja pasar (clientes
 *       no-browser, tests, Stripe).</li>
 *   <li>Candidato ajeno a la allowlist → 403 JSON sin invocar la cadena; candidato listado → deja
 *       pasar. Un {@code Referer} inparseable se trata como ajeno (fail-closed).</li>
 * </ol>
 * Los métodos seguros ({@code GET}/{@code HEAD}/{@code OPTIONS}, incluido el preflight CORS) nunca
 * se evalúan.</p>
 *
 * <p><strong>Allowlist.</strong> La MISMA propiedad {@code app.cors.allowed-origins} de
 * {@code SecurityConfig} con el MISMO parseo (separación por coma, {@code trim}, descarte de
 * vacíos) y comparación exacta de cadena — {@code Origin} nunca trae barra final y las entradas
 * tampoco deben traerla.</p>
 *
 * <p><strong>Posición en la cadena (ver decisión 2).</strong> Registrado con
 * {@code addFilterBefore(..., CorsFilter.class)}: corre ANTES que el {@code CorsFilter} (y por
 * tanto antes que {@code JwtAuthenticationFilter}). Sin esto el filtro sería inobservable para
 * {@code Origin} ajeno, porque el {@code CorsFilter} preexistente ya cortocircuita esos casos con
 * su 403 de texto plano; aquí el veredicto explícito con contrato JSON
 * ({@code {"mensaje":"Origen no permitido"}}, el formato de {@code GlobalExceptionHandler}) lo
 * pre-empta. No autentica ni autoriza: solo rechaza o deja pasar (principio 7 intacto).</p>
 *
 * <p><strong>Dispatches ERROR (ver decisión 2).</strong> Se conserva deliberadamente el
 * {@code shouldNotFilterErrorDispatch() = true} por defecto (override explícito abajo): este
 * filtro se OMITE en el dispatch ERROR hacia {@code /error} y no interfiere con la corrección de
 * PHA12TSK02 (que depende de que {@code JwtAuthenticationFilter} sí corra ahí). Además el rechazo
 * usa {@code setStatus} + escritura directa (nunca {@code sendError}), por lo que el propio filtro
 * no dispara dispatches ERROR nuevos.</p>
 *
 * <p><strong>Cuerpo intacto (advertencia de plan.md sobre raw body).</strong> El filtro solo lee
 * headers y la línea de petición; jamás consume ni envuelve el cuerpo — la verificación HMAC del
 * webhook sobre el raw body queda intacta.</p>
 */
@Component
public class OriginValidationFilter extends OncePerRequestFilter {

    /** Ruta exenta del chequeo de origen (coincidencia exacta, no prefijo). */
    static final String RUTA_WEBHOOK_STRIPE_EXENTA = "/webhooks/stripe";

    /** Mensaje del cuerpo JSON 403 (contrato {@code {"mensaje": ...}} del proyecto). */
    static final String MENSAJE_ORIGEN_NO_PERMITIDO = "Origen no permitido";

    /** Conjunto de orígenes permitidos, derivado de {@code app.cors.allowed-origins}. */
    private final Set<String> origenesPermitidos;

    /**
     * Constructor con inyección de la allowlist de orígenes desde la misma propiedad que CORS.
     *
     * @param allowedOriginsRaw cadena con orígenes permitidos separados por coma (inyectada desde
     *                          {@code app.cors.allowed-origins}); usa el mismo valor por defecto
     *                          que {@code SecurityConfig} cuando la propiedad está ausente
     */
    public OriginValidationFilter(
            @Value("${app.cors.allowed-origins:http://localhost:3000}") String allowedOriginsRaw) {
        this.origenesPermitidos = parsearAllowlist(allowedOriginsRaw);
    }

    /**
     * Aplica la validación de origen a métodos no seguros con candidato presente y no exentos.
     *
     * @param request solicitud HTTP recibida
     * @param response respuesta HTTP en construcción
     * @param filterChain cadena de filtros para continuar cuando la petición pasa el chequeo
     * @throws ServletException si ocurre un error del contenedor servlet
     * @throws IOException si falla la escritura de la respuesta de rechazo
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!esMetodoNoSeguro(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        if (esRutaExenta(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }
        String candidato = resolverOrigenCandidato(request);
        if (candidato == null || origenesPermitidos.contains(candidato)) {
            filterChain.doFilter(request, response);
            return;
        }
        rechazar(response);
    }

    /**
     * Declara explícitamente que este filtro se OMITE en dispatches de tipo ERROR hacia
     * {@code /error} (conserva el {@code true} por defecto de {@link OncePerRequestFilter}, a
     * diferencia de {@link JwtAuthenticationFilter} que lo anula con {@code false} en PHA12TSK02).
     *
     * <p>Así el segundo pase del contenedor ante una excepción no manejada solo re-ejecuta el
     * filtro JWT (que conserva autenticación y permite el 500 honesto) sin que este filtro
     * re-evalúe el origen sobre una petición ya resuelta.</p>
     *
     * @return siempre {@code true}: el filtro NO se ejecuta en el dispatch de tipo ERROR
     */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return true;
    }

    /**
     * Indica si el método HTTP es no seguro (mutante) y por tanto sujeto al chequeo de origen.
     *
     * @param metodo método HTTP de la petición (ej. {@code POST}); {@code null} se trata como seguro
     * @return {@code true} solo para {@code POST}, {@code PUT}, {@code PATCH} o {@code DELETE}
     */
    private boolean esMetodoNoSeguro(String metodo) {
        return HttpMethod.POST.matches(metodo)
                || HttpMethod.PUT.matches(metodo)
                || HttpMethod.PATCH.matches(metodo)
                || HttpMethod.DELETE.matches(metodo);
    }

    /**
     * Indica si la ruta está exenta del chequeo de origen.
     *
     * <p>Coincidencia EXACTA con {@code /webhooks/stripe} (no prefijo): hoy existe un único
     * endpoint de webhook y una exención por prefijo abriría silenciosamente futuras rutas bajo
     * ese prefijo (ver decisión 3 del Artifact).</p>
     *
     * @param requestUri URI de la petición (ruta sin query string, ej. {@code /webhooks/stripe})
     * @return {@code true} solo si la ruta es exactamente la del webhook de Stripe
     */
    private boolean esRutaExenta(String requestUri) {
        return RUTA_WEBHOOK_STRIPE_EXENTA.equals(requestUri);
    }

    /**
     * Resuelve el origen candidato a validar: header {@code Origin} si presente (no blanco); si
     * no, origen {@code esquema://autoridad} derivado del header {@code Referer} si presente
     * (fallback de plan.md); si ambos ausentes, {@code null} (dejar pasar).
     *
     * <p>Un {@code Referer} inparseable o sin esquema/autoridad se considera AJENO (fail-closed):
     * no se puede verificar su origen, luego no se confía en él. El literal {@code "null"} que
     * envían iframes sandboxed simplemente no está en la allowlist y se rechaza sin trato
     * especial.</p>
     *
     * @param request solicitud HTTP recibida
     * @return el origen candidato (ej. {@code https://app.ejemplo.com}), o {@code null} si no hay
     *         ni {@code Origin} ni {@code Referer} verificables
     */
    private String resolverOrigenCandidato(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && !origin.isBlank()) {
            return origin.trim();
        }
        String referer = request.getHeader(HttpHeaders.REFERER);
        if (referer == null || referer.isBlank()) {
            return null;
        }
        return extraerOrigenDeReferer(referer.trim());
    }

    /**
     * Deriva el origen {@code esquema://autoridad} de una URL de {@code Referer}.
     *
     * @param referer valor del header {@code Referer} (se asume no blanco y ya con {@code trim})
     * @return el origen derivado (ej. {@code https://app.ejemplo.com:8443}), o la cadena vacía si
     *         la URL no es parseable o carece de esquema/autoridad: la cadena vacía nunca pertenece
     *         a la allowlist ({@link #parsearAllowlist} descarta vacíos), por lo que el caso
     *         inparseable se rechaza siempre (fail-closed)
     */
    private String extraerOrigenDeReferer(String referer) {
        try {
            URI uri = URI.create(referer);
            String esquema = uri.getScheme();
            String autoridad = uri.getAuthority();
            if (esquema == null || esquema.isBlank() || autoridad == null || autoridad.isBlank()) {
                return "";
            }
            return esquema.toLowerCase() + "://" + autoridad;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /**
     * Parsea la allowlist con el mismo algoritmo que {@code SecurityConfig}: separación por coma,
     * {@code trim} y descarte de entradas vacías.
     *
     * @param allowedOriginsRaw cadena cruda de orígenes separados por coma (puede ser
     *                          {@code null} si la propiedad falta y Spring no aplica el defecto)
     * @return conjunto inmutable de orígenes permitidos (comparación exacta de cadena)
     */
    private Set<String> parsearAllowlist(String allowedOriginsRaw) {
        if (allowedOriginsRaw == null || allowedOriginsRaw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(allowedOriginsRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Rechaza la petición con 403 y cuerpo JSON {@code {"mensaje":"Origen no permitido"}} sin
     * invocar el resto de la cadena.
     *
     * <p>Usa {@code setStatus} + escritura directa (nunca {@code sendError}) para no disparar un
     * dispatch ERROR del contenedor. No toca cookies: en particular, ante
     * {@code POST /auth/logout} rechazado el controlador nunca se ejecuta y por tanto nunca emite
     * la {@code Set-Cookie} de expiración — la cookie {@code jwt} de la víctima queda intacta.</p>
     *
     * @param response respuesta HTTP en construcción
     * @throws IOException si falla la escritura del cuerpo de rechazo
     */
    private void rechazar(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // Mensaje constante (sin input del atacante): no requiere escapeo JSON ni ObjectMapper.
        response.getWriter().write("{\"mensaje\":\"" + MENSAJE_ORIGEN_NO_PERMITIDO + "\"}");
    }
}
