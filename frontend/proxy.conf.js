/**
 * The proxy target is an environment variable, never a literal. Without it the
 * local backend applies, so `bun run start` alone is a full working environment
 * once `docker compose up postgres api` runs.
 *
 * The effective target is printed on startup — the first place to look for
 * unexplained 401s or empty lists.
 */
const target = process.env['API_PROXY_TARGET'] ?? 'http://localhost:8080';

console.log(`[proxy] /api, /mcp → ${target}`);

// `/mcp` and the protected-resource metadata go to the api too, as nginx.conf sends them: without
// them the dev server answers both with index.html and a 200, which an MCP client cannot parse.
// `/mcp` exactly, with or without a query (a key starting with ^ is a pattern here, matched against
// the path and query), so `/mcpanything` stays the SPA's as it does behind nginx.
//
// `xfwd` on every entry: `changeOrigin` turns the Host into the target's, and the api reads the
// browser's host and port from X-Forwarded-Host and -Port, both to name its own URLs and, under
// AUTH_MODE=none, to refuse a page on a rebound name (RebindingGuard).
//
// `configure` overwrites all three with what this server knows: `xfwd` alone keeps a Host the
// client set and appends to a client's port and scheme. A page on a rebound name could then hand
// the api `localhost` as the only name it sees, the Host being the target's by then, and an
// appended port no longer parses. nginx.conf overwrites them the same way.
const api = {
    target,
    secure: false,
    changeOrigin: true,
    xfwd: true,
    configure: (proxy) => {
        proxy.on('proxyReq', (proxyReq, req) => {
            proxyReq.setHeader('x-forwarded-host', req.headers.host ?? '');
            // The port the browser used, from its Host as nginx.conf's map reads it, not the socket's:
            // a published port or a tunnel in front of this server differs. A Host without one means
            // the scheme's default.
            const tls = Boolean(req.socket.encrypted);
            const port = /:(\d+)$/.exec(req.headers.host ?? '')?.[1] ?? (tls ? '443' : '80');
            proxyReq.setHeader('x-forwarded-port', port);
            proxyReq.setHeader('x-forwarded-proto', tls ? 'https' : 'http');
        });
    },
};
module.exports = {
    '/api': api,
    '^/mcp(\\?|$)': api,
    '/.well-known/oauth-protected-resource': api,
};
