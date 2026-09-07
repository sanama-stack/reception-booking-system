package dev.reception.common.ids;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * RFC 9562 version 7 UUIDs — 48 bits of Unix milliseconds followed by 74 bits of randomness.
 *
 * <p>Ids are generated in the application rather than by the database (docs/03-data-model.md §1).
 * Version 7 is time-ordered, so B-tree locality is close to a sequence's, unlike version 4. It is
 * chosen over {@code bigserial} because these ids appear in public URLs, and a sequence there
 * leaks tenant volume to anyone who books twice.
 *
 * <p>This class is a pure function of its arguments. {@link IdGenerator} is the bean that supplies
 * them from the injected {@code Clock}, which is why nothing here reads the ambient clock.
 *
 * <pre>
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                        unix_ts_ms (48)                        |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |          unix_ts_ms           |  ver  |       rand_a (12)     |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |var|                     rand_b (62)                           |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * </pre>
 */
public final class UuidV7 {

    /** The 48-bit timestamp field overflows on 10889-08-02. Rejected rather than silently wrapped. */
    private static final long MAX_TIMESTAMP_MILLIS = (1L << 48) - 1;

    private UuidV7() {}

    /**
     * Builds a version 7 UUID for {@code timestamp}, drawing 74 bits from {@code random}.
     *
     * @throws IllegalArgumentException if the timestamp is before the Unix epoch or beyond the
     *     48-bit field
     */
    public static UUID from(Instant timestamp, RandomGenerator random) {
        long millis = timestamp.toEpochMilli();
        if (millis < 0 || millis > MAX_TIMESTAMP_MILLIS) {
            throw new IllegalArgumentException("Timestamp is outside the 48-bit UUIDv7 range: " + timestamp);
        }

        byte[] randomBytes = new byte[10];
        random.nextBytes(randomBytes);
        ByteBuffer buffer = ByteBuffer.wrap(randomBytes);

        // High half: 48 bits of milliseconds, then version 7 in the next nibble, then 12 random bits.
        long mostSignificant = (millis << 16) | 0x7000L | (buffer.getShort() & 0x0FFFL);

        // Low half: variant 0b10 in the top two bits, then 62 random bits.
        long leastSignificant = buffer.getLong();
        leastSignificant = (leastSignificant & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;

        return new UUID(mostSignificant, leastSignificant);
    }

    /** The Unix millisecond timestamp encoded in a version 7 UUID. Used by the tests and nothing else. */
    public static long timestampMillis(UUID uuid) {
        return uuid.getMostSignificantBits() >>> 16;
    }
}
