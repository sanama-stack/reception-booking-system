package dev.reception.common.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

/**
 * {@code Instant.now()} is banned outside the {@code Clock} bean, and the ban is enforced here
 * rather than remembered (docs/09-phase-plan.md §5).
 *
 * <p>The whole {@code now()} family is covered, not just {@code Instant}: reading the ambient
 * clock through {@code LocalDate.now()} is the same defect wearing a different type, and it is the
 * one that would silently break the availability engine's DST tests.
 */
class NoAmbientClockTest {

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("dev.reception");

    @Test
    void no_class_reads_the_ambient_clock() {
        ArchRule rule = ArchRuleDefinition.noClasses()
                .that()
                .doNotHaveFullyQualifiedName("dev.reception.common.time.ClockConfig")
                .should()
                .callMethod(Instant.class, "now")
                .orShould()
                .callMethod(LocalDate.class, "now")
                .orShould()
                .callMethod(LocalTime.class, "now")
                .orShould()
                .callMethod(LocalDateTime.class, "now")
                .orShould()
                .callMethod(ZonedDateTime.class, "now")
                .orShould()
                .callMethod(OffsetDateTime.class, "now")
                .orShould()
                .callMethod(System.class, "currentTimeMillis")
                .because("the current instant comes from the injected Clock bean, so tests can fix it "
                        + "(docs/08-testing-strategy.md §4)");

        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    void only_the_clock_configuration_constructs_a_system_clock() {
        ArchRule rule = ArchRuleDefinition.noClasses()
                .that()
                .doNotHaveFullyQualifiedName("dev.reception.common.time.ClockConfig")
                .should()
                .callMethod(Clock.class, "systemUTC")
                .orShould()
                .callMethod(Clock.class, "systemDefaultZone")
                .because("there is exactly one Clock in the application and it is a bean");

        rule.check(PRODUCTION_CLASSES);
    }
}
