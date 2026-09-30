# syntax=docker/dockerfile:1
# The Angular CLI checks `process.version`, and bun 1.3.12's `node` shim reports v24.3.0,
# below the CLI's minimum. So this stage runs on the Node the CLI wants and carries the bun
# that `packageManager` pins, both by exact tag. When an Angular bump raises the CLI's Node
# floor (`engines.node` in node_modules/@angular/cli/package.json), the Node tag here moves
# with it; CI builds on the runner's Node and will not notice. `BunPinTest` holds the bun
# tag equal to `packageManager` and the two workflows.
# `$BUILDPLATFORM`: the build output is static files, the same for every architecture, so the
# stage runs once on the runner's own platform. Built per target, the arm64 pass ran under
# QEMU, and esbuild (Go) died there with SIGILL after minutes. Only the nginx stage is per arch.
# bun comes from a stage of its own on the same platform: `COPY --from=<image>` resolves the
# image for the *target*, so the arm64 pass copied an arm64 bun into this amd64 stage and
# `bun install` died with exit 255.
FROM --platform=$BUILDPLATFORM oven/bun:1.3.12-alpine AS bun

FROM --platform=$BUILDPLATFORM node:22.23.3-alpine AS build
COPY --from=bun /usr/local/bin/bun /usr/local/bin/bun
WORKDIR /app
COPY frontend/package.json frontend/bun.lock ./
RUN bun install --frozen-lockfile
COPY frontend/ ./
RUN bun run build

FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/frontend/browser /usr/share/nginx/html
# 8080, not 80: nginx runs as uid 101 here, and a non-root process cannot bind a port
# below 1024. The deployment chart outside this repository moves its container port along.
EXPOSE 8080
