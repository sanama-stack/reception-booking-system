package dev.reception.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.reception.support.IntegrationTest;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * <strong>No CORS configuration exists, and no endpoint grants a cross-origin caller anything.</strong>
 *
 * <p>docs/06-security.md §13: <em>"Single origin, so no CORS configuration exists — the safest
 * configuration is the absent one."</em> Written in the design phase and, until this test, asserted
 * by nothing. {@code SecurityConfig} says {@code .cors(cors -> cors.disable())} in one line that
 * could become {@code Customizer.withDefaults()} with a permissive source beside it, or a single
 * {@code @CrossOrigin} could appear on one controller, and the whole suite would stay green. An
 * absent configuration is the one nothing defends, because there is no file to review.
 *
 * <p><strong>Three shapes, because the sentence makes two different claims and they fail
 * differently.</strong>
 *
 * <ul>
 *   <li><em>Nothing is granted.</em> A simple cross-origin request is answered normally and the
 *       browser is what withholds the body — the response either carries {@code
 *       Access-Control-Allow-Origin} or the caller cannot read it. A preflight never reaches a
 *       handler at all: {@code AbstractHandlerMapping} substitutes a pre-flight handler and, with
 *       no configuration, {@code DefaultCorsProcessor} rejects it with {@code 403}. Enabling CORS
 *       for reads alone would leave the second shape clean and the first one leaking, so both are
 *       probed across the whole mapped surface.
 *   <li><em>No configuration exists.</em> Stronger, and it is the sentence in the document. A
 *       {@code @CrossOrigin} naming one partner origin grants {@link #FOREIGN_ORIGIN} nothing, so
 *       both probes above stay green while the claim has become false — and it is the claim a
 *       reader relies on when they decide they need not look. {@link
 *       #no_cors_configuration_is_registered} is that half: it reads the configuration off the
 *       objects that can hold it rather than inferring it from one caller's experience.
 * </ul>
 *
 * <p><strong>The list is derived, not typed</strong> — {@link RequestMappingHandlerMapping}, the
 * object Spring routes real requests with, for the reason {@code FormPostRejectionTest} gives: a
 * hand-written list cannot fail for the endpoint nobody remembered.
 *
 * <p><strong>And the probe is checked before it is believed.</strong> {@code Origin} and {@code
 * Access-Control-Request-Method} are both on {@code HttpURLConnection}'s restricted-header list, so
 * a client built on it drops them without saying so — every response then correctly carries no CORS
 * header, every assertion here goes green, and this test would pass unchanged against an
 * application that grants every origin everything with credentials. See {@link #the_probe_is_a_probe}.
 */
class NoCorsConfigurationTest extends IntegrationTest {

    /** Not the origin, and not a plausible partner either: nothing should ever match this. */
    private static final String FOREIGN_ORIGIN = "https://evil.example";

    /**
     * Every header by which a server grants a cross-origin caller something. {@code Vary} is
     * deliberately not one of them — it grants nothing, and it is load-bearing for the opposite
     * reason in {@link #the_probe_is_a_probe}.
     */
    private static final List<String> CORS_GRANT_HEADERS = List.of(
            HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
            HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS,
            HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
            HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
            HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
            HttpHeaders.ACCESS_CONTROL_MAX_AGE);

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Test
    @DisplayName("no endpoint answers a cross-origin request with a CORS grant")
    void no_endpoint_grants_a_foreign_origin_anything() {
        var granted = new TreeMap<String, List<String>>();

        for (Endpoint endpoint : mappedEndpoints()) {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.ORIGIN, FOREIGN_ORIGIN);
            headers.setContentType(MediaType.APPLICATION_JSON);
            grantsIn(granted, endpoint, exchange(endpoint.method(), endpoint.samplePath(), headers, "{}"));
        }

        assertThat(granted)
                .as(
                        """
                        These endpoints answered a request from %s with a header that grants a \
                        cross-origin caller something. On a single origin nothing needs one, and a \
                        grant here is readable by any page on the internet with the victim's \
                        cookies attached. docs/06-security.md §13."""
                                .formatted(FOREIGN_ORIGIN))
                .isEmpty();
    }

    @Test
    @DisplayName("no endpoint answers a preflight with a CORS grant")
    void no_endpoint_answers_a_preflight() {
        var granted = new TreeMap<String, List<String>>();

        for (Endpoint endpoint : mappedEndpoints()) {
            grantsIn(granted, endpoint, preflight(endpoint.samplePath(), endpoint.method()));
        }

        assertThat(granted)
                .as(
                        """
                        These endpoints answered a preflight with a CORS grant. A preflight is the \
                        shape that matters for writes and for any request carrying a custom header: \
                        it is asked before the real request is sent, and an answer to it is \
                        permission to send that request.""")
                .isEmpty();
    }

    /**
     * §3.1 — the claim itself, rather than one hostile origin's experience of it.
     *
     * <p>The two probes above ask what {@link #FOREIGN_ORIGIN} is granted. A {@code
     * @CrossOrigin("https://partner.example")} grants it nothing, so both stay green while §13's
     * sentence has become false — and it is the sentence a reader relies on when they decide they
     * need not look. So the configuration is read off the two objects that can hold it, rather
     * than inferred from one caller's experience.
     *
     * <p>{@code getCorsConfigurationSource()} is what {@code WebMvcConfigurer#addCorsMappings}
     * populates, and the annotation scan is the other way a handler acquires a configuration.
     * Together they are every route into MVC-level CORS; the Spring Security side of it — a source
     * wired into the filter chain — is what the two probes see, because {@code CorsFilter} runs
     * before authentication and stamps its headers on a {@code 401}.
     *
     * <p><strong>Why this is read and not probed.</strong> A behavioural sweep was written first
     * and measured blind: a {@code @CrossOrigin} planted on {@code AnalyticsController} changed no
     * response, because Spring Security answers {@code 401} in the filter chain and MVC's CORS
     * interceptor never runs. The same annotation on {@code HealthController} was caught
     * immediately. A sweep like that reports green over the entire authenticated surface while
     * seeing none of it — T96.
     */
    @Test
    @DisplayName("no CORS configuration is registered, whatever origin it would have named")
    void no_cors_configuration_is_registered() {
        assertThat(mappings.getCorsConfigurationSource())
                .as(
                        """
                        A CORS configuration source is wired into the handler mapping — which is \
                        what WebMvcConfigurer#addCorsMappings does. docs/06-security.md §13 says \
                        none exists anywhere, not that none of it matches a hostile origin.""")
                .isNull();

        var declared = new TreeSet<String>();
        mappings.getHandlerMethods().forEach((info, handler) -> {
            if (!handler.getBeanType().getPackageName().startsWith("dev.reception")) {
                return;
            }
            if (handler.getMethodAnnotation(CrossOrigin.class) != null
                    || AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), CrossOrigin.class) != null) {
                declared.add(handler.getBeanType().getSimpleName() + "#" + handler.getMethod().getName());
            }
        });

        assertThat(declared)
                .as("these carry @CrossOrigin, which is the other way a handler acquires a CORS configuration")
                .isEmpty();
    }

    /**
     * The positive control, and the reason the three assertions above are worth anything.
     *
     * <p>T89, in the form T95 sharpened it to: assert the specific thing whose absence would make
     * the main assertion vacuous. Three things could make it vacuous — the derivation could produce
     * nothing, the response headers could be unreadable through this client, and <strong>the
     * request headers could never have arrived.</strong>
     *
     * <p>The last is the real hazard and it is not hypothetical: {@code Origin} and {@code
     * Access-Control-Request-Method} are both restricted for {@code HttpURLConnection}, which is
     * what {@code TestRestTemplate} falls back to when no HTTP client is on the test classpath.
     * This suite happens to resolve {@code JdkClientHttpRequestFactory}, which sends both — but
     * that is a property of the dependency graph, not of this test, and dropping one dependency
     * could change it silently.
     *
     * <p><strong>The proof is a pair, and it has to be a pair.</strong> The preflight must carry
     * {@code Vary: Origin} and the bare {@code OPTIONS} to the same path must not. Taken alone
     * neither holds:
     *
     * <ul>
     *   <li>"the preflight varies on Origin" is also true of a blind probe against an application
     *       that <em>has</em> a CORS source, because the source alone makes the processor run;
     *   <li>"the bare OPTIONS does not vary on Origin" is also true of a blind probe against this
     *       application as it stands, because then neither request is a preflight.
     * </ul>
     *
     * <p>Together they are only both true when the two headers arrived and no configuration source
     * exists — which is the state every other assertion in this class is written for.
     */
    @Test
    @DisplayName("the probe is a probe: the headers arrive, the responses are readable, the surface is seen")
    void the_probe_is_a_probe() {
        ResponseEntity<String> preflight = preflight("/health", "GET");
        ResponseEntity<String> bareOptions = exchange("OPTIONS", "/health", new HttpHeaders(), null);

        assertThat(varyHeaderOf(preflight))
                .as(
                        """
                        A preflight must be recognised as one, and Spring can only tell it from a \
                        plain OPTIONS by reading Origin and Access-Control-Request-Method. If this \
                        does not vary on Origin, neither header arrived — the client dropped them \
                        as restricted — and every other assertion in this class is vacuous.""")
                .containsIgnoringCase("origin");

        assertThat(varyHeaderOf(bareOptions))
                .as(
                        """
                        And the same path without those two headers must NOT vary on Origin. If it \
                        does, a CORS configuration source exists — which would also make the \
                        assertion above pass for a blind probe, and is the half that makes this \
                        control a pair rather than one check.""")
                .doesNotContainIgnoringCase("origin");

        assertThat(bareOptions.getStatusCode()).as("the bare OPTIONS is answered by the handler").isEqualTo(HttpStatus.OK);
        assertThat(bareOptions.getHeaders().getFirst(HttpHeaders.ALLOW))
                .as("and it is answered by the handler rather than by the CORS processor")
                .contains("GET");

        assertThat(bareOptions.getHeaders().getFirst("X-Frame-Options"))
                .as(
                        """
                        Response headers are readable through this client at all. Without this, a \
                        header map that came back empty for every request would satisfy "no \
                        endpoint returned a CORS header" perfectly.""")
                .isEqualTo("DENY");

        Set<String> signatures = new TreeSet<>();
        mappedEndpoints().forEach(endpoint -> signatures.add(endpoint.signature()));
        assertThat(signatures)
                .as("the mapped surface, derived from the handler mapping")
                .contains(
                        "GET /health",
                        "GET /auth/me",
                        "POST /appointments",
                        "GET /analytics/summary",
                        "POST /services/{id}/deactivate",
                        "POST /public/appointments/{id}/reschedule");
    }

    private static String varyHeaderOf(ResponseEntity<String> response) {
        return String.join(", ", response.getHeaders().getOrEmpty(HttpHeaders.VARY));
    }

    private void grantsIn(TreeMap<String, List<String>> into, Endpoint endpoint, ResponseEntity<String> response) {
        List<String> present = CORS_GRANT_HEADERS.stream()
                .filter(header -> response.getHeaders().getFirst(header) != null)
                .map(header -> header + ": " + response.getHeaders().getFirst(header))
                .toList();
        if (!present.isEmpty()) {
            into.put(endpoint.signature(), present);
        }
    }

    private ResponseEntity<String> preflight(String path, String method) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ORIGIN, FOREIGN_ORIGIN);
        headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method);
        return exchange("OPTIONS", path, headers, null);
    }

    private ResponseEntity<String> exchange(String method, String path, HttpHeaders headers, String body) {
        return rest.exchange(
                "http://localhost:" + port + "/api" + path,
                HttpMethod.valueOf(method),
                new HttpEntity<>(body, headers),
                String.class);
    }

    /**
     * Every endpoint this application maps, framework endpoints excluded. Reads as well as writes:
     * a {@code @CrossOrigin} on a read controller is a cross-origin read of tenant data, which is
     * the worse of the two.
     */
    private Set<Endpoint> mappedEndpoints() {
        Set<Endpoint> endpoints = new TreeSet<>();
        mappings.getHandlerMethods().forEach((info, handler) -> {
            if (!handler.getBeanType().getPackageName().startsWith("dev.reception")) {
                return;
            }
            for (String pattern : patternsOf(info)) {
                info.getMethodsCondition()
                        .getMethods()
                        .forEach(method -> endpoints.add(new Endpoint(method.asHttpMethod().name(), pattern)));
            }
        });
        return endpoints;
    }

    private static Set<String> patternsOf(RequestMappingInfo info) {
        return info.getPathPatternsCondition() == null
                ? Set.of()
                : info.getPathPatternsCondition().getPatternValues();
    }

    private record Endpoint(String method, String pattern) implements Comparable<Endpoint> {

        /** Template variables filled with something that resolves to nothing. */
        String samplePath() {
            return pattern.replaceAll("\\{[^/]*}", "00000000-0000-0000-0000-000000000001");
        }

        String signature() {
            return method + " " + pattern;
        }

        @Override
        public int compareTo(Endpoint other) {
            return signature().compareTo(other.signature());
        }
    }
}
