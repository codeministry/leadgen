/**
 * The proxy target is an environment variable, never a literal. Without it the
 * local backend applies, so `bun run start` alone is a full working environment
 * once `docker compose up postgres api` runs.
 *
 * The effective target is printed on startup — the first place to look for
 * unexplained 401s or empty lists.
 */
const target = process.env['API_PROXY_TARGET'] ?? 'http://localhost:8080';

console.log(`[proxy] /api → ${target}`);

module.exports = {
  '/api': { target, secure: false, changeOrigin: true },
};
