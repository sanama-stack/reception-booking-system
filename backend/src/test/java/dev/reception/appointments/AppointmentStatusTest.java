package dev.reception.appointments;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The whole state machine, enumerated.
 *
 * <p>Sixteen pairs, and every one of them is asserted — three legal and thirteen not. That is worth
 * more than three happy-path tests, because the defects this class exists to prevent are all of the
 * form "a move nobody thought about turned out to be allowed": completing a cancelled appointment,
 * or reviving a no-show once the customer complains.
 *
 * <p>Pure. No Spring, no database, no clock.
 */
class AppointmentStatusTest {

    private static final Set<AppointmentStatus> FROM_CONFIRMED = EnumSet.of(
            AppointmentStatus.COMPLETED, AppointmentStatus.NO_SHOW, AppointmentStatus.CANCELLED);

    @ParameterizedTest
    @EnumSource(AppointmentStatus.class)
    @DisplayName("from CONFIRMED every move is legal except staying put")
    void the_three_outcomes_are_reachable(AppointmentStatus target) {
        assertThat(AppointmentStatus.CONFIRMED.canMoveTo(target)).isEqualTo(FROM_CONFIRMED.contains(target));
    }

    @ParameterizedTest
    @EnumSource(
            value = AppointmentStatus.class,
            names = {"COMPLETED", "NO_SHOW", "CANCELLED"})
    @DisplayName("a terminal status is terminal — nothing moves out of it, itself included")
    void terminal_states_are_terminal(AppointmentStatus terminal) {
        for (AppointmentStatus target : AppointmentStatus.values()) {
            assertThat(terminal.canMoveTo(target))
                    .as("%s -> %s", terminal, target)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("there is no way back to CONFIRMED, because the time may already be gone")
    void nothing_returns_to_confirmed() {
        for (AppointmentStatus from : AppointmentStatus.values()) {
            assertThat(from.canMoveTo(AppointmentStatus.CONFIRMED)).isFalse();
        }
    }

    @ParameterizedTest
    @EnumSource(AppointmentStatus.class)
    @DisplayName("only CONFIRMED holds the employee's time, which is the constraint's own predicate")
    void only_confirmed_holds_time(AppointmentStatus status) {
        assertThat(status.holdsTime()).isEqualTo(status == AppointmentStatus.CONFIRMED);
    }
}
