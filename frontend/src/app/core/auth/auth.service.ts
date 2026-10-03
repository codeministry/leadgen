import {HttpStatusCode} from '@angular/common/http';
import {inject, Injectable, signal} from '@angular/core';
import {Dispatcher, Events} from '@ngrx/signals/events';
import {AuthConfig as OidcConfig, OAuthErrorEvent, OAuthService} from 'angular-oauth2-oidc';
import {filter, firstValueFrom} from 'rxjs';
import {UnsavedWork} from '@core/unsaved/unsaved-work';
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
 * sign-in brings the browser back to the URL it was reloaded on. The one thing this class puts
 * in `sessionStorage` itself is `SIGN_IN_MARKER`, a time and not a credential.
 *
 * <p>**The access token renews itself** with the refresh-token grant, at 75 % of its lifetime:
 * `useSilentRefresh: false` plus the code flow makes the library's automatic refresh POST the
 * refresh token to the token endpoint — no iframe, no silent-refresh page, no reload. The
 * refresh token is the ordinary one bound to the SSO session; `offline_access` is not asked
 * for, because a token that outlives the logout is the opposite of what a browser tab needs.
 * **A renewal that fails for a while is not an ended session**: a network failure or an
 * identity provider answering 5xx is retried with `RENEWAL_BACKOFF_MS`, and offline the retry
 * waits for the `online` event. Only a 400 or 401 from the token endpoint — Keycloak's
 * `invalid_grant` — says the refresh token is dead. A token that lapsed anyway (a laptop that
 * slept through the renewal) is renewed before the next request goes out: `bearer()`.
 *
 * <p>**When the session is over** — a renewal refused, a request that carried the bearer and came
 * back 401, or a sign-in that failed on the way back — the app says so once
 * (`authEvents.sessionExpired`, which the toast store maps to a line) and, after
 * `SESSION_EXPIRED_NOTICE_MS`, starts the sign-in with the current URL as the place to come back
 * to. Two exceptions. Unsaved work on the page (`UnsavedWork`) holds the redirect until the person
 * asks for it from the toast. And a sign-in for an expired session less than
 * `SIGN_IN_LOOP_WINDOW_MS` ago means the server refuses a fresh token too, so another redirect
 * would only bounce: `authEvents.sessionRefused` says so and the page stays. Under `none` none of
 * it runs.
 */
@Injectable({providedIn: 'root'})
export class AuthService {
    private readonly oauth = inject(OAuthService);
    private readonly api = inject(AuthConfigApi);
    private readonly dispatcher = inject(Dispatcher);
    private readonly unsaved = inject(UnsavedWork);

    private readonly enabled = signal(false);

    /** Set by the first sign of an ended session; the page is left after it, so it never resets. */
    private expiring = false;
    /** The redirect waits for the person's "Sign in now", because unsaved work was on the page. */
    private held = false;
    /** The renewal in flight, shared by every request that arrives while it runs. */
    private renewal: Promise<boolean> | null = null;

    /** True once the mode is known and, under `oidc`, somebody is actually signed in. */
    readonly authenticated = signal(false);

    /** The signed-in subject's display name, or null under `none`. */
    readonly name = signal<string | null>(null);

    /** Who is signed in and whether an avatar may be loaded, for the header to read. */
    private readonly signedIn = inject(SignedInState);

    constructor() {
        // A root singleton for the page's lifetime: nothing to unsubscribe from.
        inject(Events)
            .on(authEvents.signInRequested)
            .subscribe(() => {
                if (this.held) {
                    this.held = false;
                    this.signIn();
                }
            });
    }

    /**
     * Runs once, before the first route, and **never rejects**.
     *
     * <p>An initializer that rejects stops Angular bootstrapping, so every failure in here
     * is a blank page rather than a screen with an error on it. An API that is down ends as
     * "not signed in" under an unknown mode, and the screens report their own failures. Under
     * `oidc`, a sign-in that did not produce a token — an unreachable issuer, a failed code
     * exchange, a state that does not match — is an ended session, reported as one, so the
     * operator is never left with a page whose every request silently comes back 401.
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
            .pipe(filter((event) => event.type === 'token_refresh_error'))
            .subscribe((event) => this.renewalFailed((event as OAuthErrorEvent).reason));
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
            this.authenticated.set(false);
        }
        if (!this.oauth.hasValidAccessToken()) {
            this.sessionExpired();
        }
    }

    /** Whether this instance signs in at all; false under `none` and until the mode is known. */
    isOidc(): boolean {
        return this.enabled();
    }

    /** The bearer to send right now, or null when there is no valid one in hand. Synchronous. */
    token(): string | null {
        if (!this.enabled()) {
            return null;
        }
        return this.oauth.hasValidAccessToken() ? this.oauth.getAccessToken() : null;
    }

    /**
     * The bearer to send under `oidc`, renewed first when it lapsed; one renewal for every caller
     * that arrives while it runs. Rejects with `SessionEnded` — after reporting the ended session —
     * when no token can be had. Callers ask `isOidc()` first; under `none` there is nothing to send.
     */
    async bearer(): Promise<string> {
        const valid = this.token();
        if (valid !== null) {
            return valid;
        }
        if (await this.renew()) {
            const renewed = this.token();
            if (renewed !== null) {
                return renewed;
            }
        }
        this.sessionExpired();
        throw new SessionEnded();
    }

