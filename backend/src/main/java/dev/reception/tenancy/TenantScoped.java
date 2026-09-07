package dev.reception.tenancy;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a repository whose entity is tenant-owned.
 *
 * <p>The annotation is not read at runtime. It exists so {@code TenantRepositoryShapeTest} can
 * enforce the rule from docs/02-product-architecture.md §4: every query method on such a
 * repository takes {@code businessId} first, and {@code findById} is not declared. Forgetting the
 * tenant filter is then not expressible rather than merely discouraged.
 *
 * <p>Marking the repository rather than the entity is deliberate — the rule is about the shape of
 * the query surface, and that is what a reader needs to see when they open the file they are about
 * to add a method to.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface TenantScoped {}
