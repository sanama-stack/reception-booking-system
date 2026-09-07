/**
 * `45` → `45 min`, `90` → `1 hr 30 min`.
 *
 * Not in `lib/time`, deliberately: everything there formats an *instant* in the business timezone,
 * and a length of time has no instant and no zone. Putting it there would suggest it did.
 */
export function formatDuration(minutes: number): string {
  if (minutes < 60) return `${minutes} min`;
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return rest === 0 ? `${hours} hr` : `${hours} hr ${rest} min`;
}
