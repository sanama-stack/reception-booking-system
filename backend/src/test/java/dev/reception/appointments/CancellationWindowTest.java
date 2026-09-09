package dev.reception.appointments;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.reception.business.Business;
import dev.reception.business.BusinessService;
import dev.reception.common.error.ApiException;
import dev.reception.common.error.ErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The boundary of the Cancellation Window, to the second.
 *
 * <p>A unit test rather than an endpoint one, because the only actor the dashboard can produce is a
 * Business — which is never bound by the window. Until phase 08 opens a Customer-facing path, this
 * is the only place the refusing branch can be exercised at all, and leaving it until then would
 * mean shipping the rule untested.
 */
class CancellationWindowTest {

    private static final Instant NOW = Instant.parse("2026-09-09T12:00:00Z");
    private static final UUID BUSINESS_ID = UUID.randomUUID();

    @Test
    @DisplayName("comfortably before the window, a customer may still cancel")
    void outside_the_window_is_allowed() {
        assertThatCode(() -> windowOf(24).requireOpenFor(startingAt(Duration.ofHours(48))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("inside the window it is refused with CANCELLATION_WINDOW_CLOSED")
    void inside_the_window_is_refused() {
        assertThatThrownBy(() -> windowOf(24).requireOpenFor(startingAt(Duration.ofHours(23))))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo(ErrorCode.CANCELLATION_WINDOW_CLOSED);
    }

    @Test
    @DisplayName("exactly at the boundary the window is already shut")
    void the_boundary_itself_is_closed() {
        // A customer told they have "until 24 hours before" has until then, not through it. One
        // second either side of this line is what the two tests above pin.
        assertThatThrownBy(() -> windowOf(24).requireOpenFor(startingAt(Duration.ofHours(24))))
                .isInstanceOf(ApiException.class);

        assertThatCode(() -> windowOf(24)
                        .requireOpenFor(startingAt(Duration.ofHours(24).plusSeconds(1))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a window of zero hours closes only once the appointment has started")
    void zero_hours_means_up_to_the_appointment() {
        assertThatCode(() -> windowOf(0).requireOpenFor(startingAt(Duration.ofSeconds(1))))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> windowOf(0).requireOpenFor(startingAt(Duration.ZERO)))
                .isInstanceOf(ApiException.class);
    }

    /**
     * The Business is mocked rather than constructed and patched: {@code Business.apply} is
     * package-private in {@code dev.reception.business}, and the only setting this class reads is
     * the window. Reaching across the package boundary to set the other eighteen would be a worse
     * test, not a more realistic one.
     */
    private CancellationWindow windowOf(int hours) {
        Business business = mock(Business.class);
        when(business.cancellationWindowHours()).thenReturn(hours);

        BusinessService businesses = mock(BusinessService.class);
        when(businesses.read()).thenReturn(business);
        return new CancellationWindow(businesses, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Appointment startingAt(Duration fromNow) {
        Instant startsAt = NOW.plus(fromNow);
        return new Appointment(
                UUID.randomUUID(),
                BUSINESS_ID,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                startsAt,
                startsAt.plus(Duration.ofHours(1)),
                startsAt,
                startsAt.plus(Duration.ofHours(1)),
                new BigDecimal("60.00"),
                "GEL",
                "ABCDEFGH",
                AppointmentSource.DASHBOARD,
                null,
                Instant.EPOCH);
    }
}
