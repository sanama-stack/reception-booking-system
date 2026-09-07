package dev.reception.business;

import dev.reception.common.ids.IdGenerator;
import java.time.Clock;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Creates a Business and its opening week.
 *
 * <p>Lives here rather than in {@code auth} so that the knowledge of what a new business's hours
 * should be sits with the hours. Registration calls it inside its own transaction, which is what
 * makes account creation atomic (docs/phases/phase-02-authentication.md).
 *
 * <p>The values themselves are in {@link BusinessDefaults}, so "what does a new account start with"
 * has one answer rather than one per caller.
 */
@Service
public class BusinessProvisioningService {

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
                ids.newId(),
                name,
                slugs.deriveUnique(name),
                BusinessDefaults.TIMEZONE,
                BusinessDefaults.CURRENCY,
                clock.instant());
        businesses.save(business);
        hours.saveAll(defaultWeek(business));
        return business;
    }

    private List<BusinessHours> defaultWeek(Business business) {
        List<BusinessHours> week = new ArrayList<>(BusinessDefaults.OPEN_DAYS.size());
        for (DayOfWeek day : BusinessDefaults.OPEN_DAYS) {
            week.add(new BusinessHours(
                    ids.newId(),
                    business.getId(),
                    day,
                    BusinessDefaults.OPENS_AT,
                    BusinessDefaults.CLOSES_AT));
        }
        return week;
    }
}
