import {HttpErrorResponse, HttpStatusCode} from '@angular/common/http';
import {effect, inject, Injectable, signal, untracked} from '@angular/core';
import {Dispatcher, Events} from '@ngrx/signals/events';
import {AuthConfig as OidcConfig, OAuthService} from 'angular-oauth2-oidc';
import {catchError, defer, firstValueFrom, Observable, of, switchMap, tap, throwError} from 'rxjs';
import {UnsavedWork} from '@core/unsaved/unsaved-work';
import {AuthConfig, AuthConfigApi} from './auth-config.api';
import {authEvents} from './auth.events';
import {SignedInState} from './signed-in.state';

/**
 * Where the sign-in stands. One value, so that every question — may a request go out, may the
 * page leave, has the person been told — is answered by reading it rather than by combining flags.
 *
 * ```
 * off                                   none, or the mode is unknown: nothing here runs
 * booting ─┬─▶ signedIn ◀──▶ refused    a token in hand; refused = the loop brake spoke
 *          │      │
 *          │      ├─▶ held ──▶ leaving  unsaved work holds the redirect until it clears
 *          │      └─────────▶ leaving   the notice stands, then the page leaves
 *          ├─▶ leaving                  a plain load, or a code that could not be exchanged
 *          ├─▶ failed ──────▶ leaving   a sign-in failed moments after the last; the person retries
 *          └─▶ unreachable ─▶ leaving   the issuer did not answer; the person retries
 * ```
 */
export type AuthPhase = 'off' | 'booting' | 'signedIn' | 'refused' | 'held' | 'leaving' | 'failed' | 'unreachable';

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
 * <p>**A load that has to sign in renders nothing.** `initialise()` runs as an app initializer
 * and stays pending while the browser is on its way to the identity provider, so no screen
 * renders and no request goes out that would come back 401 — a request from a page that is
 * leaving is what used to raise a false "session expired" on every reload.
 *
 * <p>**The access token renews itself** with the refresh-token grant, at `RENEW_AT` of its
 * lifetime, on a timer this class owns: the library's automatic refresh is not set up, so one
 * place and one slot (`renewal`) send every refresh-token grant, and two grants can never race
 * each other into an `invalid_grant` under refresh-token rotation. No iframe, no silent-refresh
 * page, no reload; `offline_access` is not asked for, because a token that outlives the logout
 * is the opposite of what a browser tab needs. **A renewal that fails for a while is not an
 * ended session**: a network failure or an identity provider answering 5xx is retried with
 * `RENEWAL_BACKOFF_MS`, and offline the retry waits for the `online` event. Only a 400 or 401
 * from the token endpoint — Keycloak's `invalid_grant` — says the refresh token is dead.
 *
 * <p>**A request waits for a renewal, but not for ever.** `call()` is the one way a request
 * goes out under `oidc`: a lapsed token is renewed first, a 401 to a bearer is answered with one
 * renewal and one replay (a token that expired in flight), and a renewal that takes longer than
 * `REQUEST_RENEWAL_TIMEOUT_MS` fails the request as unreachable while the renewal carries on.
 *
 * <p>**When the session is over** — a renewal refused, or a replayed request still answered 401
 * — the app says so once (`authEvents.sessionExpired`)
 * and, after `SESSION_EXPIRED_NOTICE_MS`, starts the sign-in with the current URL as the place to
 * come back to. Unsaved work on the page (`UnsavedWork`) holds the redirect until it clears or
 * the person asks for it. And a sign-in less than `SIGN_IN_LOOP_WINDOW_MS` ago whose token the
 * API has not yet accepted once means the server refuses a fresh token too, so another redirect
 * would only bounce: `authEvents.sessionRefused` says so and the page stays, still sending its
 * bearer, until a request goes through or the person signs in again. A code that comes back and
 * cannot be exchanged is no ended session, since nobody was signed in: the load goes straight back to
 * the identity provider, and inside that same window stops at `authEvents.signInFailed` instead.
 */
