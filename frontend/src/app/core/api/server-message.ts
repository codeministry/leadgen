/**
 * The actionable half of a 4xx: the sentence the server wrote.
 *
 * <p>Several endpoints answer a refusal with plain text — "only .md documents are
 * accepted", "offer 41 was rejected by the filter" — because a bare status code is a
 * support request. Shown as it stands rather than translated into a generic message of
 * our own, which would lose the one part that says what to do about it.
 *
 * It passes through the `transloco` pipe like a key would, and a key that does not exist
 * in the catalog renders as itself, which is the sentence. The fallback is a catalog key
 * for the case where the server said nothing useful, such as a network failure.
 */
export function serverMessage(error: { error?: unknown }, fallback: string): string {
  return typeof error.error === 'string' && error.error.length > 0 ? error.error : fallback;
}
