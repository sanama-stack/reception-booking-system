import path from 'path';
import type { NextConfig } from 'next';

const nextConfig: NextConfig = {
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
