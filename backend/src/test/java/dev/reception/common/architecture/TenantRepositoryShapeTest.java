package dev.reception.common.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tenant isolation, enforced at the shape of the query surface.
 *
 * <p>The rule from docs/02-product-architecture.md §4 is that a repository for a tenant-owned
 * entity exposes only {@code findByBusinessId…}-shaped methods, so "forgetting the tenant filter"
 * is not expressible rather than merely discouraged. A convention that has to be remembered is one
 * that will eventually be forgotten, and the endpoint where it is forgotten is the one that leaks
 * another business's data.
 *
 * <p>Marked repositories are those annotated {@code @TenantScoped}. Phase 02 has one; phases 03
 * onward add many, and each arrives already governed.
 */
class TenantRepositoryShapeTest {

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("dev.reception");

    /** Prefixes that guarantee the generated query is filtered by tenant. */
    private static final List<String> TENANT_SCOPED_PREFIXES =
            List.of("findByBusinessId", "existsByBusinessId", "countByBusinessId", "deleteByBusinessId");

    /**
     * Inherited from {@code JpaRepository} and therefore not declared here; the rule governs what a
     * repository <em>adds</em>. The inherited unfiltered methods are a known gap, closed in
     * practice by every call site going through a declared method — and closed structurally in
     * phase 11's reflection-driven isolation suite, which probes the endpoints rather than the
     * repositories.
     */
    @Test
    void every_query_method_on_a_tenant_scoped_repository_names_the_tenant() {
        ArchRule rule = ArchRuleDefinition.classes()
                .that()
                .areAnnotatedWith("dev.reception.tenancy.TenantScoped")
                .should(declareOnlyTenantScopedMethods())
                .because("a repository method that does not take businessId is a cross-tenant read "
                        + "waiting to be written (docs/02-product-architecture.md §4)")
                // Phase 02 declares one such repository. The rule is stated now so that phases 03
                // onward, which add most of them, are governed from their first commit.
                .allowEmptyShould(true);

        rule.check(PRODUCTION_CLASSES);
    }

    private static ArchCondition<JavaClass> declareOnlyTenantScopedMethods() {
        return new ArchCondition<>("declare only businessId-scoped query methods") {
            @Override
            public void check(JavaClass repository, ConditionEvents events) {
                for (JavaMethod method : repository.getMethods()) {
                    String name = method.getName();
                    boolean scoped = TENANT_SCOPED_PREFIXES.stream().anyMatch(name::startsWith);
                    if (!scoped) {
                        events.add(SimpleConditionEvent.violated(
                                method,
                                "%s.%s does not begin with one of %s"
                                        .formatted(repository.getSimpleName(), name, TENANT_SCOPED_PREFIXES)));
                    }
                }
            }
        };
    }

    /**
     * A tenant-scoped repository must not re-declare {@code findById}. Inheriting it is
     * unavoidable; declaring it would be an invitation.
     */
    @Test
    void no_tenant_scoped_repository_declares_an_unscoped_find_by_id() {
        ArchRule rule = ArchRuleDefinition.noMethods()
                .that()
                .areDeclaredInClassesThat(annotatedWithTenantScoped())
                .should()
                .haveName("findById")
                .because("the tenant must be part of the lookup, not an afterthought")
                .allowEmptyShould(true);

        rule.check(PRODUCTION_CLASSES);
    }

    /**
     * The rule the whole isolation design rests on: no endpoint anywhere accepts {@code business_id}
     * from the caller (docs/04-api-overview.md §1).
     *
     * <p>Checked two ways, because there are two ways to smuggle one in. A {@code @PathVariable} or
     * {@code @RequestParam} would name it in the annotation; a request body would carry it as a
     * field on the DTO. Phase 11 adds the reflective sweep over every mapped endpoint; this is the
     * compile-time half that fails the moment someone writes one.
     */
    @Test
    void no_endpoint_binds_a_business_id_from_the_path_or_the_query() {
        ArchRule rule = ArchRuleDefinition.noMethods()
                .that()
                .areDeclaredInClassesThat()
                .areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                .should(bindAParameterNamed("business"))
                .because("business_id is derived from the Membership, the slug or the conversation "
                        + "record — never from a path, a query parameter, a header or a body")
                .allowEmptyShould(true);

        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    void no_request_body_carries_a_business_id_field() {
        ArchRule rule = ArchRuleDefinition.noFields()
                .that()
                .areDeclaredInClassesThat()
                .resideInAPackage("dev.reception..web..")
                .should()
                .haveName("businessId")
                .because("a DTO field is a value the caller chooses, and the tenant is never one")
                .allowEmptyShould(true);

        rule.check(PRODUCTION_CLASSES);
    }

    /** Matches a binding annotation whose declared name mentions the given word. */
    private static ArchCondition<JavaMethod> bindAParameterNamed(String word) {
        List<String> bindingAnnotations = List.of(
                "org.springframework.web.bind.annotation.PathVariable",
                "org.springframework.web.bind.annotation.RequestParam",
                "org.springframework.web.bind.annotation.RequestHeader");

        return new ArchCondition<>("bind a parameter named '" + word + "'") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                method.getParameters().forEach(parameter -> parameter.getAnnotations().forEach(annotation -> {
                    if (!bindingAnnotations.contains(annotation.getRawType().getName())) {
                        return;
                    }
                    String declaredName = annotation.getProperties().values().stream()
                            .map(String::valueOf)
                            .reduce("", (a, b) -> a + " " + b)
                            .toLowerCase();
                    if (declaredName.contains(word)) {
                        events.add(SimpleConditionEvent.satisfied(
                                method, method.getFullName() + " binds a caller-supplied " + word + " id"));
                    }
                }));
            }
        };
    }

    private static DescribedPredicate<JavaClass> annotatedWithTenantScoped() {
        return new DescribedPredicate<>("annotated with @TenantScoped") {
            @Override
            public boolean test(JavaClass javaClass) {
                return javaClass.isAnnotatedWith("dev.reception.tenancy.TenantScoped");
            }
        };
    }
}