@Injectable({providedIn: 'root'})
export class AuthService {
    private readonly oauth = inject(OAuthService);
    private readonly api = inject(AuthConfigApi);
    private readonly dispatcher = inject(Dispatcher);
    private readonly unsaved = inject(UnsavedWork);
    /** Who is signed in and whether an avatar may be loaded, for the header to read. */
    private readonly signedIn = inject(SignedInState);

    /** The one piece of state; see `AuthPhase`. */
    readonly phase = signal<AuthPhase>('off');

    /** The renewal in flight, shared by every caller that arrives while it runs. */
    private renewal: Promise<boolean> | null = null;
    /** The timer that renews the access token at `RENEW_AT` of its lifetime. */
    private renewalTimer: ReturnType<typeof setTimeout> | undefined;
    /** Whether a bearer request has gone through on this page, which lifts the loop brake. */
    private accepted = false;
    private issuer = '';
    private gravatar = false;

    constructor() {
        // A root singleton for the page's lifetime: nothing to unsubscribe from.
        inject(Events)
            .on(authEvents.signInRequested)
            .subscribe(() => this.signInRequested());
        // A held redirect goes as soon as nothing on the page would be lost by it.
        effect(() => {
            if (this.phase() === 'held' && !this.unsaved.any()) {
                untracked(() => this.leave());
            }
        });
    }

    /**
     * Runs once, before the first route, and **never rejects**.
     *
     * <p>An initializer that rejects stops Angular bootstrapping, so every failure in here is a
     * blank page rather than a screen with an error on it. An API that is down ends as `off`, and
     * the screens report their own failures. An identity provider that is down ends as
     * `unreachable`, with a standing line that offers to try again. A load that has to sign in
     * **never resolves**: the browser leaves, and nothing should render on the way out.
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
        this.issuer = config.issuer;
        this.gravatar = config.gravatar !== false;
        this.oauth.configure(this.oidcConfig(config.issuer, config.clientId));
        this.phase.set('booting');
        if (!(await this.discovered())) {
            return;
        }
        try {
            // Exchanges the code when the browser comes back with one; does nothing on a plain load.
            await this.oauth.tryLogin();
        } catch {
            // A code that came back and could not be exchanged — a failed exchange, a state that
            // does not match, an error from the identity provider. Nobody is signed in, so the
            // server has refused nothing: straight back to the identity provider, unless that is
            // where the last attempt came from moments ago, which would only bounce.
            if (signedInMomentsAgo()) {
                this.phase.set('failed');
                this.dispatcher.dispatch(authEvents.signInFailed());
                return;
            }
            this.leaveNow();
            return new Promise<void>(() => undefined);
        }
        if (!this.oauth.hasValidAccessToken()) {
            this.leaveNow();
            return new Promise<void>(() => undefined);
        }
        this.returnToStartingPoint();
        this.phase.set('signedIn');
        this.scheduleRenewal();
        const user = signedInUser(this.oauth.getIdentityClaims() as IdentityClaims | null, this.issuer);
        this.signedIn.user.set(user);
        this.signedIn.gravatar.set(this.gravatar);
    }

    /** Whether this instance signs in at all; false under `none` and until the mode is known. */
    isOidc(): boolean {
        return this.phase() !== 'off';
    }

    /** The bearer to send right now, or null when there is no valid one in hand. Synchronous. */
    token(): string | null {
        return this.live() && this.oauth.hasValidAccessToken() ? this.oauth.getAccessToken() : null;
    }

