package dev.reception.business;

import dev.reception.common.ids.IdGenerator;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Creates a Business and its opening week.
 *
 * <p>Lives here rather than in {@code auth} so that the knowledge of what a new business's hours
 * should be sits with the hours. Registration calls it inside its own transaction, which is what
 * makes account creation atomic (docs/phases/phase-02-authentication.md).
 */
@Service
public class BusinessProvisioningService {

    /**
     * Monday to Friday, 09:00-17:00.
     *
     * <p>Defaults exist so a new account is never a blank slate the owner has to decode. The
     * weekend is deliberately absent rather than present-and-closed: a day with no row <em>is</em>
     * closed, and seeding contradictory rows would teach the opposite (docs/03-data-model.md).
     */
    static final LocalTime DEFAULT_OPENS_AT = LocalTime.of(9, 0);

    static final LocalTime DEFAULT_CLOSES_AT = LocalTime.of(17, 0);
    static final List<DayOfWeek> DEFAULT_OPEN_DAYS = List.of(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);

    /**
     * Until the owner sets their own in phase 03. UTC is the honest placeholder: it is visibly not
     * a guess about where they are, unlike the server's zone, which would be one.
     */
    static final ZoneId DEFAULT_TIMEZONE = ZoneId.of("UTC");

    static final String DEFAULT_CURRENCY = "USD";

    private final BusinessRepository businesses;
    private final BusinessHoursRepository hours;
    private final SlugService slugs;
    private final IdGenerator ids;
    private final Clock clock;

    public BusinessProvisioningService(
            BusinessRepository businesses,
            BusinessHoursRepository hours,
            SlugService slugs,
            IdGenerator ids,
            Clock clock) {
        this.businesses = businesses;
        this.hours = hours;
        this.slugs = slugs;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * Creates the business and its default week.
     *
     * <p>Deliberately not {@code @Transactional}: the caller owns the transaction, because the
     * business, the user and the membership must commit together or not at all. A boundary here
     * would let the business survive a later failure.
     */
    public Business provision(String name) {
        Business business = new Business(
                ids.newId(), name, slugs.deriveUnique(name), DEFAULT_TIMEZONE, DEFAULT_CURRENCY, clock.instant());
        businesses.save(business);
        hours.saveAll(defaultWeek(business));
        return business;
    }

    private List<BusinessHours> defaultWeek(Business business) {
        List<BusinessHours> week = new ArrayList<>(DEFAULT_OPEN_DAYS.size());
        for (DayOfWeek day : DEFAULT_OPEN_DAYS) {
            week.add(new BusinessHours(
                    ids.newId(), business.getId(), day, DEFAULT_OPENS_AT, DEFAULT_CLOSES_AT));
        }
        return week;
    }
}
