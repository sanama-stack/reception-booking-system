package dev.reception.common.time;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The single source of the current instant in the application.
 *
 * <p>{@code Instant.now()} and its siblings are banned everywhere else and the ban is enforced by
 * {@code NoAmbientClockTest} rather than remembered. Every component that needs to know the time
 * injects this bean; tests inject a fixed one, which is what makes the availability engine
 * exhaustively testable (docs/08-testing-strategy.md §4).
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
