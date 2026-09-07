package dev.reception.common.logging;

import com.fasterxml.jackson.core.JsonStreamContext;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.logstash.logback.mask.ValueMasker;

/**
 * Appender-level redaction (docs/06-security.md §10).
 *
 * <p>Applied at the appender so correctness does not depend on every call site remembering.
 * Never reaches a log file: passwords in any form, access and refresh tokens, Manage Link tokens,
 * Confirmation Codes, OpenAI API keys, customer email addresses and phone numbers. Customers appear
 * in logs as {@code customer_id} and nothing else.
 *
 * <p>This is a backstop, not a licence — call sites still must not hand secrets to the logger. It
 * lives in Java rather than in the Logback XML because a regex there is silently mangled by the
 * configurator's escape handling, and because a security control deserves a unit test.
 */
public class PiiValueMasker implements ValueMasker {

    private record Rule(Pattern pattern, String replacement) {}

    /** Key names whose value is a secret. Matched as a suffix, so {@code refresh_token} counts. */
    private static final String SECRET_KEY =
            "[A-Za-z0-9_-]*(?:password|passwd|secret|token|api[_-]?key|confirmation[_-]?code)";

    /** Where a value stops: whitespace or a JSON/query delimiter. */
    private static final String VALUE = "[^\\s,;\"'}\\]]+";

    private static final List<Rule> RULES = List.of(
            // An Authorization header carries a scheme and then the credential, so everything to
            // the end of the value goes — redacting only the first token would leave the secret.
            new Rule(
                    Pattern.compile("(?i)\\bauthorization\\b([\"']?\\s*[:=]\\s*[\"']?)[^,;\"'}\\]\\n]+"),
                    "Authorization$1[REDACTED]"),
            // A secret carried in a key=value or "key": "value" pair. The key survives so the log
            // still says what was redacted.
            new Rule(
                    Pattern.compile("(?i)\\b(" + SECRET_KEY + ")\\b([\"']?\\s*[:=]\\s*[\"']?)" + VALUE),
                    "$1$2[REDACTED]"),
            // JWT-shaped values, wherever they appear.
            new Rule(
                    Pattern.compile("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"),
                    "[REDACTED_JWT]"),
            // OpenAI-shaped API keys.
            new Rule(Pattern.compile("\\bsk-[A-Za-z0-9_-]{12,}"), "[REDACTED_API_KEY]"),
            // Customer PII.
            new Rule(
                    Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
                    "[REDACTED_EMAIL]"),
            new Rule(Pattern.compile("\\+[0-9]{7,15}\\b"), "[REDACTED_PHONE]"));

    @Override
    public Object mask(JsonStreamContext context, Object value) {
        if (!(value instanceof String text) || text.isEmpty()) {
            return null;
        }
        String masked = redact(text);
        // Returning null means "unchanged", which lets the encoder skip a copy.
        return masked.equals(text) ? null : masked;
    }

    /** Exposed for the test that proves each rule fires. */
    public static String redact(String text) {
        String result = text;
        for (Rule rule : RULES) {
            Matcher matcher = rule.pattern().matcher(result);
            if (matcher.find()) {
                result = matcher.reset().replaceAll(rule.replacement());
            }
        }
        return result;
    }
}
