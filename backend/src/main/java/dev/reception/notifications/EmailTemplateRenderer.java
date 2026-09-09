package dev.reception.notifications;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Turns a {@link MailModel} into the subject and two bodies stored on a {@link Notification}.
 *
 * <p><strong>No template engine.</strong> The whole requirement is placeholder substitution and one
 * conditional, and the two things a real engine would add here are a dependency and the ability to
 * put logic in a template — the second of which is the thing that makes email templates rot. The
 * syntax is deliberately not extensible: {@code {{key}}} and {@code &#123;&#123;#key&#125;&#125;…}
 * blocks, nothing else, no expressions.
 *
 * <p><strong>HTML values are escaped; text values are not.</strong> A Customer's name and a
 * Business's name reach these templates unfiltered from a form, so a name containing a tag would
 * otherwise be markup in everyone's inbox. The text body has no markup to break, and escaping it
 * would render {@code &amp;} into a plain-text message.
 *
 * <p><strong>Times are formatted in the Business's timezone and a fixed locale.</strong> The
 * timezone because a customer reads "2pm" as their salon's 2pm, and the locale because the JVM's
 * default would make an email's wording depend on the machine that happened to send it — a
 * difference that would show up as a failing test on one developer's laptop and nowhere else.
 */
@Component
public class EmailTemplateRenderer {

    /** {@code {{key}}} — a value, escaped for HTML. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z]+)}}");

    /** <code>{{#key}}…{{/key}}</code> — kept when the value is present, removed when it is blank. */
    private static final Pattern BLOCK = Pattern.compile("\\{\\{#([a-zA-Z]+)}}(.*?)\\{\\{/\\1}}", Pattern.DOTALL);

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("EEEE d MMMM yyyy 'at' HH:mm", Locale.ENGLISH);

    private final String layout;
    private final Map<NotificationType, String> htmlBodies = new EnumMap<>(NotificationType.class);
    private final Map<NotificationType, String> textBodies = new EnumMap<>(NotificationType.class);

    /**
     * Templates are read once, at startup, and a missing one refuses to start the application.
     *
     * <p>Read lazily, the first missing template would surface as a failed send inside a poller
     * batch — long after the deploy, on a row that then retries five times and fails for good. A
     * container that will not start is the cheaper failure by a wide margin.
     */
    public EmailTemplateRenderer() {
        this.layout = read("mail/layout.html");
        for (NotificationType type : NotificationType.values()) {
            String base = "mail/" + fileNameFor(type);
            htmlBodies.put(type, read(base + ".html"));
            textBodies.put(type, read(base + ".txt"));
        }
    }

    /** {@code BOOKING_CONFIRMATION} is stored as {@code booking-confirmation.html}. */
    private static String fileNameFor(NotificationType type) {
        return type.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public RenderedEmail render(NotificationType type, MailModel model) {
        Map<String, String> values = valuesFor(model);
        String subject = subjectFor(type, model);
        values.put("subject", subject);

        String textBody = substitute(resolveBlocks(textBodies.get(type), values), values, false);
        String htmlFragment = substitute(resolveBlocks(htmlBodies.get(type), values), values, true);

        // The fragment is inserted raw — it is rendered markup, not a value — which is why every
        // value inside it was escaped a line earlier rather than here.
        Map<String, String> layoutValues = new LinkedHashMap<>(values);
        String htmlBody = substitute(resolveBlocks(layout, layoutValues), layoutValues, true)
                .replace("{{content}}", htmlFragment);

        return new RenderedEmail(subject, htmlBody, textBody);
    }

    /**
     * The subject line per type.
     *
     * <p>A switch with no {@code default}, so a fifth {@link NotificationType} fails to compile
     * rather than silently taking someone else's subject. The same closed-enum discipline the
     * frontend applies to {@code EmptyReason}.
     */
    private static String subjectFor(NotificationType type, MailModel model) {
        return switch (type) {
            case BOOKING_CONFIRMATION -> "Your appointment at %s is confirmed".formatted(model.businessName());
            case REMINDER_24H -> "Reminder: your appointment at %s is tomorrow".formatted(model.businessName());
            case CANCELLATION -> "Your appointment at %s has been cancelled".formatted(model.businessName());
            case RESCHEDULE -> "Your appointment at %s has moved".formatted(model.businessName());
        };
    }

    private static Map<String, String> valuesFor(MailModel model) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("businessName", model.businessName());
        values.put("businessPhone", blankIfNull(model.businessPhone()));
        values.put("customerName", model.customerName());
        values.put("serviceName", model.serviceName());
        values.put("employeeName", model.employeeName());
        values.put("when", format(model.startsAt(), model.timezone()));
        values.put("previousWhen", model.previousStartsAt() == null
                ? ""
                : format(model.previousStartsAt(), model.timezone()));
        values.put("confirmationCode", model.confirmationCode());
        values.put("manageUrl", model.manageUrl());
        values.put("cancellationReason", blankIfNull(model.cancellationReason()));
        return values;
    }

    private static String format(Instant instant, ZoneId zone) {
        return WHEN.format(instant.atZone(zone));
    }

    /** Keeps or drops each conditional block according to whether its value is present. */
    private static String resolveBlocks(String template, Map<String, String> values) {
        Matcher matcher = BLOCK.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            String replacement = value == null || value.isBlank() ? "" : matcher.group(2);
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * Substitutes every {@code {{key}}}.
     *
     * <p>An unknown key is left standing rather than replaced with an empty string. It is a template
     * bug either way, and a customer receiving a message with {@code {{customreName}}} visible in it
     * is how anyone finds out — an empty string would produce a sentence with a hole in it that
     * reads as merely awkward. {@code EmailTemplateRendererTest} asserts no template has one.
     */
    private static String substitute(String template, Map<String, String> values, boolean escapeHtml) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            if (!values.containsKey(key)) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            String value = values.get(key);
            matcher.appendReplacement(out, Matcher.quoteReplacement(escapeHtml ? escape(value) : value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** The five characters that matter in element content and in a quoted attribute. */
    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String blankIfNull(String value) {
        return value == null ? "" : value;
    }

    private static String read(String path) {
        try (var stream = new ClassPathResource(path).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException missing) {
            throw new UncheckedIOException("Email template " + path + " is missing from the classpath", missing);
        }
    }
}
