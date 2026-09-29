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
module.exports = {
    '/api': {target, secure: false, changeOrigin: true},
    '/mcp': {target, secure: false, changeOrigin: true},
    '/.well-known/oauth-protected-resource': {target, secure: false, changeOrigin: true},
};
