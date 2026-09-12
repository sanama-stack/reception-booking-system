package dev.reception.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import dev.reception.support.IntegrationTest;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The gate the rest of the isolation suite stands on: <strong>every mapped endpoint is
 * classified</strong>.
 *
 * <p>This test asserts nothing about tenancy. It asserts that somebody has written down what
 * tenancy <em>means</em> for every endpoint the application maps — which is the part that rots.
 * Isolation tests written one module at a time are correct on the day they are written and silently
 * incomplete a phase later, because the endpoint added in between is the one nobody remembered.
 *
 * <p>The list comes from {@link RequestMappingHandlerMapping}, which is the same object Spring
 * routes requests with. There is no second source of truth to drift from: if a request can reach a
 * controller method, that method is in this list.
 */
class EndpointCoverageTest extends IntegrationTest {

    /**
     * Two beans implement this type — the application's own and springdoc's. The qualifier picks
     * the one that routes real requests; leaving it out fails with {@code NoUniqueBeanDefinition},
     * which reads like a missing bean and is the opposite.
     */
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Test
    @DisplayName("every endpoint the application maps has been classified")
    void nothing_is_unclassified() {
        Set<String> unclassified = new TreeSet<>(mappedEndpoints());
        unclassified.removeAll(EndpointCatalogue.ENDPOINTS.keySet());

        assertThat(unclassified)
                .as(
                        """
                        These endpoints are routable and nobody has said what tenant isolation means \
                        for them. Add a line to EndpointCatalogue: a probe if the endpoint touches \
                        tenant data, or an exemption with a written reason if it does not. Do not \
                        guess — an exemption is a claim that this endpoint cannot reach another \
                        Business's rows.""")
                .isEmpty();
    }

    @Test
    @DisplayName("no classification names an endpoint that no longer exists")
    void nothing_is_stale() {
        Set<String> vanished = new TreeSet<>(EndpointCatalogue.ENDPOINTS.keySet());
        vanished.removeAll(mappedEndpoints());

        assertThat(vanished)
                .as(
                        """
                        These classifications name endpoints the application no longer maps. A stale \
                        entry is worse than a missing one: it makes the catalogue look complete while \
                        the probe it describes runs against nothing.""")
                .isEmpty();
    }

    /**
     * The suite is worth having only if something is actually probed, and the count is an honest
     * guard against a catalogue quietly turning into a list of exemptions.
     */
    @Test
    @DisplayName("the catalogue is mostly probes, not mostly exemptions")
    void the_catalogue_has_not_become_a_list_of_excuses() {
        long exempt = EndpointCatalogue.ENDPOINTS.values().stream()
                .filter(classification -> classification.isolation() == EndpointCatalogue.Isolation.NO_TENANT)
                .count();

        assertThat(exempt)
                .as("exemptions out of %d endpoints; every one of them is a claim that has to hold",
                        EndpointCatalogue.ENDPOINTS.size())
                .isLessThan(EndpointCatalogue.ENDPOINTS.size() / 4);
    }

    @Test
    @DisplayName("every exemption carries a reason")
    void no_exemption_is_silent() {
        EndpointCatalogue.ENDPOINTS.forEach((endpoint, classification) -> {
            if (classification.isolation() == EndpointCatalogue.Isolation.NO_TENANT) {
                assertThat(classification.why())
                        .as("%s is exempt from the isolation probes and says nothing about why", endpoint)
                        .isNotBlank();
            }
        });
    }

    /**
     * Every endpoint declared by this application, as {@code "METHOD /pattern"}.
     *
     * <p>Framework endpoints are excluded <em>by where they are declared</em>, not by name:
     * springdoc's {@code /openapi} and {@code /docs} and Spring's {@code /error} are mapped by
     * classes outside {@code dev.reception}. Filtering on the package means a new springdoc version
     * that renames its paths changes nothing here, and an endpoint of ours can never be excluded by
     * resembling one of theirs.
     */
    private Set<String> mappedEndpoints() {
        Set<String> endpoints = new TreeSet<>();
        mappings.getHandlerMethods().forEach((info, handler) -> {
            if (!isOurs(handler)) {
                return;
            }
            for (String pattern : patternsOf(info)) {
                info.getMethodsCondition().getMethods().forEach(method -> endpoints.add(method + " " + pattern));
            }
        });
        return endpoints;
    }

    private static boolean isOurs(HandlerMethod handler) {
        return handler.getBeanType().getPackageName().startsWith("dev.reception");
    }

    private static Set<String> patternsOf(RequestMappingInfo info) {
        return info.getPathPatternsCondition() == null
                ? Set.of()
                : info.getPathPatternsCondition().getPatternValues();
    }
}
