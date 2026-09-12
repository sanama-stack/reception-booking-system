import { defineConfig } from 'vitest/config';

/**
 * The frontend test runner — docs/phases/phase-11-hardening-and-deployment.md, *The frontend test
 * runner*.
 *
 * Deliberately thin. Its targets are the phase-10 rows that could only be *counted* rather than
 * asserted: business-timezone rendering, and the empty/loading/error states rule 7 requires every
 * screen to ship and nothing has ever checked. It does not replace the Playwright flow, which
 * stays the check that proves the demo works.
 *
 * **No `@vitejs/plugin-react`.** Its only contributions here would be Fast Refresh, which a test
 * run has no use for, and the React Compiler's Babel pass, which this application does not use —
 * against a peer range demanding a Vite major of its own. Vite's own transform handles `.tsx`.
 *
 * **`oxc`, not `esbuild`.** Vite 8 transforms with Oxc, and an `esbuild: { jsx }` block here is
 * ignored — with a warning only if both are present, and silently if it is the only one. The
 * failure that follows names the wrong cause: *"make sure to not set jsx to preserve"*, pointing
 * at `tsconfig.json`, which says `preserve` correctly because Next does that transform in the
 * real build.
 */
export default defineConfig({
  oxc: { jsx: 'automatic' },
  resolve: {
    // The `@/*` of tsconfig.json, which is how every file in src/ imports its neighbours.
    alias: { '@': new URL('./src/', import.meta.url).pathname },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.ts', 'src/**/*.test.tsx'],
    // **The browser's zone, set to something the business is not.** ADR-0003 says every datetime
    // renders in the *business's* timezone; a suite run in UTC against a UTC fixture cannot tell a
    // correct conversion from no conversion at all. Asia/Tbilisi is +04:00 and has no DST, so the
    // counterfactual is a fixed four hours all year. The timezone tests assert this is in force
    // before they assert anything else — if it ever stops taking effect, they go red rather than
    // quietly becoming tautologies.
    env: { TZ: 'Asia/Tbilisi' },
  },
});
