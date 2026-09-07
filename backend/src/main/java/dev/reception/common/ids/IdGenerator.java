package dev.reception.common.ids;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Issues the primary key for every row the application writes.
 *
 * <p>A bean rather than a static helper for one reason: a version 7 UUID embeds the current
 * instant, and the current instant comes from the injected {@code Clock} — the same rule the whole
 * codebase follows, enforced by {@code NoAmbientClockTest}. A static {@code Instant.now()} here
 * would be the one place the ban leaked.
 */
@Component
public class IdGenerator {

    private final Clock clock;
    private final RandomGenerator random;

    // Two constructors, so Spring is told which one; the other lets a test fix the randomness.
    @Autowired
    public IdGenerator(Clock clock) {
        // SecureRandom because these ids are handed out in public URLs; a predictable id would let
        // one customer guess another's appointment link.
        this(clock, new SecureRandom());
    }

    IdGenerator(Clock clock, RandomGenerator random) {
        this.clock = clock;
        this.random = random;
    }

    public UUID newId() {
        return UuidV7.from(clock.instant(), random);
    }
}