    /**
     * **The one rule for "this answer ended the session"**: a 401 to a request that carried the
     * bearer. Used by `bearerInterceptor` and by the chat stream, which bypasses it. A 403 is a
     * signed-in person without the right; a 401 without a bearer is the API's business.
     */
    refused(status: number, sentBearer: boolean): void {
        if (sentBearer && status === HttpStatusCode.Unauthorized) {
            this.sessionExpired();
        }
    }

    /**
     * The session is over: say so once, then sign in again and come back here.
     *
     * <p>A burst — four screens refreshing at once, a renewal failing while their requests come
     * back — is one session ending, so everything after the first call is dropped. The notice is
     * what makes the toast readable before the page leaves; unsaved work turns it into a wait for
     * the person, and a sign-in moments ago turns it into `sessionRefused` and no redirect at all.
     */
    sessionExpired(): void {
        if (!this.enabled() || this.expiring) {
            return;
        }
        this.expiring = true;
        this.oauth.stopAutomaticRefresh();
        if (signedInMomentsAgo()) {
            this.dispatcher.dispatch(authEvents.sessionRefused());
            return;
        }
        const unsaved = this.unsaved.any();
        this.dispatcher.dispatch(authEvents.sessionExpired({unsaved}));
        if (unsaved) {
            this.held = true;
        } else {
            setTimeout(() => this.signIn(), SESSION_EXPIRED_NOTICE_MS);
        }
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

    /** Leaves for the identity provider, and leaves the marker the loop brake reads on the way back. */
    private signIn(): void {
        markSignIn();
        this.oauth.initCodeFlow(currentPath());
    }

    /** One renewal at a time; true once a fresh access token is in hand. */
    private renew(): Promise<boolean> {
        if (this.expiring || !this.oauth.getRefreshToken()) {
            return Promise.resolve(false);
        }
        this.renewal ??= this.renewUntilSettled(false).finally(() => (this.renewal = null));
        return this.renewal;
    }

    /**
     * The library's automatic renewal failed. A dead refresh token ends the session here and now;
     * anything else is retried. While a renewal of ours runs, its own failures arrive here too
     * (the library announces every one) and are already that loop's to handle.
     */
    private renewalFailed(reason: unknown): void {
        if (this.renewal !== null) {
            return;
        }
        if (endsSession(reason)) {
            this.sessionExpired();
            return;
        }
        this.renewal = this.renewUntilSettled(true).finally(() => (this.renewal = null));
        void this.renewal.then((renewed) => {
            if (!renewed) this.sessionExpired();
        });
    }

    /** Tries the refresh-token grant until it succeeds or the token endpoint says the session is over. */
    private async renewUntilSettled(justFailed: boolean): Promise<boolean> {
        for (let attempt = 0; ; attempt++) {
            if (justFailed || attempt > 0) {
                await pause(attempt);
            }
            if (this.expiring) {
                return false;
            }
            try {
                await this.oauth.refreshToken();
                return true;
            } catch (failure) {
                if (endsSession(failure)) {
                    return false;
                }
            }
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

/** What `bearer()` rejects with when no token can be had; the ended session is already reported. */
export class SessionEnded extends Error {
    constructor() {
        super('The session has ended');
        this.name = 'SessionEnded';
    }
}

/**
 * Whether a failed renewal means the refresh token is dead: the token endpoint answered 400 or
 * 401 (`invalid_grant`, an unknown client), or the answer was no HTTP response at all — a token
 * the library could not validate, which no retry fixes. A network failure (status 0), a timeout,
 * a throttle or a 5xx is the identity provider having a bad minute, not the session ending.
 */
function endsSession(reason: unknown): boolean {
    const status = (reason as {status?: unknown} | null)?.status;
    if (typeof status !== 'number') {
        return true;
    }
    return status === HttpStatusCode.BadRequest || status === HttpStatusCode.Unauthorized;
}

/**
 * The waits between renewal attempts, the last one repeated: quick at first, because a dropped
 * packet is the common case, then no faster than every half minute. A mark, unmeasured.
 */
export const RENEWAL_BACKOFF_MS: readonly number[] = [1_000, 2_000, 4_000, 8_000, 15_000, 30_000];

/** Waits before the next attempt: for the network to come back, or for the backoff. */
function pause(attempt: number): Promise<void> {
    if (!navigator.onLine) {
        return new Promise((resolve) => window.addEventListener('online', () => resolve(), {once: true}));
    }
    const delay = RENEWAL_BACKOFF_MS[Math.min(attempt, RENEWAL_BACKOFF_MS.length - 1)];
    return new Promise((resolve) => setTimeout(resolve, delay));
}

/**
 * The `sessionStorage` key of the loop brake: when this tab last left for the identity provider
 * because a session ended. A time in milliseconds, never a token — it has to survive the redirect,
 * which memory does not, and it carries nothing an XSS could use.
 */
export const SIGN_IN_MARKER = 'leadgen.auth.signInForExpiredSessionAt';

/**
 * How recent that sign-in has to be for the next expiry to count as a loop: a round trip through
 * an SSO session takes seconds, an access token lives minutes. A mark, unmeasured.
 */
export const SIGN_IN_LOOP_WINDOW_MS = 60_000;

function markSignIn(): void {
    try {
        window.sessionStorage.setItem(SIGN_IN_MARKER, String(Date.now()));
    } catch {
        // Storage refused (a private window, a quota): the brake is off, the sign-in still works.
    }
}

function signedInMomentsAgo(): boolean {
    let marked: number;
    try {
        marked = Number(window.sessionStorage.getItem(SIGN_IN_MARKER));
    } catch {
        return false;
    }
    return marked > 0 && Date.now() - marked < SIGN_IN_LOOP_WINDOW_MS;
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
