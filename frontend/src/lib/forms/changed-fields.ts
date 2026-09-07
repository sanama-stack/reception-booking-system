/**
 * The fields that actually changed, as a patch the server will read the way the owner meant.
 *
 * `null` on the loaded resource and `''` in the form are the same state — not set — so a field
 * nobody touched does not appear in the patch. Sending everything would be harmless in most cases
 * and not in several: an unchanged `slug` would still be checked for uniqueness, an unchanged
 * `timezone` would trip the confirmation dialog that exists to warn about a change, and an
 * unchanged service name would be re-checked against the case-insensitive uniqueness index.
 *
 * Clearing still works. An owner who empties a filled-in field produces `''` against a non-null
 * current value, which differs, so `''` is sent — and blank clears, the rule every `PATCH` in this
 * API follows (`Business.apply`, `Service.apply`, `Employee.apply`).
 *
 * `current` is compared key by key against what the form holds, so it can be either the loaded
 * resource itself — where the keys line up — or a values-shaped projection of it, which is what a
 * resource with a nested field needs (a Service's `price` is `{ amount, currency }` on the way in
 * and a bare decimal string on the way out).
 */
export function changedFields<T extends object>(
  current: { [K in keyof T]?: unknown },
  values: T,
): Partial<T> {
  const patch: Record<string, unknown> = {};

  for (const [key, value] of Object.entries(values)) {
    const stored = (current as Record<string, unknown>)[key];
    const unset = stored === null ? '' : stored;
    if (value !== unset) patch[key] = value;
  }

  return patch as Partial<T>;
}
