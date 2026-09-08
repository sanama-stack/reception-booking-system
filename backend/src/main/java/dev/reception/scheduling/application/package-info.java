/**
 * AvailabilityService (phase 05), and BookingService and RescheduleService (phase 06).
 *
 * <p>Everything that loads what the engine needs lives here, so that everything which decides
 * whether a Slot is bookable can live in {@code scheduling.domain} with no way to reach a database.
 */
package dev.reception.scheduling.application;
