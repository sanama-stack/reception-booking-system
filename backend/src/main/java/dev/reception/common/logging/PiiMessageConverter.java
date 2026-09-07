package dev.reception.common.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Applies {@link PiiValueMasker} to a pattern layout's {@code %m}.
 *
 * <p>The JSON encoder redacts through its own masking decorator, so without this the
 * human-readable console used during development would be the one place secrets could reach a log
 * (docs/06-security.md §10). Registered against {@code m}, {@code msg} and {@code message} in
 * logback-spring.xml so that any pattern, including one written later, is covered.
 */
public class PiiMessageConverter extends MessageConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return PiiValueMasker.redact(super.convert(event));
    }
}
