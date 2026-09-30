/**
 * The avatar URL gravatar.com serves for an address, or null when there is nothing to ask for.
 *
 * <p>SHA-256 of the trimmed, lower-cased address, which is the hash Gravatar documents now; the
 * address itself never leaves the browser. `d=404` asks for no placeholder at all, so an address
 * without a Gravatar image is a failed load and the menu falls back to the initials, rather
 * than to a generated face nobody chose.
 *
 * <p>`crypto.subtle` exists only in a secure context. localhost is one and a deployment behind
 * TLS is one; anywhere else this answers null and the initials stand in.
 */
export async function gravatarUrl(email: string | null | undefined, size = 80): Promise<string | null> {
    const address = email?.trim().toLowerCase();
    if (!address || typeof crypto === 'undefined' || !crypto.subtle) {
        return null;
    }
    const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(address));
    const hex = Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('');
    return `https://gravatar.com/avatar/${hex}?s=${size}&d=404`;
}

/** Up to two letters for the avatar's place: first and last word of the name, else its first letter. */
export function initials(name: string | null | undefined): string {
    // An address stands in for a name when the token carries none: its local part is the name.
    const words = (name ?? '').trim().split('@')[0].split(/[\s._-]+/).filter(Boolean);
    if (words.length === 0) {
        return '?';
    }
    const first = words[0].charAt(0);
    const last = words.length > 1 ? words[words.length - 1].charAt(0) : '';
    return (first + last).toUpperCase();
}
