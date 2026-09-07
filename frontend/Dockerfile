# ---- dependencies -----------------------------------------------------------
FROM node:22-alpine AS deps
WORKDIR /app
RUN corepack enable
# pnpm-workspace.yaml carries the allowBuilds settings; without it the native
# packages' install scripts are refused and the install fails.
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile

# ---- build ------------------------------------------------------------------
FROM node:22-alpine AS build
WORKDIR /app
RUN corepack enable
COPY --from=deps /app/node_modules ./node_modules
COPY . .
ENV NEXT_TELEMETRY_DISABLED=1
RUN pnpm build

# ---- run --------------------------------------------------------------------
FROM node:22-alpine AS runtime
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 PORT=3000 HOSTNAME=0.0.0.0

RUN addgroup -S reception && adduser -S -G reception reception

# `output: 'standalone'` emits a self-contained server plus only the modules it needs.
COPY --from=build --chown=reception:reception /app/.next/standalone ./
COPY --from=build --chown=reception:reception /app/.next/static ./.next/static
COPY --from=build --chown=reception:reception /app/public ./public

USER reception
EXPOSE 3000
CMD ["node", "server.js"]
