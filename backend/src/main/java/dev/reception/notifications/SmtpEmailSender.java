package dev.reception.notifications;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * The SMTP adapter behind {@link EmailSender}. Mailpit locally, a real relay in production — the
 * difference is entirely in {@code spring.mail.*}, and neither this class nor anything above it
 * knows which one it is talking to.
 *
 * <p>Sends {@code multipart/alternative} with both bodies. A client that renders HTML shows the
 * HTML; a text client, a screen reader and several spam filters read the plain part. Sending HTML
 * alone is what makes a transactional message look like a marketing one.
 *
 * <p>Every transport failure becomes an {@link EmailDeliveryException} carrying the original
 * message, because the poller records that text in {@code last_error} and "Connection refused" is
 * the difference between a misconfigured relay and a rejected recipient.
 */
@Component
public class SmtpEmailSender implements EmailSender {

    /** Deliberately loose. Over-matching a diagnostic string costs nothing; under-matching leaks. */
    private static final Pattern EMAIL_IN_TEXT =
            Pattern.compile("<?[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}>?");

    private final JavaMailSender mail;
    private final String from;

    public SmtpEmailSender(JavaMailSender mail, @Value("${app.mail.from}") String from) {
        this.mail = mail;
        this.from = from;
    }

    @Override
    public void send(String recipient, RenderedEmail message) {
        try {
            MimeMessage mime = mail.createMimeMessage();
            // true: multipart. The two-argument setText below is what fills both alternatives.
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(recipient);
            helper.setSubject(message.subject());
            // Plain text first, HTML second — the order the MIME spec uses to say which alternative
            // is preferred, and getting it backwards makes some clients show the plain part.
            helper.setText(message.text(), message.html());
            mail.send(mime);
        } catch (MessagingException | MailException failed) {
            throw new EmailDeliveryException(describe(failed), failed);
        }
    }

    /**
     * The transport's own words, with any address in them removed.
     *
     * <p>This string is stored in {@code last_error} and reaches the log, and an SMTP rejection
     * routinely quotes the recipient back — {@code 550 5.1.1 <someone@example.com> User unknown} is
     * the ordinary shape of one. Left alone it would copy a customer's address into a log with a far
     * longer retention than the row it came from (docs/06-security.md §10), so it is redacted here
     * rather than at each place the text is later read.
     *
     * <p>The diagnosis survives redaction: {@code 550 5.1.1 <redacted> User unknown} still says the
     * address does not exist, which is the fact an operator needs.
     */
    private static String describe(Exception failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        return failure.getClass().getSimpleName() + ": " + EMAIL_IN_TEXT.matcher(message).replaceAll("<redacted>");
    }
}
