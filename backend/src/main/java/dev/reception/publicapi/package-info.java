/**
 * Every endpoint reachable without authentication, in one directory you can read.
 *
 * <p>That is the point of the package rather than a side effect of it: "what can a stranger reach"
 * is a question worth answering by listing files instead of by grepping for annotations and hoping
 * the list is complete. The only other unauthenticated paths in the application are the four
 * {@code /auth/*} endpoints, health, and the API docs — all of them named explicitly in
 * {@code SecurityConfig}.
 *
 * <p>Three rules hold across everything here.
 *
 * <p><strong>Responses are hand-written.</strong> No entity is ever serialised;
 * {@code PublicResponses} is the whole vocabulary and {@code PublicFieldAllowListTest} fails the
 * build for a field that was not typed into it deliberately.
 *
 * <p><strong>The tenant is derived, never accepted.</strong> From the slug, by
 * {@code SlugTenantContextFilter}, or from a proven capability, by
 * {@code PublicAppointmentAuthority}. Nothing here reads a business from a body or a parameter.
 *
 * <p><strong>The application services are the dashboard's.</strong> Booking, cancelling,
 * rescheduling and availability all land in the same classes the authenticated surface calls, which
 * is what makes this package add no privileged path — and what phase 09's Tools will inherit by
 * calling those same services rather than these controllers.
 */
package dev.reception.publicapi;
