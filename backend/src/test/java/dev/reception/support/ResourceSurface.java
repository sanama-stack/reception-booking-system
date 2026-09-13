package dev.reception.support;

import java.util.Set;
import java.util.TreeSet;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;

/**
 * The paths this application serves from a resource handler, derived from the mapping itself.
 *
 * <p>Static resources are served by {@code ResourceHttpRequestHandler}, which declares no handler
 * methods, so they can never appear in a derivation built on {@code RequestMappingHandlerMapping}.
 * Two tests need to know what they are — one asks whether a rate-limit policy covers them, the other
 * whether they are part of the documentation surface — and <strong>they must agree.</strong>
 *
 * <p>That is the whole reason this is a class rather than a private method twice. A generator copied
 * between two tests is two generators, and the day they disagree is the day one of them is asserting
 * something about paths the other does not believe exist. The repository has five copies of {@code
 * patternsOf} and they have been harmless; this one produces a <em>value</em> that two assertions
 * compare against the world, which is a different risk.
 */
public final class ResourceSurface {

    /**
     * The leaf to ask for when a pattern ends in {@code **} and so names no file of its own.
     *
     * <p>The one assumption here, and callers are expected to check it rather than trust it:
     * {@code RateLimitCoverageTest} requires every path this produces to be served with a {@code
     * 200}, so a resource root with no {@code index.html} fails loudly instead of quietly
     * contributing a path nothing serves.
     */
    public static final String DEFAULT_LEAF = "index.html";

    private ResourceSurface() {}

    /** One concrete path under each pattern the resource handler mapping registers. */
    public static Set<String> samplePaths(ApplicationContext context) {
        SimpleUrlHandlerMapping resources =
                context.getBean("resourceHandlerMapping", SimpleUrlHandlerMapping.class);
        Set<String> samples = new TreeSet<>();
        resources.getUrlMap().keySet().forEach(pattern -> samples.add(sampleUnder(pattern)));
        return samples;
    }

    /**
     * A concrete path under {@code pattern}, taking every wildcard at its narrowest.
     *
     * <p>A {@code *} inside a segment matches zero characters, so it is simply dropped:
     * {@code /swagger-ui*} becomes {@code /swagger-ui}. A whole segment of {@code **} names no file,
     * so it becomes {@link #DEFAULT_LEAF}.
     *
     * <p>Narrowest is the right choice and not the convenient one: it produces the path a caller
     * would actually ask for, which is what both a policy and the running application have to be
     * asked about.
     */
    public static String sampleUnder(String pattern) {
        StringBuilder path = new StringBuilder();
        for (String segment : pattern.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            path.append('/').append(segment.equals("**") ? DEFAULT_LEAF : segment.replace("*", ""));
        }
        return path.toString();
    }
}