    /**
     * **The one way a request goes out under `oidc`**, for `bearerInterceptor` and the chat stream.
     *
     * <p>`attempt` sends with the token it is handed and fails with an `HttpErrorResponse` when
     * the server refuses. A valid token is handed over synchronously; a lapsed one is renewed
     * first. A 401 is answered with one renewal and one replay, and a second 401 ends the session;
     * under the loop brake a 401 is handed on as it is. Any other answer lifts the brake.
     * When no token can be had the request is never sent: it fails as a 401 `url` never saw, or
     * as status 0 when the renewal outlasted `REQUEST_RENEWAL_TIMEOUT_MS`. A 403 is a signed-in
     * person without the right and passes through untouched.
     */
    call<T>(url: string, attempt: (token: string) => Observable<T>): Observable<T> {
        // Any answer but a 401 says the server took the bearer — a 403, a 404, a 409 included.
        const send = (token: string) =>
            attempt(token).pipe(
                tap({
                    complete: () => this.accept(),
                    error: (error: unknown) => {
                        if (answeredWithBearer(error)) this.accept();
                    },
                }),
            );
        const valid = this.token();
        const token$ = valid !== null ? of(valid) : defer(() => this.renewedToken());
        return token$.pipe(
            switchMap((token) =>
                send(token).pipe(
                    catchError((error: unknown) =>
                        // Under the loop brake a 401 is what the brake already reported: a renewal
                        // would only send the identity provider one more grant per screen and poll.
                        refusedBearer(error) && this.phase() !== 'refused'
                            ? defer(() => this.renewedToken(token)).pipe(
                                  switchMap(send),
                                  tap({
                                      error: (again: unknown) => {
                                          if (refusedBearer(again)) this.expire();
                                      },
                                  }),
                              )
                            : throwError(() => error),
                    ),
                ),
            ),
            catchError((error: unknown) => throwError(() => notSent(error, url) ?? error)),
        );
    }

    /**
     * Ends the Keycloak session too, not only this tab's tokens: RP-initiated logout at the
     * issuer's `end_session_endpoint`, with the ID token as the hint and back to this origin.
     * A local-only logout would be undone by the next load, which signs in silently again.
     */
    logout(): void {
        if (this.isOidc()) {
            this.oauth.logOut();
        }
    }

    /** Whether a token is in hand and requests may carry it. */
    private live(): boolean {
        const phase = this.phase();
        return phase === 'signedIn' || phase === 'refused';
    }

    /**
     * A token for a request that has none: renewed, within `REQUEST_RENEWAL_TIMEOUT_MS`. After a
     * refusal, `stale` is the token that was refused — when somebody else renewed meanwhile, the
     * fresh one is used without a second grant.
     */
    private async renewedToken(stale?: string): Promise<string> {
        const current = this.token();
        if (current !== null && current !== stale) {
            return current;
        }
        if (!this.live()) {
            throw new SessionEnded();
        }
        const renewed = await within(this.startRenewal(), REQUEST_RENEWAL_TIMEOUT_MS);
        if (renewed === 'late') {
            throw new RenewalTimedOut();
        }
        const token = renewed ? this.token() : null;
        if (token === null) {
            this.expire();
            throw new SessionEnded();
        }
        return token;
    }

    /** The timer's renewal, and the one place a timer-driven failure ends the session. */
    private scheduleRenewal(): void {
        clearTimeout(this.renewalTimer);
        const expiresAt = this.oauth.getAccessTokenExpiration();
        if (expiresAt === null) {
            return;
        }
        const delay = Math.max(0, (expiresAt - Date.now()) * RENEW_AT);
        this.renewalTimer = setTimeout(() => {
            void this.startRenewal().then((renewed) => {
                if (!renewed) this.expire();
            });
        }, delay);
    }

    /** One renewal at a time; true once a fresh access token is in hand. */
    private startRenewal(): Promise<boolean> {
        this.renewal ??= this.renewUntilSettled().finally(() => (this.renewal = null));
        return this.renewal;
    }

    /** Tries the refresh-token grant until it succeeds, the token endpoint says no, or the page leaves. */
    private async renewUntilSettled(): Promise<boolean> {
        for (let attempt = 0; ; attempt++) {
            if (attempt > 0) {
                await pause(attempt - 1);
            }
            if (!this.live() || !this.oauth.getRefreshToken()) {
                return false;
            }
            try {
                await this.oauth.refreshToken();
                this.scheduleRenewal();
                return true;
            } catch (failure) {
                if (endsSession(failure)) {
                    return false;
                }
            }
        }
    }

