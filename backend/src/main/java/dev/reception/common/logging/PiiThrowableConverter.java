package dev.reception.common.logging;

import ch.qos.logback.classic.spi.IThrowableProxy;
import org.springframework.boot.logging.logback.ExtendedWhitespaceThrowableProxyConverter;

/**
 * The stack-trace half of {@link PiiMessageConverter}.
 *
 * <p>An exception message is a common carrier of exactly the values that must never be logged — a
 * failed connection string, a rejected credential — so the throwable is redacted on the same terms
 * as the message. Extends Spring Boot's converter rather than Logback's so the familiar console
 * formatting is preserved.
 */
public class PiiThrowableConverter extends ExtendedWhitespaceThrowableProxyConverter {

    @Override
    protected String throwableProxyToString(IThrowableProxy proxy) {
        return PiiValueMasker.redact(super.throwableProxyToString(proxy));
    }
}
