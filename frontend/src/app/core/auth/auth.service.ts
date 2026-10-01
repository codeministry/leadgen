import {DestroyRef, inject, Injectable, signal} from '@angular/core';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {Dispatcher} from '@ngrx/signals/events';
import {AuthConfig as OidcConfig, OAuthService} from 'angular-oauth2-oidc';
import {filter, firstValueFrom} from 'rxjs';
import {AuthConfig, AuthConfigApi} from './auth-config.api';
import {authEvents} from './auth.events';
import {SignedInState} from './signed-in.state';

/**
 * Whether anybody has to log in, and the token if they did.
 *
 * <p>**Nothing here is decided at build time.** The browser asks the API which mode it is
 * in, because this repository wires nothing into an artifact and the frontend has no
 * build-time configuration at all. The consequence is that the same bundle serves a
 * single-operator instance on localhost with no login and a reachable one behind Keycloak.
 *
 * <p>**Under `none` this does nothing whatsoever** — no discovery request, no redirect, no
 * interceptor header. That is the shipped default and the path most runs take, so it must
 * not depend on an identity provider being up.
 *
 * <p>**The tokens live in memory only.** `angular-oauth2-oidc` would keep them in
 * `sessionStorage` by default; a token in storage survives an XSS long enough to be read,
 * and nothing here needs it to survive a reload. `TokenStorage` (`oidc-client.ts`) is what
 * enforces this — the library default was in place until spec 024 noticed. A reload runs the
 * code flow again, which the identity provider's SSO cookie answers without a form, and the
 * sign-in brings the browser back to the URL it was reloaded on.
 *
 * <p>**The access token renews itself** with the refresh-token grant, at 75 % of its lifetime:
 * `useSilentRefresh: false` plus the code flow makes the library's automatic refresh POST the
 * refresh token to the token endpoint — no iframe, no silent-refresh page, no reload. The
 * refresh token is the ordinary one bound to the SSO session; `offline_access` is not asked
 * for, because a token that outlives the logout is the opposite of what a browser tab needs.
 *
 * <p>**When the session is over** — a renewal the identity provider refused, or a request that
 * carried the bearer and came back 401 — the app says so once (`authEvents.sessionExpired`,
 * which the toast store maps to a line) and, after `SESSION_EXPIRED_NOTICE_MS`, starts the
 * sign-in with the current URL as the place to come back to. Under `none` none of it runs.
 */
@Injectable({providedIn: 'root'})
export class AuthService {
    private readonly oauth = inject(OAuthService);
    private readonly api = inject(AuthConfigApi);

    private readonly enabled = signal(false);
    private readonly dispatcher = inject(Dispatcher);
    private readonly destroyRef = inject(DestroyRef);

    /** Set by the first sign of an ended session; the page is left shortly after, so it never resets. */
    private expiring = false;

    /** True once the mode is known and, under `oidc`, somebody is actually signed in. */
    readonly authenticated = signal(false);

    /** The signed-in subject's display name, or null under `none`. */
    readonly name = signal<string | null>(null);

    /** Who is signed in and whether an avatar may be loaded, for the header to read. */
    private readonly signedIn = inject(SignedInState);

    /**
     * Runs once, before the first route, and **never rejects**.
     *
     * <p>An initializer that rejects stops Angular bootstrapping, so every failure in here
     * is a blank page rather than a screen with an error on it. Two of them are reachable
     * and neither is this function's business to have an opinion about: an API that is
     * down, and an identity provider that is. Both end as "not signed in", the interceptor
     * sends no header, and the screens report their own failures the way they already do.
     */
    async initialise(): Promise<void> {
        let config: AuthConfig;
        try {
            config = await firstValueFrom(this.api.load());
        } catch {
            // The API being unreachable is a thing the screens show. It is not a reason
            // for the application to not exist.
            return;
        }
        if (config.mode !== 'oidc' || !config.issuer || !config.clientId) {
            return;
        }
        this.enabled.set(true);
        this.oauth.configure(this.oidcConfig(config.issuer, config.clientId));
        this.oauth.events
            .pipe(
                filter((event) => event.type === 'token_refresh_error'),
                takeUntilDestroyed(this.destroyRef),
            )
            .subscribe(() => this.sessionExpired());
        try {
            // The URL this load started on travels through the identity provider as the
            // additional state; when it comes back with a code, `state` holds it again.
            const signedIn = await this.oauth.loadDiscoveryDocumentAndLogin({state: currentPath()});
            if (!signedIn) {
                // The browser is on its way to the identity provider; nothing here outlives that.
                return;
            }
            this.returnToStartingPoint();
            this.oauth.setupAutomaticSilentRefresh();
            this.authenticated.set(this.oauth.hasValidAccessToken());
            const user = signedInUser(this.oauth.getIdentityClaims() as IdentityClaims | null, config.issuer);
            this.signedIn.user.set(this.authenticated() ? user : null);
            this.name.set(user?.name ?? null);
            this.signedIn.gravatar.set(config.gravatar !== false);
        } catch {
            // Deliberately swallowed and surfaced as "not signed in": an unreachable
            // issuer is not a reason to leave the operator looking at nothing.
            this.authenticated.set(false);
        }
    }