    /**
     * The session is over: say so once, then sign in again and come back here.
     *
     * <p>Only `signedIn` can expire; every other phase has either no session to end or already said
     * what it had to say, which is what turns a burst — four screens refreshing at once — into one line.
     */
    private expire(): void {
        if (this.phase() !== 'signedIn') {
            return;
        }
        if (!this.accepted && signedInMomentsAgo()) {
            this.phase.set('refused');
            this.dispatcher.dispatch(authEvents.sessionRefused());
            return;
        }
        clearTimeout(this.renewalTimer);
        const unsaved = this.unsaved.any();
        this.dispatcher.dispatch(authEvents.sessionExpired({unsaved}));
        if (unsaved) {
            this.phase.set('held');
        } else {
            this.leave();
        }
    }

    /** The person asked to sign in: from a held redirect, after the loop brake or a failed sign-in, or to retry the issuer. */
    private signInRequested(): void {
        const phase = this.phase();
        if (phase === 'held' || phase === 'refused' || phase === 'failed') {
            this.leaveNow();
        } else if (phase === 'unreachable') {
            void this.retry();
        }
    }

    /** Asks the issuer again, and leaves for it when it answers — back to wherever the person is now. */
    private async retry(): Promise<void> {
        this.phase.set('booting');
        if (await this.discovered()) {
            this.leaveNow();
        }
    }

    /**
     * Loads the discovery document within `DISCOVERY_TIMEOUT_MS`. Without it the library's sign-in
     * waits for an event that never comes, so an issuer that does not answer has to be a phase.
     */
    private async discovered(): Promise<boolean> {
        try {
            if ((await within(this.oauth.loadDiscoveryDocument(), DISCOVERY_TIMEOUT_MS)) !== 'late') {
                return true;
            }
        } catch {
            // Refused, unparseable or unreachable: all the same to the person.
        }
        this.phase.set('unreachable');
        this.dispatcher.dispatch(authEvents.issuerUnreachable());
        return false;
    }

    /** Leaves after the notice, so the line that says why can be read. */
    private leave(): void {
        this.phase.set('leaving');
        setTimeout(() => this.signIn(currentPath()), SESSION_EXPIRED_NOTICE_MS);
    }

    private leaveNow(path = currentPath()): void {
        this.phase.set('leaving');
        this.signIn(path);
    }

    /** Leaves for the identity provider, and leaves the marker the loop brake reads on the way back. */
    private signIn(path: string): void {
        markSignIn();
        this.oauth.initCodeFlow(path);
    }

    /** A bearer request went through: the server takes this sign-in's tokens, so the brake is lifted. */
    private accept(): void {
        if (!this.accepted) {
            this.accepted = true;
            clearSignInMarker();
        }
        if (this.phase() === 'refused') {
            this.phase.set('signedIn');
            // The line that said the server refuses is wrong now, and its Sign in now would do nothing.
            this.dispatcher.dispatch(authEvents.sessionAccepted());
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
            // A token this close to its expiry counts as lapsed, so it is renewed before it
            // goes out rather than expiring on the way to the API. Despite its name the library
            // subtracts this from a time in milliseconds, and it adds the clock skew back on, so
            // both go in here: a token is valid until `EXPIRY_MARGIN_S` before it expires.
            clockSkewInSec: CLOCK_SKEW_S,
            decreaseExpirationBySec: (EXPIRY_MARGIN_S + CLOCK_SKEW_S) * 1000,
            showDebugInformation: false,
            // `remoteOnly` and not `false`: the dev server is plain HTTP on localhost and
            // has to work, but anywhere else a code flow over HTTP puts the token on the
            // wire. `false` would have allowed exactly that, silently, on the one
            // deployment where it matters.
            requireHttps: 'remoteOnly',
        };
    }
}

/** What a request fails with internally when no token can be had; `call` turns it into a 401. */
class SessionEnded extends Error {
    constructor() {
        super('The session has ended');
        this.name = 'SessionEnded';
    }
}

/** What a request fails with internally when its renewal outlasted the limit; `call` turns it into status 0. */
class RenewalTimedOut extends Error {
    constructor() {
        super('The session renewal took too long');
        this.name = 'RenewalTimedOut';
    }
}

