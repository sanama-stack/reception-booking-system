package dev.reception.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.reception.support.IntegrationTest;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * <strong>No state-changing endpoint accepts a content type an HTML form can send.</strong>
 *
 * <p>docs/06-security.md §13 says state-changing requests "additionally require {@code
 * Content-Type: application/json}, which blocks the form-post CSRF shape". That was true of every
 * endpoint carrying a {@code @RequestBody} — Spring answers {@code 415} because no converter turns
 * a form body into a DTO — and <strong>false of the ten that take no body</strong>, where there is
 * nothing to convert and so nothing to refuse. Measured in phase 11 before {@link
 * JsonOnlyWriteFilter} existed: a form-encoded {@code POST /auth/logout} answered {@code 204}.
 *
 * <p>The list is derived, not typed. {@link RequestMappingHandlerMapping} is the object Spring
 * routes real requests with, so an endpoint is in this test the moment it is mapped — which is the
 * whole lesson of {@code RateLimitCoverageTest}: the endpoint nobody remembered is exactly the one
 * a hand-written list cannot fail for, because forgetting it in the controller and forgetting it in
 * the list are one act.
 *
 * <p><strong>Unauthenticated on purpose.</strong> The filter runs before authentication, so every
 * one of these answers {@code 415} with no credentials at all. That is the assertion: without the
 * filter a protected endpoint answers {@code 401} and a public one does its work, and neither is
 * {@code 415}.
 */
class FormPostRejectionTest extends IntegrationTest {

    /**
     * Everything an HTML form's {@code enctype} can produce. {@code text/plain} carries a charset
     * here because that is how a browser sends it, and a filter comparing full header strings
     * rather than type and subtype would let it through.
     */
    private static final MediaType[] FORM_TYPES = {
        MediaType.APPLICATION_FORM_URLENCODED,
        MediaType.MULTIPART_FORM_DATA,
        MediaType.valueOf("text/plain;charset=UTF-8"),
    };

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Test
    @DisplayName("every state-changing endpoint refuses every content type a form can send")
    void no_write_accepts_a_form_post() {
        var accepted = new TreeMap<String, HttpStatus>();

        for (Endpoint endpoint : stateChangingEndpoints()) {
            for (MediaType type : FORM_TYPES) {
                HttpStatus status = statusFor(endpoint, type, "x=1");
                if (status != HttpStatus.UNSUPPORTED_MEDIA_TYPE) {
                    accepted.put(endpoint.signature() + " as " + type, status);
                }
            }
        }

        assertThat(accepted)
                .as(
                        """
                        These state-changing endpoints answered something other than 415 to a \
                        content type an HTML form can produce. A 401 counts as a failure here: it \
                        means the request was refused for the credentials it lacked rather than for \
                        the shape it had, and the same request from a signed-in victim's browser \
                        would have gone through. See JsonOnlyWriteFilter.""")
                .isEmpty();
    }

    /**
     * The positive control, and the reason the assertion above is worth anything.
     *
     * <p>T89 again. "Nothing answered other than 415" is equally true of a filter that answers
     * {@code 415} to everything, and of a derivation that produced no endpoints at all. So: the same
     * endpoints, sent JSON, must <em>not</em> be refused for their media type — and the derivation
     * must still contain the writes this application is known to have.
     */
    @Test
    @DisplayName("the same endpoints accept JSON, and the derivation still sees the application")
    void the_filter_refuses_the_shape_and_not_the_request() {
        Set<String> signatures = new TreeSet<>();
        stateChangingEndpoints().forEach(endpoint -> signatures.add(endpoint.signature()));

        assertThat(signatures)
                .as("the state-changing surface, derived from the handler mapping")
                .contains(
                        "POST /auth/login",
                        "POST /auth/logout",
                        "POST /auth/refresh",
                        "POST /appointments",
                        "DELETE /business/faqs/{id}",
                        "POST /services/{id}/deactivate");

        assertThat(signatures)
                .as("a read is not a write, and a filter that refused one would be a bug of its own")
                .doesNotContain("GET /auth/me", "GET /analytics/summary");

        var refusedAsJson = new TreeSet<String>();
        for (Endpoint endpoint : stateChangingEndpoints()) {
            if (statusFor(endpoint, MediaType.APPLICATION_JSON, "{}") == HttpStatus.UNSUPPORTED_MEDIA_TYPE) {
                refusedAsJson.add(endpoint.signature());
            }
        }

        assertThat(refusedAsJson)
                .as(
                        """
                        These refused application/json for its media type. The filter is supposed to \
                        refuse three content types, not everything that is not on a list — and a \
                        filter that refuses all of them passes the test above while breaking every \
                        client.""")
                .isEmpty();
    }

    /**
     * A body-less write with no {@code Content-Type} at all is still allowed through.
     *
     * <p>Deliberate, and the reason the filter refuses three types rather than requiring one. No
     * HTML form omits the header — {@code enctype} always resolves to one of the three — so there
     * is nothing to defend against here, and several legitimate clients send nothing on a
     * body-less {@code POST}. Requiring JSON positively would refuse them for no gain. Asserted
     * because "require JSON" is what §13 says in words and is the change a future reader will make.
     */
    @Test
    @DisplayName("a write with no content type at all is not refused for its media type")
    void an_absent_content_type_is_not_a_form_post() {
        assertThat(statusFor(new Endpoint("POST", "/auth/logout"), null, null))
                .isNotEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    private HttpStatus statusFor(Endpoint endpoint, MediaType type, String body) {
        HttpHeaders headers = new HttpHeaders();
        if (type != null) {
            headers.setContentType(type);
        }
        ResponseEntity<String> response = rest.exchange(
                "http://localhost:" + port + "/api" + endpoint.samplePath(),
                HttpMethod.valueOf(endpoint.method()),
                new HttpEntity<>(body, headers),
                String.class);
        return HttpStatus.valueOf(response.getStatusCode().value());
    }

    /** Every POST, PUT, PATCH and DELETE this application maps, framework endpoints excluded. */
    private Set<Endpoint> stateChangingEndpoints() {
        Set<Endpoint> endpoints = new TreeSet<>();
        mappings.getHandlerMethods().forEach((info, handler) -> {
            if (!handler.getBeanType().getPackageName().startsWith("dev.reception")) {
                return;
            }
            for (String pattern : patternsOf(info)) {
                info.getMethodsCondition().getMethods().forEach(method -> {
                    String name = method.asHttpMethod().name();
                    if (Set.of("POST", "PUT", "PATCH", "DELETE").contains(name)) {
                        endpoints.add(new Endpoint(name, pattern));
                    }
                });
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
