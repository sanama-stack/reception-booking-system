package dev.reception.notifications;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies the Manage Link — the capability a Customer holds instead of an account.
 *
 * <p><strong>A capability token, not a session.</strong> It authorises exactly one Appointment and
 * grants nothing else: no listing, no other appointment, no Business data beyond what that one
 * booking contains. Someone who obtains a link obtains that appointment and only that appointment,
 * which is the property that makes emailing it acceptable at all (docs/06-security.md).
 *
 * <p><strong>Stateless on purpose.</strong> The appointment id and the expiry travel in the token
 * and the signature covers both, so there is no table of issued links to keep, sweep, or leak. The
 * cost is that a token cannot be revoked before it expires — which is why the lifetime is short and
 * tied to the appointment rather than to a fixed calendar window.
 *
 * <p>Encoded as {@code base64url(payload) + "." + base64url(hmac)}, both unpadded. The payload is
 * readable by anyone holding the token, and deliberately so: it contains an id the holder is being
 * granted access to and an expiry they gain nothing by reading. Signing rather than encrypting means
 * a tampered token fails verification instead of decrypting into something plausible.
 */
@Service
public class ManageTokenService {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String SEPARATOR = ".";

    /**
     * How long a link outlives the Appointment it manages.
     *
     * <p>Measured from {@code endsAt} rather than from issue, so a booking made three months ahead
     * does not carry a token that expired eleven weeks before anyone could use it. Twenty-four hours
     * afterwards, because the reasons to open the link do not stop at the appointment's end — a
     * customer checking what time it was, or what they were charged, is asking a legitimate question
     * about something that just happened.
     */
    private static final Duration GRACE_AFTER_END = Duration.ofHours(24);

    private final byte[] secret;
    private final Clock clock;

    public ManageTokenService(@Value("${app.security.manage-link-secret}") String secret, Clock clock) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    /** The token for an Appointment ending at {@code endsAt}. */
    public String issue(UUID appointmentId, Instant endsAt) {
        long expiry = endsAt.plus(GRACE_AFTER_END).getEpochSecond();
        String payload = appointmentId + "|" + expiry;
        return encode(payload.getBytes(StandardCharsets.UTF_8)) + SEPARATOR + encode(sign(payload));
    }

    /**
     * The Appointment this token authorises, or empty if it does not authorise anything.
     *
     * <p>Every failure returns the same empty result — malformed, tampered, expired, signed with a
     * different secret. A caller cannot tell them apart and neither can an attacker probing the
     * endpoint, which is the same reasoning {@code INVALID_CREDENTIALS} follows for login.
     */
    public Optional<UUID> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        int dot = token.indexOf(SEPARATOR);
        if (dot <= 0 || dot == token.length() - 1) {
            return Optional.empty();
        }

        String payload;
        byte[] presented;
        try {
            payload = new String(decode(token.substring(0, dot)), StandardCharsets.UTF_8);
            presented = decode(token.substring(dot + 1));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }

        // Constant-time, and the order matters: the signature is checked before the payload is
        // parsed or the expiry read, so nothing an unsigned token says can steer this method.
        // MessageDigest.isEqual compares every byte regardless of where the first difference is,
        // which is what stops the comparison leaking how much of a forged signature was right.
        if (!MessageDigest.isEqual(presented, sign(payload))) {
            return Optional.empty();
        }

        int bar = payload.indexOf('|');
        if (bar <= 0) {
            return Optional.empty();
        }
        try {
            if (Long.parseLong(payload.substring(bar + 1)) <= clock.instant().getEpochSecond()) {
                return Optional.empty();
            }
            return Optional.of(UUID.fromString(payload.substring(0, bar)));
        } catch (IllegalArgumentException unparseable) {
            // NumberFormatException from the expiry, or a payload whose first half is not a UUID.
            // Only reachable for a payload this service signed, so it means the format changed
            // under rows already issued rather than that somebody tampered with one.
            return Optional.empty();
        }
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException impossible) {
            // HmacSHA256 is required of every JVM, and the key is a non-empty byte array.
            throw new IllegalStateException("HMAC-SHA256 is unavailable", impossible);
        }
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }
}
