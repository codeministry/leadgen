import {EnvironmentProviders, makeEnvironmentProviders} from '@angular/core';
import {OAuthStorage, provideOAuthClient} from 'angular-oauth2-oidc';

/**
 * The two keys that must outlive the page: the code flow writes them before the browser leaves
 * for the identity provider and reads them when it comes back to a fresh page with fresh memory.
 */
const ROUND_TRIP_KEYS: ReadonlySet<string> = new Set(['PKCE_verifier', 'nonce']);

/**
 * Where `angular-oauth2-oidc` keeps what it receives: **tokens in memory only.**
 *
 * <p>`provideOAuthClient()` defaults to `sessionStorage`, so until this class existed every
 * token — the refresh token included — sat in storage that an XSS can read, and the comment on
 * `AuthService` claiming otherwise was wrong. A pure `MemoryStorage` is not the fix either: the
 * PKCE verifier and the nonce have to survive the redirect to the identity provider, and a
 * sign-in that loses them fails every time. So those two, and only those, go to this tab's
 * `sessionStorage`; neither is a credential without the code, and the library removes the
 * verifier once the code is exchanged. Everything else lives and dies with the page, which is
 * why a reload runs the code flow again.
 */
export class TokenStorage implements OAuthStorage {
    private readonly memory = new Map<string, string>();

    getItem(key: string): string | null {
        return ROUND_TRIP_KEYS.has(key) ? window.sessionStorage.getItem(key) : (this.memory.get(key) ?? null);
    }

    setItem(key: string, data: string): void {
        if (ROUND_TRIP_KEYS.has(key)) {
            window.sessionStorage.setItem(key, data);
        } else {
            this.memory.set(key, data);
        }
    }

    removeItem(key: string): void {
        if (ROUND_TRIP_KEYS.has(key)) {
            window.sessionStorage.removeItem(key);
        } else {
            this.memory.delete(key);
        }
    }
}

/** The OIDC client, with the storage above in place of the library's `sessionStorage` default. */
export function provideOidcClient(): EnvironmentProviders {
    return makeEnvironmentProviders([provideOAuthClient(), {provide: OAuthStorage, useFactory: () => new TokenStorage()}]);
}
