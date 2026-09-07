/**
 * The IANA and ISO-4217 lists, read from the runtime rather than shipped here.
 *
 * The server validates against `ZoneId.getAvailableZoneIds()` and `Currency.getInstance`, both of
 * which read the JDK's own registries. A hand-maintained copy in the frontend would be a second
 * source of truth that goes stale, and would offer values the server then rejects. These functions
 * *offer*; the server decides.
 */

/**
 * Every zone this runtime knows, or an empty list where it cannot say.
 *
 * `Intl.supportedValuesOf` is widely available but not universal, and a browser without it must
 * still be able to set a timezone — so an empty list leaves the caller with a plain text field
 * rather than with a list this file guessed at.
 */
export function availableTimezones(): string[] {
  try {
    return Intl.supportedValuesOf('timeZone');
  } catch {
    return [];
  }
}

/** ISO-4217 codes, on the same terms as the zones. */
export function availableCurrencies(): string[] {
  try {
    return Intl.supportedValuesOf('currency');
  } catch {
    return [];
  }
}

/**
 * `America/New_York` → `America / New York`.
 *
 * Purely presentational; the value submitted is always the zone id itself.
 */
export function timezoneLabel(zone: string): string {
  return zone.replaceAll('_', ' ').replaceAll('/', ' / ');
}
