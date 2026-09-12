package dev.reception.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.reception.support.IntegrationTest;
import jakarta.validation.constraints.Size;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * <strong>Every string field on every request body has a maximum length.</strong>
 *
 * <p>docs/06-security.md §7, stated categorically there: "Every string field has a maximum length.
 * Unbounded text is a denial-of-service vector." Nothing checked it, and no global request-size cap
 * exists to fall back on — neither {@code max-http-form-post-size}, which does not apply to a JSON
 * body, nor anything else in {@code application.yml}.
 *
 * <p>It found {@code PublicRequests.Authority}: three bare strings on the two <em>unauthenticated</em>
 * customer-authority endpoints, each with a bounded twin a few lines away — {@code Lookup} bounds the
 * same confirmation code at 16 and the same phone number at 30, and {@code StartSession} bounds the
 * same manage token at 500. The record's own comment explains why none of them is {@code @NotBlank}:
 * "exactly one of these" is not expressible as a field annotation. That argument is sound and it is
 * about <em>presence</em> — it took the length bound with it, and length was never part of it.
 *
 * <p>The bodies are derived from {@link RequestMappingHandlerMapping}, so a DTO is covered the moment
 * a controller takes it, and nested records and collection elements are walked rather than trusted.
 */
class RequestFieldLengthTest extends IntegrationTest {

    /**
     * String fields that may be unbounded, and why.
     *
     * <p>Empty, and on purpose. An entry here is a claim that a caller may send this field
     * arbitrarily large, which for an unauthenticated endpoint is a claim worth having to write
     * down.
     */
    private static final Map<String, String> UNBOUNDED_ON_PURPOSE = new LinkedHashMap<>();

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Test
    @DisplayName("no string a controller accepts as a body is unbounded")
    void every_string_field_has_a_maximum() {
        Fields fields = walkRequestBodies();

        Set<String> unbounded = new TreeSet<>(fields.unbounded());
        unbounded.removeAll(UNBOUNDED_ON_PURPOSE.keySet());

        assertThat(unbounded)
                .as(
                        """
                        These string fields have no @Size(max). An unbounded string on a request \
                        body is a denial-of-service vector (docs/06-security.md §7), and on a public \
                        endpoint it is one anybody can pull. Add a bound — if the field has a twin \
                        elsewhere, use that twin's — or add it to UNBOUNDED_ON_PURPOSE with the \
                        reason it may be arbitrarily large.""")
                .isEmpty();
    }

    /**
     * The positive control, and the reason the assertion above is worth anything.
     *
     * <p>T89, and this one was not hypothetical: the first version of this derivation read {@code
     * RecordComponent.getAnnotation(Size.class)} and reported <strong>all 59 fields unbounded and
     * none bounded</strong>, including several whose {@code @Size} is plainly visible in the source.
     * {@code @Size} has no {@code RECORD_COMPONENT} in its {@code @Target}, so javac propagates it to
     * the field, the accessor and the constructor parameter and the component carries nothing. Read
     * the other way round — "is this one bounded?" — the same bug would have reported every field
     * bounded and passed silently forever.
     *
     * <p>So the bounded set is asserted too, by name and by value. A derivation that has stopped
     * seeing annotations fails here.
     */
    @Test
    @DisplayName("the derivation reads annotations at all, and sees the bodies it should")
    void the_derivation_still_sees_the_annotations() {
        Fields fields = walkRequestBodies();

        assertThat(fields.bodies())
                .as("request bodies, derived from the handler mapping")
                .contains("Register", "Login", "CreateAppointment", "PatchBusiness", "Lookup");

        assertThat(fields.bounded())
                .as(
                        """
                        Fields whose @Size the derivation can actually read. An empty or tiny set \
                        here means the reflection stopped working, not that the application stopped \
                        validating — and every other assertion in this class would then pass by \
                        seeing nothing.""")
                .hasSizeGreaterThan(40)
                .contains(
                        "Register.email (max 254)",
                        "Lookup.confirmationCode (max 16)",
                        "Lookup.phone (max 30)",
                        // Nested one level down, and named here because that is the only way this
                        // control notices a walk that stopped following records. Authority is the
                        // record the whole class was written for: a derivation that never reaches it
                        // reports nothing unbounded and passes, which is what happened when this
                        // line was not here.
                        "CancelAppointment.authority.confirmationCode (max 16)");
    }