/** Whether a request carried the bearer and the server answered 401 to it. */
function refusedBearer(error: unknown): boolean {
    return error instanceof HttpErrorResponse && error.status === HttpStatusCode.Unauthorized;
}

/** Whether the server answered a bearer request with anything but a 401: it took the token. */
function answeredWithBearer(error: unknown): boolean {
    return error instanceof HttpErrorResponse && error.status !== 0 && error.status !== HttpStatusCode.Unauthorized;
}

/** The error a request fails with when it was never sent, or null when `error` is the server's own. */
function notSent(error: unknown, url: string): HttpErrorResponse | null {
    if (error instanceof SessionEnded) {
        return new HttpErrorResponse({status: HttpStatusCode.Unauthorized, statusText: 'Session ended', url});
    }
    if (error instanceof RenewalTimedOut) {
        return new HttpErrorResponse({status: 0, statusText: 'Session renewal timed out', url});
    }
    return null;
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

/** Settles with `promise`, or with `'late'` once `ms` have passed; the timer never outlives it. */
function within<T>(promise: Promise<T>, ms: number): Promise<T | 'late'> {
    let timer: ReturnType<typeof setTimeout> | undefined;
    const late = new Promise<'late'>((resolve) => (timer = setTimeout(() => resolve('late'), ms)));
    return Promise.race([promise, late]).finally(() => clearTimeout(timer));
}

/**
 * When the access token is renewed, as a fraction of its lifetime: early enough that a slow
 * identity provider still answers in time, late enough not to renew every minute. ISC-502 names it.
 */
export const RENEW_AT = 0.75;

/** Seconds before its expiry that a token counts as lapsed: one request's flight, generously. */
export const EXPIRY_MARGIN_S = 30;

/**
 * How far this browser's clock may be off the identity provider's, in seconds, when the library
 * checks an ID token's times. Its default is ten minutes, which also kept an access token "valid"
 * ten minutes past its expiry. A mark, unmeasured.
 */
export const CLOCK_SKEW_S = 60;

/**
 * How long a request waits for a renewal before it fails as unreachable. The renewal itself
 * carries on; only this request gives up, so a screen shows its failure instead of spinning.
 */
export const REQUEST_RENEWAL_TIMEOUT_MS = 10_000;

/** How long the start waits for the issuer's discovery document before it calls the issuer unreachable. */
export const DISCOVERY_TIMEOUT_MS = 10_000;

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
 * The `sessionStorage` key of the loop brake: when this tab last left for the identity provider.
 * A time in milliseconds, never a token — it has to survive the redirect, which memory does not,
 * and it carries nothing an XSS could use. The first bearer request the API accepts removes it.
 */
export const SIGN_IN_MARKER = 'leadgen.auth.signInAt';

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

function clearSignInMarker(): void {
    try {
        window.sessionStorage.removeItem(SIGN_IN_MARKER);
    } catch {
        // Storage refused: there was no marker to clear.
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

/**
 * The URL as the router sees it — path, query and fragment — on this path-located app, without the
 * parameters an authorization response put there. A callback the library has not consumed yet (an
 * issuer that did not answer, an exchange that failed) still carries `code` and `state`, and sent
 * along as the place to come back to they would come back too, to be exchanged a second time.
 */
function currentPath(): string {
    const {pathname, search, hash} = window.location;
    const query = new URLSearchParams(search);
    if (!query.has('state') || !(query.has('code') || query.has('error'))) {
        // Untouched, so the query keeps its own spelling rather than `URLSearchParams`' re-encoding.
        return pathname + search + hash;
    }
    for (const key of CALLBACK_PARAMS) query.delete(key);
    const rest = query.toString();
    return pathname + (rest ? `?${rest}` : '') + hash;
}

/** What an authorization response adds to the redirect URI's query (RFC 6749 § 4.1.2, RFC 9207). */
const CALLBACK_PARAMS: readonly string[] = ['code', 'state', 'session_state', 'iss', 'error', 'error_description', 'error_uri'];

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
