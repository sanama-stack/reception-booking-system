package dev.reception.support;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Every endpoint {@link RequestMappingHandlerMapping} routes, derived once.
 *
 * <p>Eight test classes ask Spring what this application serves, and until this class existed each
 * one derived the answer for itself: six byte-identical copies of {@code patternsOf}, nine copies of
 * the {@code dev.reception} package filter, three copies of an {@code Endpoint} record, and one
 * caller with no null guard at all. {@code ResourceSurface}'s javadoc judged that duplication
 * harmless, on the grounds that a repeated <em>filter</em> is safer than a repeated
 * <em>generator</em>. <strong>That judgement was wrong in one specific way, and
 * {@code MappedSurfaceTest}'s 556 lines are the bill for it.</strong>
 *
 * <p>The copies did not disagree with each other. They agreed — including about what they could not
 * see. Every one of them paired a pattern with a verb by iterating {@code
 * info.getMethodsCondition().getMethods()}, so a mapping that declares no HTTP method yielded an
 * empty loop and contributed nothing. It was not filtered out; <em>it never arrived</em>, which is
 * why no control reported it and why no control could: a blind spot is exactly the place a test
 * says nothing about. Spring's {@code /error} is mapped that way.
 *
 * <p><strong>So this class hands back the methodless mappings rather than swallowing them</strong>,
 * under the verb {@link #ANY} — the vocabulary {@code MappedSurfaceTest} already used for them. A
 * caller that wants only endpoints with a declared verb says {@link #declaringAMethod()} and says it
 * <em>at the call site</em>, where the next reader can see the narrowing and ask what it costs. The
 * silent drop becomes a written decision, and a new test that forgets to make it gets {@code ANY}
 * endpoints in its face instead of a quietly shorter list.
 *
 * <p><strong>This class refuses to be blind for the other reason too.</strong> A {@link
 * RequestMappingInfo} with no path patterns is an {@link IllegalStateException} here, not an empty
 * set — "maps nothing" and "could not be asked" are the same value and opposite facts, and the whole
 * of {@code MappedSurfaceTest} exists because the second kept being mistaken for the first.
 *
 * <p>What this deliberately does <em>not</em> hold is judgement. {@code EndpointCatalogue} says what
 * isolation means for each endpoint and {@code MappedSurfaceTest} says what sits outside this
 * mapping altogether; both are prose somebody has to write, and neither belongs in a derivation.
 * This answers one question only: what does {@code RequestMappingHandlerMapping} route.
 */
public final class MappedSurface {

    /** The package a handler must be declared in to be ours. */
    public static final String OUR_PACKAGE = "dev.reception";

    /**
     * The verb recorded for a mapping that declares none.
     *
     * <p>Not an HTTP method and not meant to be sent: it is how a mapping that answers whichever
     * verb the request used appears in a surface that is keyed by verb. {@link #declaringAMethod()}
     * is how a caller excludes these, and the exclusion is the caller's to justify.
     */
    public static final String ANY = "ANY";

    /**
     * The verbs a mapping can declare, read off {@link RequestMethod} rather than typed.
     *
     * <p>Derived for the usual reason: a typed list is correct until the framework adds a verb, and
     * the failure would be a narrowing that silently selects nothing.
     */
    private static final Set<String> DECLARABLE =
            Arrays.stream(RequestMethod.values()).map(Enum::name).collect(Collectors.toCollection(TreeSet::new));

    /**
     * The value substituted for a template variable in {@link Endpoint#samplePath()}.
     *
     * <p>A UUID, because two of the three tests that used to carry their own copy of this record
     * issue real HTTP requests, and Spring binds {@code {id}} to a {@code UUID} before the handler
     * runs: a filler that is not one answers {@code 400} and the test then asserts about the wrong
     * refusal. The third only feeds the path to {@code AntPathMatcher} and the security matchers,
     * which are indifferent to the value — so this is the filler that is correct in all three
     * places, rather than the one two of them happened to use.
     *
     * <p>Deliberately meaningless: nothing downstream resolves it, and a value that looked like a
     * real slug would suggest it had been looked up.
     */
    public static final String SAMPLE_ID = "00000000-0000-0000-0000-000000000001";

    /** One mapped endpoint: the pattern as Spring declares it, and the verb it answers. */
    public record Endpoint(String method, String pattern) implements Comparable<Endpoint> {

        /** {@code "METHOD /pattern"}, the form every catalogue and assertion message keys on. */
        public String signature() {
            return method + " " + pattern;
        }

        /**
         * The pattern with every template variable filled in.
         *
         * <p>Neither {@code AntPathMatcher} nor the security matchers can be asked about a pattern;
         * both answer about a path. See {@link MappedSurface#SAMPLE_ID} for why the filler is what
         * it is.
         */
        public String samplePath() {
            return pattern.replaceAll("\\{[^/]*}", SAMPLE_ID);
        }

        /** Whether Spring pairs this pattern with a real verb, or {@link MappedSurface#ANY}. */
        public boolean declaresAMethod() {
            return !ANY.equals(method);
        }

        @Override
        public int compareTo(Endpoint other) {
            return signature().compareTo(other.signature());
        }
    }

    /** One row of the derivation: what Spring routes, and the handler it routes to. */
    private record Mapped(Endpoint endpoint, HandlerMethod handler) {}

    private final List<Mapped> rows;

    private MappedSurface(List<Mapped> rows) {
        this.rows = rows;
    }

    /**
     * Everything {@code mappings} routes, narrowed by nothing.
     *
     * <p>Methodless mappings are included under {@link #ANY} and framework-declared handlers are
     * included as themselves. Both are narrowings a caller makes on purpose, not defaults it
     * inherits.
     *
     * <p>The parameter is the shared supertype rather than {@link RequestMappingHandlerMapping},
     * because the actuator's {@code WebMvcEndpointHandlerMapping} is keyed by {@link
     * RequestMappingInfo} too and {@code MappedSurfaceTest} has to read it with the same derivation
     * it reads the controllers with. A second mapping deserves the same eyes, not a second copy of
     * this loop — that is the mistake this class exists to undo.
     *
     * @param mappings a mapping keyed by {@link RequestMappingInfo}. For the application's own
     *     controllers this is {@code requestMappingHandlerMapping}, and two beans implement that
     *     type — ours and springdoc's — so callers must inject it {@code @Qualifier}ed; omitting the
     *     qualifier fails with {@code NoUniqueBeanDefinition}, which reads like a missing bean and
     *     is the opposite.
     */
    public static MappedSurface of(RequestMappingInfoHandlerMapping mappings) {
        List<Mapped> rows = new ArrayList<>();
        mappings.getHandlerMethods().forEach((info, handler) -> {
            for (String pattern : patternsOf(info)) {
                Set<String> methods = new TreeSet<>();
                info.getMethodsCondition().getMethods().forEach(method -> methods.add(method.asHttpMethod()
                        .name()));
                if (methods.isEmpty()) {
                    rows.add(new Mapped(new Endpoint(ANY, pattern), handler));
                } else {
                    methods.forEach(method -> rows.add(new Mapped(new Endpoint(method, pattern), handler)));
                }
            }
        });
        return new MappedSurface(rows);
    }

    /**
     * The patterns of one mapping.
     *
     * <p>Throws rather than answering {@link Set#of()} for a mapping with no path patterns. Under
     * {@code PathPatternParser}, which Boot has defaulted to since 3.0 and this application does not
     * override, every {@code RequestMappingInfo} has them; a null would mean the mapping is being
     * routed by the deprecated {@code PatternsRequestCondition}, and every derivation in this
     * repository would then step straight over it without reporting anything. The six copies this
     * class replaces all returned the empty set here, so that day would have arrived as silence.
     */
    private static Set<String> patternsOf(RequestMappingInfo info) {
        if (info.getPathPatternsCondition() == null) {
            throw new IllegalStateException(
                    "Mapping declares no path patterns and would be invisible to every endpoint derivation "
                            + "in this suite: " + info + ". This means PathPatternParser is no longer in use. "
                            + "Do not restore the empty set — teach this method to read the other condition.");
        }
        return info.getPathPatternsCondition().getPatternValues();
    }

    /** Endpoints whose handler is declared inside {@link #OUR_PACKAGE}. */
    public MappedSurface ours() {
        return where(handler -> handler.getBeanType().getPackageName().startsWith(OUR_PACKAGE));
    }

    /**
     * Endpoints whose handler is declared outside {@link #OUR_PACKAGE}.
     *
     * <p>The complement of {@link #ours()}, and the reason both exist as named methods: the filter
     * used to be written out at each call site, once inverted, and an inverted copy of a filter is
     * the copy that stops matching the others when the condition changes.
     */
    public MappedSurface framework() {
        return where(handler -> !handler.getBeanType().getPackageName().startsWith(OUR_PACKAGE));
    }

    /**
     * Endpoints Spring pairs with a real HTTP verb.
     *
     * <p><strong>Every caller of this is excluding something, and owes a reason.</strong> What it
     * excludes is the mappings that answer any verb — today Spring's {@code /error} — which no
     * control keyed by verb can say anything about. {@code MappedSurfaceTest} holds that set to the
     * property that makes the exclusion harmless: nothing invisible is reachable without
     * authentication. Cite it, rather than inheriting the narrowing by accident.
     */
    public MappedSurface declaringAMethod() {
        return new MappedSurface(
                rows.stream().filter(row -> row.endpoint().declaresAMethod()).toList());
    }

    /** Endpoints whose pattern starts with {@code prefix}. */
    public MappedSurface under(String prefix) {
        return new MappedSurface(rows.stream()
                .filter(row -> row.endpoint().pattern().startsWith(prefix))
                .toList());
    }

    /**
     * Endpoints answering any of {@code verbs}.
     *
     * <p>A verb this application could never declare is an {@link IllegalArgumentException} rather
     * than a narrowing to nothing. Every caller of this feeds the result to an emptiness assertion,
     * and an empty set produced by a misspelt verb passes one exactly as well as a clean surface
     * does — the same shape as the blind derivation this class was written to remove, arriving
     * through a typo instead of through a loop.
     *
     * <p>{@link #ANY} is accepted, and is the only accepted value that is not an HTTP method.
     */
    public MappedSurface answering(String... verbs) {
        Set<String> wanted = Set.of(verbs);
        wanted.forEach(verb -> {
            if (!ANY.equals(verb) && !DECLARABLE.contains(verb)) {
                throw new IllegalArgumentException("No mapping can declare '" + verb
                        + "', so narrowing to it selects nothing and every assertion downstream passes on an "
                        + "empty set. Spring's RequestMethod declares " + DECLARABLE + ", and this class adds "
                        + ANY + " for a mapping that declares none.");
            }
        });
        return new MappedSurface(rows.stream()
                .filter(row -> wanted.contains(row.endpoint().method()))
                .toList());
    }

    private MappedSurface where(Predicate<HandlerMethod> handlers) {
        return new MappedSurface(
                rows.stream().filter(row -> handlers.test(row.handler())).toList());
    }

    /** What is left, as endpoints. */
    public Set<Endpoint> endpoints() {
        return new TreeSet<>(rows.stream().map(Mapped::endpoint).toList());
    }

    /** What is left, as {@code "METHOD /pattern"}. */
    public Set<String> signatures() {
        return new TreeSet<>(rows.stream().map(row -> row.endpoint().signature()).toList());
    }

    /** What is left, as patterns — deduplicated across the verbs that share one. */
    public Set<String> patterns() {
        return new TreeSet<>(rows.stream().map(row -> row.endpoint().pattern()).toList());
    }

    /** The handler methods behind what is left, deduplicated across the endpoints that share one. */
    public Set<HandlerMethod> handlers() {
        return new LinkedHashSet<>(rows.stream().map(Mapped::handler).toList());
    }
}
