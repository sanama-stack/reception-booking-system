'use client';

import { useEffect, useState } from 'react';

/**
 * Warns a developer who has opened the app on this dev server's own port instead of through Caddy.
 *
 * Bypassing Caddy puts the frontend and the backend on two different origins, so `/api/*` is not
 * routed at all and every request 404s inside React — which surfaces as a wall of stack trace that
 * says nothing about the actual cause. From phase 02 it is worse than an inconvenience: cookie
 * authentication cannot work across two origins, so even a reachable API would not keep you signed
 * in (ADR-0001, docs/06-security.md §2).
 *
 * Development only, and verified so. `process.env.NODE_ENV` is replaced at build time, so both
 * guards below fold to `false` and the minifier drops everything behind them: a production build
 * contains none of this copy and none of this logic. What survives is the husk —
 * `function(){useState(null); useEffect(()=>{},[]); return null}` — because a client component's
 * module reference is registered in the client manifest whether or not the server ever renders it.
 * Seventy bytes and no strings, which is the honest description rather than "it is removed".
 *
 * The ports come from the repository's `.env` by way of `next.config.ts`, so this cannot start
 * naming a port Caddy has stopped using.
 */
export function DevOriginGuard() {
  const [wrongOrigin, setWrongOrigin] = useState<string | null>(null);

  useEffect(() => {
    if (process.env.NODE_ENV !== 'development') return;

    const frontendPort = process.env.NEXT_PUBLIC_FRONTEND_PORT;
    const appPort = process.env.NEXT_PUBLIC_APP_PORT;
    if (!frontendPort || !appPort || appPort === frontendPort) return;

    // Compare the port, not the whole origin: reaching Caddy over a LAN address or a hostname
    // alias is fine, and comparing origins would flag it.
    if (window.location.port !== frontendPort) return;

    const { protocol, hostname, pathname, search, hash } = window.location;
    setWrongOrigin(`${protocol}//${hostname}:${appPort}${pathname}${search}${hash}`);
  }, []);

  if (process.env.NODE_ENV !== 'development' || !wrongOrigin) return null;

  return (
    <div
      role="alert"
      className="border-danger/40 bg-danger/10 fixed inset-x-0 top-0 z-[100] border-b px-4 py-3 text-sm shadow-sm backdrop-blur"
    >
      <p className="text-ink mx-auto max-w-3xl">
        <strong className="font-semibold">
          You are on port {process.env.NEXT_PUBLIC_FRONTEND_PORT}
        </strong>
        , which bypasses Caddy. Nothing under <code className="font-mono text-xs">/api</code> is
        routed here, so every request will fail and sign-in cannot work.{' '}
        <a href={wrongOrigin} className="text-brand font-medium underline">
          Open this page on port {process.env.NEXT_PUBLIC_APP_PORT}
        </a>
        .
      </p>
    </div>
  );
}
