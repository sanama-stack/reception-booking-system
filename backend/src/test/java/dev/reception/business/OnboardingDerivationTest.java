package dev.reception.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.reception.business.CatalogReadiness.Snapshot;
import dev.reception.tenancy.TenantContext;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The checklist is derived, so it can be tested exhaustively without any of the state existing.
 *
 * <p>That is most of the value of the {@link CatalogReadiness} seam: services, employees and
 * schedules arrive in phase 04, but the sixteen combinations of them are enumerable today, and the
 * one that matters — everything configured except opening hours — is not reachable through the API
 * in phase 03 at all.
 */
class OnboardingDerivationTest {

    private static final UUID BUSINESS_ID = UUID.randomUUID();

    private BusinessRepository businesses;
    private BusinessHoursRepository hours;
    private CatalogReadiness catalog;
    private OnboardingService onboarding;

    @BeforeEach
    void setUp() {
        businesses = mock(BusinessRepository.class);
        hours = mock(BusinessHoursRepository.class);
        catalog = mock(CatalogReadiness.class);
        TenantContext tenant = mock(TenantContext.class);
        when(tenant.businessId()).thenReturn(BUSINESS_ID);
        when(businesses.findById(BUSINESS_ID))
                .thenReturn(Optional.of(new Business(
                        BUSINESS_ID, "Salon Aria", "salon-aria", ZoneId.of("UTC"), "USD", Instant.EPOCH)));
        onboarding = new OnboardingService(businesses, hours, catalog, tenant);
    }

    private void given(long hoursRows, Snapshot snapshot) {
        when(hours.countByBusinessId(BUSINESS_ID)).thenReturn(hoursRows);
        when(catalog.of(any())).thenReturn(snapshot);
    }

    @Test
    @DisplayName("hours are configured when at least one interval exists")
    void hours_configured_follows_the_row_count() {
        given(1, Snapshot.NOTHING_CONFIGURED);
        assertThat(onboarding.checklist().hoursConfigured()).isTrue();

        given(0, Snapshot.NOTHING_CONFIGURED);
        assertThat(onboarding.checklist().hoursConfigured()).isFalse();
    }

    @Test
    @DisplayName("a business fresh from registration has hours and nothing else")
    void a_newly_registered_business() {
        given(5, Snapshot.NOTHING_CONFIGURED);

        OnboardingService.Checklist checklist = onboarding.checklist();

        assertThat(checklist.hoursConfigured()).isTrue();
        assertThat(checklist.hasActiveService()).isFalse();
        assertThat(checklist.hasActiveEmployee()).isFalse();
        assertThat(checklist.hasEmployeeSchedule()).isFalse();
        assertThat(checklist.hasBookableService()).isFalse();
        assertThat(checklist.publicPageReady()).isFalse();
    }

    /**
     * Every combination of the five inputs that decide {@code publicPageReady}. Enumerated rather
     * than sampled, because "ready" is the flag that decides whether the dashboard tells an owner
     * they can start taking bookings, and being wrong in the optimistic direction means a customer
     * reaching a page that cannot book them.
     */
    @ParameterizedTest(name = "hours={0} service={1} employee={2} schedule={3} bookable={4} → ready={5}")
    @CsvSource({
        "false, false, false, false, false, false",
        "true,  false, false, false, false, false",
        "false, true,  true,  true,  true,  false",
        "true,  true,  false, true,  true,  false",
        "true,  true,  true,  false, true,  false",
        "true,  true,  true,  true,  false, false",
        "true,  true,  true,  true,  true,  true",
    })
    void public_page_ready_is_the_conjunction(
            boolean hoursConfigured,
            boolean hasActiveService,
            boolean hasActiveEmployee,
            boolean hasEmployeeSchedule,
            boolean hasBookableService,
            boolean expectedReady) {
        given(
                hoursConfigured ? 5 : 0,
                new Snapshot(hasActiveService, hasActiveEmployee, hasEmployeeSchedule, hasBookableService));

        assertThat(onboarding.checklist().publicPageReady()).isEqualTo(expectedReady);
    }

    @Test
    @DisplayName("the booking url follows the slug, so a slug change moves it with no second write")
    void booking_url_is_derived_from_the_slug() {
        given(5, Snapshot.NOTHING_CONFIGURED);
        assertThat(onboarding.checklist().bookingUrl()).isEqualTo("/book/salon-aria");
    }
}