    /** The bearer to send, or null when there is nothing to send. */
    token(): string | null {
        if (!this.enabled()) {
            return null;
        }
        return this.oauth.hasValidAccessToken() ? this.oauth.getAccessToken() : null;
    }

    /**
     * The session is over: say so once, then sign in again and come back here.
     *
     * <p>Called by the library's `token_refresh_error` and by `bearerInterceptor` for a 401 on a
     * request that carried the bearer. A burst — four screens refreshing at once, a renewal failing
     * while their requests come back — is one session ending, so everything after the first call
     * is dropped. The notice is what makes the toast readable before the page leaves.
     */
    sessionExpired(): void {
        if (!this.enabled() || this.expiring) {
            return;
        }
        this.expiring = true;
        this.oauth.stopAutomaticRefresh();
        this.dispatcher.dispatch(authEvents.sessionExpired());
        setTimeout(() => this.oauth.initCodeFlow(currentPath()), SESSION_EXPIRED_NOTICE_MS);
    }

    /**
     * Ends the Keycloak session too, not only this tab's tokens: RP-initiated logout at the
     * issuer's `end_session_endpoint`, with the ID token as the hint and back to this origin.
     * A local-only logout would be undone by the next load, which signs in silently again.
     */
    logout(): void {
        if (this.enabled()) {
            this.oauth.logOut();
        }
    }

    /**
     * After a sign-in, put the browser back on the URL it left from, before the router's first
     * navigation reads it. `returnPath` refuses anything but a same-origin path.
     */
    private returnToStartingPoint(): void {
        const path = returnPath(this.oauth.state);
        if (path !== null && path !== currentPath()) {
            window.history.replaceState(window.history.state, '', path);
        }
    }

    private oidcConfig(issuer: string, clientId: string): OidcConfig {
        return {
            issuer,
            clientId,
            redirectUri: window.location.origin + '/',
            postLogoutRedirectUri: window.location.origin + '/',
            responseType: 'code',
            scope: 'openid profile email',
            // PKCE is not optional for a public client: there is no secret to prove the
            // code came back to whoever asked for it.
            useSilentRefresh: false,
            // The renewal fires at 75 % of the access token's lifetime — the library default,
            // spelled out because ISC-502 names it and a default can move under an upgrade.
            timeoutFactor: 0.75,
            showDebugInformation: false,
            // `remoteOnly` and not `false`: the dev server is plain HTTP on localhost and
            // has to work, but anywhere else a code flow over HTTP puts the token on the
            // wire. `false` would have allowed exactly that, silently, on the one
            // deployment where it matters.
            requireHttps: 'remoteOnly',
        };
    }
}

/**
 * How long the session-expired toast stands before the sign-in takes the page away: long enough
 * to read one sentence, short enough not to look stuck. A mark, unmeasured.
 */
export const SESSION_EXPIRED_NOTICE_MS = 2_500;

/** The URL as the router sees it — path, query and fragment — on this path-located app. */
function currentPath(): string {
    return window.location.pathname + window.location.search + window.location.hash;
}

/**
 * The path to come back to after a sign-in, or null when `state` is not one.
 *
 * <p>The library hands the additional state back still URL-encoded once. **Only a same-origin
 * path passes**: it starts with one `/`, and neither `//host` nor `/\\host` (which browsers
 * read as protocol-relative) nor any scheme. The state comes back through a URL anybody can
 * craft, so without this the sign-in would be an open redirect.
 */
export function returnPath(state: string | null | undefined): string | null {
    if (!state) {
        return null;
    }
    let path: string;
    try {
        path = decodeURIComponent(state);
    } catch {
        return null;
    }
    if (!path.startsWith('/') || path.startsWith('//') || path.startsWith('/\\')) {
        return null;
    }
    return new URL(path, window.location.origin).origin === window.location.origin ? path : null;
}

/** The ID token claims the user menu reads; every one of them optional, as the realm decides. */
interface IdentityClaims {
    readonly name?: string;
    readonly given_name?: string;
    readonly family_name?: string;
    readonly preferred_username?: string;
    readonly email?: string;
    readonly email_verified?: boolean;
    readonly exp?: number;
}

/**
 * Who is signed in, as the user menu shows it.
 *
 * <p>`name` falls back to the username and then to the address, so the menu always has a line
 * to lead with; `expiresAt` is the ID token's own expiry, which is when the next load signs in
 * again, not a session a logout would shorten.
 */
export interface SignedInUser {
    readonly name: string | null;
    readonly username: string | null;
    readonly email: string | null;
    readonly emailVerified: boolean | null;
    readonly issuer: string;
    readonly expiresAt: Date | null;
}

export function signedInUser(claims: IdentityClaims | null, issuer: string): SignedInUser | null {
    if (!claims) {
        return null;
    }
    const joined = [claims.given_name, claims.family_name].filter((part) => !!part?.trim()).join(' ');
    const name = claims.name?.trim() || joined || claims.preferred_username || claims.email || null;
    return {
        name,
        username: claims.preferred_username ?? null,
        email: claims.email ?? null,
        emailVerified: claims.email_verified ?? null,
        issuer,
        expiresAt: typeof claims.exp === 'number' ? new Date(claims.exp * 1000) : null,
    };
}
