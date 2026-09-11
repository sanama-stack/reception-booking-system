package dev.reception.seed;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removes a previous run's demo tenants, so {@code make seed} is repeatable.
 *
 * <p>The alternative — refusing to run when the database already holds a Salon Aria — makes the
 * command useless the second time it is needed, which is every time after the first. A demo dataset
 * is a fixture, and a fixture you cannot rebuild is a fixture you end up working around.
 *
 * <p><strong>Scoped to the two demo tenants by name, never "delete everything".</strong> The
 * database this runs against is a developer's, and it will have their own business in it. Two
 * businesses are identified — by slug, and by the memberships of the two demo accounts, because a
 * run that failed between registration and the profile patch left a business under the slug
 * registration derived rather than the one the blueprint asked for.
 *
 * <p><strong>SQL rather than repositories, and the order is the schema's.</strong> Deleting a
 * Business cascades to everything that carries its {@code business_id} — customers, appointments,
 * events, notifications, services, employees, schedules, time off, hours, closures, FAQs and AI
 * conversations — because every one of those foreign keys is {@code ON DELETE CASCADE}
 * (docs/03-data-model.md). The three that are not are {@code memberships}, {@code refresh_tokens}
 * and the demo users themselves, so those are deleted here by hand, before and after. If a future
 * migration adds a table that references a Business without cascading, this fails loudly on the
 * foreign key rather than leaving an orphan behind.
 */
@Component
class SeedReset {

    private final JdbcTemplate jdbc;

    SeedReset(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return how many Businesses were removed */
    @Transactional
    int clear(List<String> slugs, List<String> ownerEmails) {
        List<UUID> businessIds = businessesToClear(slugs, ownerEmails);

        jdbc.update(
                "delete from refresh_tokens where user_id in (select id from users where email in (%s))"
                        .formatted(placeholders(ownerEmails)),
                ownerEmails.toArray());

        if (!businessIds.isEmpty()) {
            Object[] ids = businessIds.toArray();
            jdbc.update(
                    "delete from memberships where business_id in (%s)".formatted(placeholders(businessIds)), ids);
            jdbc.update("delete from businesses where id in (%s)".formatted(placeholders(businessIds)), ids);
        }

        jdbc.update(
                "delete from users where email in (%s)".formatted(placeholders(ownerEmails)), ownerEmails.toArray());

        return businessIds.size();
    }

    /**
     * Empties the outbox for the seeded tenants.
     *
     * <p>The seed writes no mail of its own, but cancelling through the real service enqueues a real
     * cancellation email — and a demo that begins with a Mailpit already holding messages nobody
     * sent teaches the wrong thing about the outbox. After this, the first message in Mailpit is
     * one the demo caused.
     */
    @Transactional
    int clearOutbox(Collection<UUID> businessIds) {
        if (businessIds.isEmpty()) {
            return 0;
        }
        return jdbc.update(
                "delete from notifications where business_id in (%s)".formatted(placeholders(businessIds)),
                businessIds.toArray());
    }

    private List<UUID> businessesToClear(List<String> slugs, List<String> ownerEmails) {
        List<UUID> found = new ArrayList<>(
                jdbc.queryForList(
                        "select id from businesses where slug in (%s)".formatted(placeholders(slugs)),
                        UUID.class,
                        slugs.toArray()));

        List<UUID> throughMembership = jdbc.queryForList(
                """
                select m.business_id from memberships m
                  join users u on u.id = m.user_id
                 where u.email in (%s)
                """
                        .formatted(placeholders(ownerEmails)),
                UUID.class,
                ownerEmails.toArray());

        for (UUID id : throughMembership) {
            if (!found.contains(id)) {
                found.add(id);
            }
        }
        return found;
    }

    private static String placeholders(Collection<?> values) {
        return values.stream().map(value -> "?").collect(Collectors.joining(", "));
    }
}
