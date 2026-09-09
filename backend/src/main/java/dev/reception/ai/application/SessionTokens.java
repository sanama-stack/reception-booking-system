package dev.reception.ai.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * The token that lets a browser continue a conversation, and the hash that is all the server keeps.
 *
 * <p>Not a JWT, and deliberately not. A Manage Link is signed because it has to survive a round trip
 * through an email and be verifiable with no row to look up (ADR-0001's reasoning, applied
 * narrowly). A chat session has a row — one is created before the token is issued — so a random
 * value indexed against it is simpler, revocable by deleting the row, and carries no claims that
 * could be read by anybody who intercepts it.
 *
 * <p>256 bits from a {@link SecureRandom}. The token is the whole of the authority to continue a
 * conversation that may hold authorisation over an appointment, so guessing one must be out of the
 * question rather than merely unlikely.
 */
@Component
public class SessionTokens {

    private final SecureRandom random = new SecureRandom();

    /** A fresh token. Returned to the browser once and never recoverable from the database. */
    public String issue() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * The 64-character hex SHA-256 the row stores.
     *
     * <p>Unsalted, and that is correct here rather than a shortcut: a salt defends against
     * precomputation over a small input space, and this input is 256 random bits. There is no
     * dictionary of those.
     */
    public String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM. If it is absent the process is not one we can serve
            // from, and pretending otherwise would mean sessions with no authority check at all.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
