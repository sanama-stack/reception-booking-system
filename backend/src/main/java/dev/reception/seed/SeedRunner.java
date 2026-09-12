package dev.reception.seed;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * {@code make seed} — the two demo tenants, written into the local database.
 *
 * <p><strong>Three guards, and they are not redundant.</strong> The bean is
 * {@code @Profile("local")}, so outside that profile it does not exist. It is
 * {@code @ConditionalOnProperty}, so a local application started normally does not reseed itself on
 * every restart — the property is set by {@code make seed} and nothing else. And {@link #run}
 * checks the environment again before it deletes anything, because the first two guards are
 * annotations, and an annotation is exactly the kind of thing a later refactor moves, copies or
 * drops without noticing that it was the only thing standing between a demo fixture and a real
 * database.
 *
 * <p><strong>It prints to {@code System.out} rather than logging.</strong> The credentials are the
 * point of the last four lines, and the log appenders mask an email address and redact anything
 * next to the word "password" in every profile, deliberately (docs/06-security.md §10). A seed that
 * announced its demo login as {@code [REDACTED_EMAIL]} would be working exactly as designed and
 * still be useless.
 */
@Component
@Profile("local")
@ConditionalOnProperty(name = "reception.seed.enabled", havingValue = "true")
class SeedRunner implements ApplicationRunner {

    private final Environment environment;
    private final SeedReset reset;
    private final TenantSeeder seeder;

    SeedRunner(Environment environment, SeedReset reset, TenantSeeder seeder) {
        this.environment = environment;
        this.reset = reset;
        this.seeder = seeder;
    }

    @Override
    public void run(ApplicationArguments args) {
        requireLocal();

        List<Blueprint.Tenant> tenants = DemoTenants.all();
        // Before the reset, not after it: a blueprint that cannot be written is not a reason to
        // have already deleted what was there.
        BlueprintCheck.verify(tenants);

        int cleared = reset.clear(
                tenants.stream().map(tenant -> tenant.profile().slug()).toList(),
                tenants.stream().map(tenant -> tenant.owner().email()).toList());

        List<TenantSeeder.Seeded> seeded = new ArrayList<>(tenants.size());
        for (Blueprint.Tenant tenant : tenants) {
            seeded.add(seeder.seed(tenant));
        }

        List<UUID> businessIds = seeded.stream().map(TenantSeeder.Seeded::businessId).toList();
        int outbox = reset.clearOutbox(businessIds);

        report(tenants, seeded, cleared, outbox);
    }

    /**
     * The guard that is code rather than an annotation.
     *
     * <p>{@code spring.profiles.default} is {@code local}, so an application started with nothing
     * set is local and this passes. Anything that has explicitly asked to be something else fails
     * here, before the reset deletes a single row.
     */
    private void requireLocal() {
        if (!environment.matchesProfiles("local") || environment.matchesProfiles("prod")) {
            throw new IllegalStateException(
                    "The demo seed only runs under the local profile. Active profiles: %s"
                            .formatted(String.join(", ", environment.getActiveProfiles())));
        }
    }

    private void report(
            List<Blueprint.Tenant> tenants, List<TenantSeeder.Seeded> seeded, int cleared, int outbox) {
        StringBuilder out = new StringBuilder("\n");
        out.append("Seeded ").append(seeded.size()).append(" demo businesses");
        if (cleared > 0) {
            out.append(", after removing ").append(cleared).append(" from an earlier run");
        }
        out.append(".\n");
        if (outbox > 0) {
            out.append("Cleared ")
                    .append(outbox)
                    .append(" notification(s) the seed's own cancellations queued, so Mailpit starts empty.\n");
        }
        out.append('\n');

        for (int i = 0; i < seeded.size(); i++) {
            Blueprint.Tenant tenant = tenants.get(i);
            TenantSeeder.Seeded result = seeded.get(i);
            out.append(result.profile().name())
                    .append("  —  ")
                    .append(result.profile().timezone().getId())
                    .append(", ")
                    .append(result.profile().currency())
                    .append('\n');
            out.append("  booking page   http://localhost:9080/book/")
                    .append(result.profile().slug())
                    .append('\n');
            out.append("  sign in as     ")
                    .append(tenant.owner().email())
                    .append("   password: ")
                    .append(tenant.owner().password())
                    .append('\n');
            out.append("  contents       ")
                    .append(result.services().size())
                    .append(" services, ")
                    .append(result.employees().size())
                    .append(" employees, ")
                    .append(tenant.customers().size())
                    .append(" customers, ")
                    .append(result.appointments().size())
                    .append(" appointments\n\n");
        }

        out.append("Sign in at http://localhost:9080/login — both accounts use the same password.\n");
        System.out.println(out);
    }
}
