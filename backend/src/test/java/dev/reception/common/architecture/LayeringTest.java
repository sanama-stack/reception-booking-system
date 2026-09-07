package dev.reception.common.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.junit.jupiter.api.Test;

/**
 * The layering rules from docs/02-product-architecture.md §2, enforced rather than merely
 * suggested. They hold trivially in phase 01 — which is the point of adding them now: the first
 * commit that breaks one fails, instead of the tenth.
 */
class LayeringTest {

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("dev.reception");

    @Test
    void the_availability_engine_performs_no_io() {
        ArchRule rule = ArchRuleDefinition.noClasses()
                .that()
                .resideInAPackage("dev.reception.scheduling.domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("javax.sql..", "java.sql..", "jakarta.persistence..", "org.springframework..")
                .because("the availability engine is a pure function of its inputs and an injected Clock")
                // The package is empty until phase 05. The rule is declared now so the commit that
                // fills it is already governed, rather than the rule arriving after the code.
                .allowEmptyShould(true);

        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    void the_ai_layer_never_touches_persistence_directly() {
        ArchRule rule = ArchRuleDefinition.noClasses()
                .that()
                .resideInAPackage("dev.reception.ai..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("java.sql..", "javax.sql..", "org.springframework.jdbc..")
                .because("every AI tool goes through the same application services the REST controllers "
                        + "call (ADR-0004)")
                // Empty until phase 09; see the note above.
                .allowEmptyShould(true);

        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    void transactions_are_declared_on_application_services_never_on_controllers() {
        ArchRule rule = ArchRuleDefinition.noClasses()
                .that()
                .areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                .should()
                .beAnnotatedWith("org.springframework.transaction.annotation.Transactional")
                .because("transaction boundaries belong to the application layer")
                .allowEmptyShould(true);

        rule.check(PRODUCTION_CLASSES);
    }
}
