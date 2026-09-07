package dev.reception.common.ids;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The id scheme, asserted rather than assumed. Version 7's time ordering is the reason it was
 * chosen over version 4 (docs/03-data-model.md §1), so it is worth a test that would notice if the
 * bit packing were wrong.
 */
class UuidV7Test {

    private static final Instant WHEN = Instant.parse("2026-09-07T12:00:00Z");

    @Test
    void carries_version_7_and_the_rfc_variant() {
        UUID id = UuidV7.from(WHEN, new Random(1));

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void encodes_the_timestamp_it_was_given() {
        UUID id = UuidV7.from(WHEN, new Random(1));

        assertThat(UuidV7.timestampMillis(id)).isEqualTo(WHEN.toEpochMilli());
    }

    /** The property the whole choice rests on: later ids sort after earlier ones. */
    @Test
    void ids_generated_over_time_sort_in_generation_order() {
        Random random = new Random(7);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            ids.add(UuidV7.from(WHEN.plusMillis(i), random).toString());
        }

        assertThat(ids).isSorted();
    }

    @Test
    void two_ids_in_the_same_millisecond_still_differ() {
        Random random = new Random(3);

        assertThat(UuidV7.from(WHEN, random)).isNotEqualTo(UuidV7.from(WHEN, random));
    }

    @Test
    void a_timestamp_outside_the_48_bit_field_is_refused_rather_than_wrapped() {
        Instant tooLate = Instant.ofEpochMilli((1L << 48));

        assertThatThrownBy(() -> UuidV7.from(tooLate, new Random(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** The generator reads the injected Clock, which is what keeps ids reproducible in tests. */
    @Test
    void the_generator_takes_its_timestamp_from_the_clock_bean() {
        IdGenerator generator = new IdGenerator(Clock.fixed(WHEN, ZoneOffset.UTC), new Random(1));

        assertThat(UuidV7.timestampMillis(generator.newId())).isEqualTo(WHEN.toEpochMilli());
    }
}
