import type { BusinessPatch, BusinessProfile } from './types';

/**
 * The fields that actually changed, as a patch the server will read the way the owner meant.
 *
 * `null` on the profile and `''` in the form are the same state — not set — so a field nobody
 * touched does not appear in the patch. Sending everything would be harmless in most cases and not
 * in two: an unchanged `slug` would still be checked for uniqueness, and an unchanged `timezone`
 * would trip the confirmation dialog that exists to warn about a change.
 *
 * Clearing still works. An owner who empties a filled-in field produces `''` against a non-null
 * current value, which differs, so `''` is sent — and blank clears (`Business.apply`).
 */
export function changedFields(profile: BusinessProfile, values: BusinessPatch): BusinessPatch {
  const patch: Record<string, unknown> = {};

  for (const [key, value] of Object.entries(values)) {
    const stored = profile[key as keyof BusinessProfile];
    const current = stored === null ? '' : stored;
    if (value !== current) patch[key] = value;
  }

  return patch as BusinessPatch;
}
