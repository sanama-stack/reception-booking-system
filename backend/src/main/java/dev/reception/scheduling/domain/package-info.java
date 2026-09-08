/**
 * The availability engine: {@code TimeRange} algebra, slot generation and {@code AvailabilityEngine}.
 *
 * <p>Pure. Nothing here may touch a repository, Spring, JDBC or the ambient clock — {@code
 * LayeringTest} fails the build if it does, and without {@code allowEmptyShould} as of phase 05.
 * That is what makes the exhaustive suite in docs/08-testing-strategy.md §4 possible: every DST
 * transition, buffer edge and horizon boundary is a function call.
 */
package dev.reception.scheduling.domain;
