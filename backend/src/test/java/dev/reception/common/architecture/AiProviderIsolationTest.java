package dev.reception.common.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The provider stops at one package.
 *
 * <p>docs/05-ai-architecture.md §10 says {@code OpenAiChatModel} is "the <strong>only</strong> class
 * permitted to import the OpenAI SDK". Phase 09 chose a hand-written {@code RestClient} call over an
 * SDK (ADR-0009), which does not weaken the rule — it changes what leaking looks like. A leak is now
 * a second class that knows the wire format: builds the request body, reads {@code choices}, or
 * holds the API key.
 *
 * <p>These rules are what keeps the port honest. The whole orchestration loop is tested against a
 * scripted double precisely because nothing above {@code dev.reception.ai.openai} can tell which
 * implementation it has — and that stays true only for as long as nothing above it reaches past the
 * port.
 */
class AiProviderIsolationTest {

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("dev.reception");

    private static final String ADAPTER_PACKAGE = "dev.reception.ai.openai..";

    /**
     * The adapter is reachable as a {@code ChatModel} bean and in no other way.
     *
     * <p>Spring injects it by interface, so nothing needs to name the class. Something that did
     * would be something that had a reason to care which provider is behind the port, and there is
     * no such legitimate reason above this line.
     */
    @Test
    @DisplayName("nothing outside the adapter package depends on the adapter")
    void the_adapter_has_no_callers() {
        ArchRule rule = ArchRuleDefinition.noClasses()
                .that()
                .resideOutsideOfPackage(ADAPTER_PACKAGE)
                .should()
                .dependOnClassesThat()
                .resideInAPackage(ADAPTER_PACKAGE)
                .because("everything above the port speaks ChatMessage, ToolSpec and ChatResponse; a "
                        + "class that names the adapter is a class that could not be tested against "
                        + "the scripted double (docs/05-ai-architecture.md §10)");

        rule.check(PRODUCTION_CLASSES);
    }

    /**
     * No HTTP client anywhere else in the AI layer.
     *
     * <p>The rule that would actually have been broken by a hurried ninth tool calling a second
     * endpoint directly — an embeddings call, a moderation check — rather than by anybody importing
     * an SDK on purpose.
     */
    @Test
    @DisplayName("no class in the AI layer outside the adapter speaks HTTP")
    void only_the_adapter_makes_http_calls() {
        ArchRule rule = ArchRuleDefinition.noClasses()
                .that()
                .resideInAPackage("dev.reception.ai..")
                .and()
                .resideOutsideOfPackage(ADAPTER_PACKAGE)
                .should()
                .dependOnClassesThat()
                .haveNameMatching("org\\.springframework\\.web\\.client\\..*|java\\.net\\.http\\..*")
                .because("a tool reaches the world through an application service, never through a "
                        + "socket of its own (ADR-0004)");

        rule.check(PRODUCTION_CLASSES);
    }

    /**
     * The key is read in one place.
     *
     * <p>{@code AiProperties} holds it and the adapter reads it. A third class reading
     * {@code app.ai.api-key} would be a third place it could be logged.
     */
    @Test
    @DisplayName("only the adapter reads the API key")
    void the_api_key_is_read_in_one_place() {
        ArchRule rule = ArchRuleDefinition.noClasses()
                .that()
                .resideOutsideOfPackage(ADAPTER_PACKAGE)
                .and()
                .haveSimpleNameNotEndingWith("AiProperties")
                .should()
                .callMethod("dev.reception.ai.application.AiProperties", "getApiKey")
                .because("a credential read in two places is a credential logged in one of them "
                        + "(docs/06-security.md §10)");

        rule.check(PRODUCTION_CLASSES);
    }
}
