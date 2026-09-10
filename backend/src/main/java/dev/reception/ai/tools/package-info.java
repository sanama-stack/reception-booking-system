/**
 * The eight things the Receptionist can do, and the two objects that decide whether it may.
 *
 * <p><strong>A tool contains no business rule.</strong> Each one publishes a strict JSON Schema,
 * translates the model's arguments into an application service's parameters, and translates the
 * answer back into JSON. Every rule it appears to enforce is enforced somewhere else and would be
 * enforced there whether or not this package existed — availability by the engine, conflicts by an
 * exclusion constraint, prices by the Service row, the Cancellation Window by
 * {@code CancellationWindow}. A rule implemented here would be a second implementation of one that
 * already exists, and the two would eventually disagree in front of a customer.
 *
 * <p>Three properties hold across everything here, and together they are ADR-0004.
 *
 * <p><strong>No schema names a tenant.</strong> Not {@code business_id}, not a slug, not at any
 * depth. Cross-tenant access is therefore not blocked but inexpressible — there is nowhere to put
 * one. {@code ToolSchemaTest} walks every published schema to its leaves and fails the build if a
 * ninth tool ever introduces one.
 *
 * <p><strong>Authority is server-held.</strong> {@code AuthorizedAppointments} is appended to by
 * exactly two tools and by session creation from a Manage Link, and the two write tools test
 * membership <em>before</em> reaching an application service. A hallucinated appointment id is
 * refused by a set lookup, so no row is read and nothing about whether it exists comes back.
 *
 * <p><strong>Failures are results.</strong> {@code ToolRegistry} converts a domain refusal into JSON
 * the model can read and explain, because a slot someone else took is an ordinary event a customer
 * should hear as a sentence. Eight classes do not each get to decide what {@code SLOT_UNAVAILABLE}
 * reads like.
 */
package dev.reception.ai.tools;