    @Test
    @DisplayName("every exemption carries a reason, and names a field that still exists")
    void no_exemption_is_silent_or_stale() {
        Fields fields = walkRequestBodies();

        UNBOUNDED_ON_PURPOSE.forEach((field, why) -> assertThat(why)
                .as("%s is allowed to be unbounded and says nothing about why", field)
                .isNotBlank());

        Set<String> vanished = new TreeSet<>(UNBOUNDED_ON_PURPOSE.keySet());
        vanished.removeAll(fields.unbounded());

        assertThat(vanished)
                .as(
                        """
                        These exemptions name fields that are no longer unbounded, or no longer \
                        exist. A stale exemption silently excuses a future field that happens to \
                        land at the same path.""")
                .isEmpty();
    }

    private record Fields(Set<String> bodies, Set<String> bounded, Set<String> unbounded) {}

    private Fields walkRequestBodies() {
        Set<String> bodies = new TreeSet<>();
        Set<String> bounded = new TreeSet<>();
        Set<String> unbounded = new TreeSet<>();

        mappings.getHandlerMethods().forEach((info, handler) -> {
            if (!handler.getBeanType().getPackageName().startsWith("dev.reception")) {
                return;
            }
            for (MethodParameter parameter : handler.getMethodParameters()) {
                if (parameter.getParameterAnnotation(RequestBody.class) == null) {
                    continue;
                }
                Class<?> body = parameter.getParameterType();
                bodies.add(body.getSimpleName());
                walk(body, body.getSimpleName(), bounded, unbounded);
            }
        });
        return new Fields(bodies, bounded, unbounded);
    }

    /** Breadth-first through a record's components, following nested records and element types. */
    private static void walk(Class<?> root, String path, Set<String> bounded, Set<String> unbounded) {
        Deque<Object[]> queue = new ArrayDeque<>();
        Set<Class<?>> seen = new HashSet<>();
        queue.add(new Object[] {root, path});

        while (!queue.isEmpty()) {
            Object[] next = queue.poll();
            Class<?> current = (Class<?>) next[0];
            String at = (String) next[1];
            if (!current.isRecord() || !seen.add(current)) {
                continue;
            }
            for (RecordComponent component : current.getRecordComponents()) {
                String where = at + "." + component.getName();
                Class<?> type = component.getType();
                if (type == String.class) {
                    Size size = sizeOf(current, component);
                    if (size == null || size.max() == Integer.MAX_VALUE) {
                        unbounded.add(where);
                    } else {
                        bounded.add(where + " (max " + size.max() + ")");
                    }
                } else if (type.isRecord()) {
                    queue.add(new Object[] {type, where});
                } else if (Iterable.class.isAssignableFrom(type)
                        && component.getGenericType() instanceof ParameterizedType parameterized
                        && parameterized.getActualTypeArguments()[0] instanceof Class<?> element) {
                    if (element == String.class) {
                        Size size = sizeOf(current, component);
                        if (size == null || size.max() == Integer.MAX_VALUE) {
                            unbounded.add(where + "[]");
                        } else {
                            bounded.add(where + "[] (max " + size.max() + ")");
                        }
                    } else {
                        queue.add(new Object[] {element, where + "[]"});
                    }
                }
            }
        }
    }

    /**
     * <strong>Not {@code component.getAnnotation(Size.class)}, and this is the trap.</strong>
     *
     * <p>{@code @Size}'s {@code @Target} does not include {@code RECORD_COMPONENT}, so javac
     * propagates it to the field, the accessor and the constructor parameter and the record
     * component itself carries nothing at all. Asking the component returns {@code null} for every
     * field in this application, including the ones whose annotation is three lines up in the
     * source. See {@link #the_derivation_still_sees_the_annotations()}.
     */
    private static Size sizeOf(Class<?> owner, RecordComponent component) {
        Size onAccessor = component.getAccessor().getAnnotation(Size.class);
        if (onAccessor != null) {
            return onAccessor;
        }
        try {
            return owner.getDeclaredField(component.getName()).getAnnotation(Size.class);
        } catch (NoSuchFieldException impossible) {
            throw new AssertionError("A record component with no backing field: " + component, impossible);
        }
    }
}
