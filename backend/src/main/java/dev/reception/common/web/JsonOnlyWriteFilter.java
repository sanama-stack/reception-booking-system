package dev.reception.common.web;

import dev.reception.common.error.ErrorCode;
import dev.reception.common.error.ProblemJsonWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The half of docs/06-security.md §13's CSRF defence that Spring does not provide.
 *
 * <p>The section says state-changing requests "additionally require {@code Content-Type:
 * application/json}, which blocks the form-post CSRF shape". That was true of every endpoint with a
 * {@code @RequestBody} — Spring's converter negotiation answers {@code 415} because no converter
 * turns a form body into a DTO — and <strong>false of the ten that take no body at all</strong>,
 * where there is nothing to convert and therefore nothing to refuse. Measured in phase 11: a
 * form-encoded {@code POST /auth/logout} answered {@code 204}. The claim was a partial truth stated
 * as a whole one, which is the shape §5's rate-limit list failed in.
 *
 * <p><strong>An HTML form can send exactly three content types</strong> — {@code
 * application/x-www-form-urlencoded}, {@code multipart/form-data} and {@code text/plain}, the values
 * its {@code enctype} accepts. Those are what this refuses, rather than requiring JSON positively:
 * a request with no {@code Content-Type} at all cannot have come from a form either, and several
 * legitimate clients send none on a body-less {@code POST}. Refusing the three that can be forged
 * is the control; requiring the one that cannot would break callers for no security gain.
 *
 * <p><strong>This is defence in depth and is not the primary control.</strong> {@code SameSite=Lax}
 * already stops the browser attaching cookies to a cross-site form post, so none of these requests
 * arrives authenticated. That is why the measured gap was not an exploit — and it is also why the
 * gap mattered: two layers were documented, one existed, and the missing one was the one a reader
 * would have counted on if {@code SameSite} were ever relaxed.
 *
 * <p>Ordered immediately after {@link dev.reception.common.ratelimit.RateLimitFilter} and therefore
 * before authentication: a forged request should be refused on its shape, without the cost of a
 * lookup and without its outcome depending on whether the caller happened to be signed in.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 11)
public class JsonOnlyWriteFilter extends OncePerRequestFilter {

    /**
     * The three an HTML form's {@code enctype} can produce, and the whole of the list.
     *
     * <p>Compared by type and subtype so a charset parameter cannot slip one past — {@code
     * text/plain;charset=UTF-8} is a form post.
     */
    private static final Set<MediaType> FORM_ENCODABLE =
            Set.of(MediaType.APPLICATION_FORM_URLENCODED, MediaType.MULTIPART_FORM_DATA, MediaType.TEXT_PLAIN);

    private static final Set<String> STATE_CHANGING =
            Set.of(HttpMethod.POST.name(), HttpMethod.PUT.name(), HttpMethod.PATCH.name(), HttpMethod.DELETE.name());

    private final ProblemJsonWriter writer;

    public JsonOnlyWriteFilter(ProblemJsonWriter writer) {
        this.writer = writer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isForgeable(request)) {
            chain.doFilter(request, response);
            return;
        }
        writer.write(
                response,
                ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                "State-changing requests must not use a form content type.",
                request.getRequestURI());
    }

    private static boolean isForgeable(HttpServletRequest request) {
        if (!STATE_CHANGING.contains(request.getMethod())) {
            return false;
        }
        String header = request.getContentType();
        if (header == null || header.isBlank()) {
            // No form sends this, so there is nothing to refuse. See the class comment.
            return false;
        }
        try {
            MediaType type = MediaType.parseMediaType(header);
            return FORM_ENCODABLE.stream().anyMatch(form -> form.getType().equals(type.getType())
                    && form.getSubtype().equals(type.getSubtype()));
        } catch (InvalidMediaTypeException unparseable) {
            // Unparseable is not a form either, and refusing it here would answer with this
            // filter's message in place of the 415 Spring already gives it a better one for.
            return false;
        }
    }
}
