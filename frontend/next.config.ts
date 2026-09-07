import { readFileSync } from 'node:fs';
import path from 'path';
import type { NextConfig } from 'next';

/**
 * Reads a port out of the repository's `.env`.
 *
 * The backend imports the same file rather than depending on whatever launched it
 * (docs/sessions/2026-09-07-phase-01-foundation.md §5.5); this is the frontend doing the same, for
 * the same reason. `.env` stays the single place a port is written down, so the development-origin
 * guard below cannot drift from the ports Caddy is actually using.
 *
 * Absent in CI and inside the container, where these arrive as real environment variables — hence
 * the fallbacks, which match `.env.example`.
 */
function portFromEnvFile(name: string, fallback: string): string {
  if (process.env[name]) return process.env[name];

  for (const candidate of ['../.env', './.env']) {
    try {
      const match = readFileSync(path.join(__dirname, candidate), 'utf8')
        .split('\n')
        .find((line) => line.startsWith(`${name}=`));
      if (match) return match.slice(name.length + 1).trim();
    } catch {
      // Not there. Try the next one, then fall back.
    }
  }
  return fallback;
}

const nextConfig: NextConfig = {
  env: {
    // Caddy — the only origin a browser should use.
    NEXT_PUBLIC_APP_PORT: portFromEnvFile('APP_PORT', '9080'),
    // This dev server's own port. Reaching the app here means bypassing Caddy, which breaks
    // every API call and, from phase 02, cookie authentication with it.
    NEXT_PUBLIC_FRONTEND_PORT: portFromEnvFile('FRONTEND_PORT', '9082'),
  },
  // Produces .next/standalone, which the runtime Docker stage copies on its own.
  output: 'standalone',
  // Pins tracing to this package so standalone output is correct regardless of any
  // lockfile further up the tree.
  outputFileTracingRoot: path.join(__dirname),
  reactStrictMode: true,
  poweredByHeader: false,
  typescript: { ignoreBuildErrors: false },
  eslint: { ignoreDuringBuilds: false },
};

export default nextConfig;
