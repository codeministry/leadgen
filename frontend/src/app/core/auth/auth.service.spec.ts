import {HttpClient, HttpEventType, HttpHeaderResponse, HttpParams, provideHttpClient, withInterceptors} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {signal} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {Dispatcher, Events} from '@ngrx/signals/events';
import {firstValueFrom} from 'rxjs';
import {AuthConfig as OidcConfig, OAuthService, OAuthStorage} from 'angular-oauth2-oidc';
import {UnsavedWork} from '@core/unsaved/unsaved-work';
import {AuthConfig} from './auth-config.api';
import {authEvents} from './auth.events';
import {
    AuthService,
    CLOCK_SKEW_S,
    DISCOVERY_TIMEOUT_MS,
    EXPIRY_MARGIN_S,
    RENEW_AT,
    RENEWAL_BACKOFF_MS,
    REQUEST_RENEWAL_TIMEOUT_MS,
    returnPath,
    SESSION_EXPIRED_NOTICE_MS,
    SIGN_IN_LOOP_WINDOW_MS,
    SIGN_IN_MARKER,
    SIGN_IN_STALLED_MS,
} from './auth.service';
import {bearerInterceptor} from './bearer.interceptor';
import {provideOidcClient} from './oidc-client';
import {SignedInState} from './signed-in.state';
import {keepUnsent, unsentQuestion} from '@core/unsaved/unsent-question';

const ISSUER = 'https://auth.example/realms/x';
const OIDC: AuthConfig = {mode: 'oidc', issuer: ISSUER, clientId: 'leadgen-web', gravatar: true};
const NONE: AuthConfig = {mode: 'none', issuer: null, clientId: null, gravatar: false};

/** Every method this service reaches for, and a record of what was called. */
interface Stub extends Partial<OAuthService> {
    calls: string[];
    config: OidcConfig | null;
    signInStates: string[];
    /** Whether the access token is still inside its lifetime; a laptop that slept flips it. */
    valid: boolean;
    accessToken: string;
    refresh: string | null;
    /** When the access token expires, in ms; null sets no renewal timer. */
    expiresAt: number | null;
    /** What each `refreshToken()` answers, in order: `'ok'`, `'hang'`, or the failure the library would reject with. */
    refreshAnswers: unknown[];
    /** What `loadDiscoveryDocument()` does: answer, never answer, or refuse. */
    discovery: 'ok' | 'hang' | 'fail';
    /** What `tryLogin()` does: nothing to exchange, or a code that could not be exchanged. */
    exchange: 'ok' | 'fail';
}

function oauth(): Stub {
    const stub: Stub = {
        calls: [],
        config: null,
        signInStates: [],
        valid: true,
        accessToken: 'a-token',
        refresh: 'refresh-1',
        expiresAt: null,
        refreshAnswers: [],
        discovery: 'ok',
        exchange: 'ok',
        state: '',
        loginUrl: `${ISSUER}/protocol/openid-connect/auth`,
        configure: (config: OidcConfig) => {
            stub.config = config;
            stub.calls.push('configure');
        },
        loadDiscoveryDocument: () => {
            stub.calls.push('discovery');
            if (stub.discovery === 'hang') return new Promise(() => undefined);
            if (stub.discovery === 'fail') return Promise.reject(new Error('unreachable'));
            return Promise.resolve({} as never);
        },
        tryLogin: async () => {
            stub.calls.push('exchange');
            if (stub.exchange === 'fail') throw new Error('invalid_nonce_in_state');
            return true;
        },
        setupAutomaticSilentRefresh: () => {
            stub.calls.push('library-refresh');
        },
        initCodeFlow: (additionalState?: string) => {
            stub.signInStates.push(additionalState ?? '');
            stub.calls.push('sign-in');
        },
        hasValidAccessToken: () => stub.valid,
        getAccessToken: () => stub.accessToken,
        getAccessTokenExpiration: () => stub.expiresAt as number,
        // The library types it `string`, and answers null without one.
        getRefreshToken: () => stub.refresh as string,
        refreshToken: async () => {
            stub.calls.push('renew');
            await Promise.resolve();
            const answer = stub.refreshAnswers.shift() ?? 'ok';
            if (answer === 'hang') return new Promise(() => undefined);
            if (answer !== 'ok') throw answer;
            stub.valid = true;
            stub.accessToken = 'renewed-token';
            return {} as never;
        },
        getIdentityClaims: () => ({name: 'Somebody'}),
    };
    return stub;
}

function setUp(stub = oauth()): {service: AuthService; backend: HttpTestingController; http: HttpClient; spy: Stub} {
    TestBed.configureTestingModule({
        providers: [
            provideHttpClient(withInterceptors([bearerInterceptor])),
            provideHttpClientTesting(),
            {provide: OAuthService, useValue: stub},
        ],
    });
    return {
        service: TestBed.inject(AuthService),
        backend: TestBed.inject(HttpTestingController),
        http: TestBed.inject(HttpClient),
        spy: stub,
    };
}

async function initialised(service: AuthService, backend: HttpTestingController, config: AuthConfig): Promise<void> {
    const done = service.initialise();
    backend.expectOne('/api/v1/auth-config').flush(config);
    await done;
}

/** Every payload of `event` the service dispatches, in order. */
function seen<P>(event: {type: string} & ((...args: never[]) => {payload: P})): P[] {
    const payloads: P[] = [];
    TestBed.inject(Events)
        .on(event as never)
        .subscribe((dispatched: {payload: P}) => payloads.push(dispatched.payload));
    return payloads;
}

function renewals(spy: Stub): number {
    return spy.calls.filter((call) => call === 'renew').length;
}

/** Sends one request and records how it ended: a status, or `'ok'`. */
function request(http: HttpClient, url: string): (number | 'ok')[] {
    const outcome: (number | 'ok')[] = [];
    http.get(url).subscribe({next: () => outcome.push('ok'), error: (error: {status: number}) => outcome.push(error.status)});
    return outcome;
}

const UNAUTHORIZED = {status: 401, statusText: 'Unauthorized'};

/** The next request to `url`, once it has gone out: a replay waits for its renewal first. */
async function sent(backend: HttpTestingController, url: string) {
    return vi.waitFor(() => backend.expectOne(url));
}

/**
 * The real library, with a session as the code flow leaves it seeded where the client keeps it,
 * signed in through `initialise`. Fake time from 2026-10-01 10:00; the token expires `lifetime` ms on.
 */
async function realClient(lifetime: number) {
    vi.useFakeTimers({now: new Date('2026-10-01T10:00:00Z'), toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'Date']});
    // Start from empty storages: specs share one worker, and another one's preference (the
    // theme, say) left behind would read as a token this test never wrote.
    window.sessionStorage.clear();
    window.localStorage.clear();
    const written = vi.spyOn(Storage.prototype, 'setItem');
    TestBed.configureTestingModule({
        providers: [provideHttpClient(withInterceptors([bearerInterceptor])), provideHttpClientTesting(), provideOidcClient()],
    });
    const now = Date.now();
    // A session as the code flow leaves it, put where the client keeps it.
    const storage = TestBed.inject(OAuthStorage);
    storage.setItem('access_token', 'first-token');
    storage.setItem('access_token_stored_at', String(now));
    storage.setItem('expires_at', String(now + lifetime));
    storage.setItem('refresh_token', 'refresh-1');
    storage.setItem('id_token', 'header.payload.signature');
    storage.setItem('id_token_claims_obj', JSON.stringify({name: 'Somebody'}));
    storage.setItem('id_token_stored_at', String(now));
    storage.setItem('id_token_expires_at', String(now + lifetime));
    const service = TestBed.inject(AuthService);
    const backend = TestBed.inject(HttpTestingController);
    const http = TestBed.inject(HttpClient);

    const done = service.initialise();
    backend.expectOne('/api/v1/auth-config').flush(OIDC);
    await vi.waitFor(() =>
        backend.expectOne(`${ISSUER}/.well-known/openid-configuration`).flush({
            issuer: ISSUER,
            authorization_endpoint: `${ISSUER}/protocol/openid-connect/auth`,
            token_endpoint: `${ISSUER}/protocol/openid-connect/token`,
            end_session_endpoint: `${ISSUER}/protocol/openid-connect/logout`,
            userinfo_endpoint: `${ISSUER}/protocol/openid-connect/userinfo`,
            revocation_endpoint: `${ISSUER}/protocol/openid-connect/revoke`,
            jwks_uri: `${ISSUER}/protocol/openid-connect/certs`,
        }),
    );
    await vi.waitFor(() => backend.expectOne(`${ISSUER}/protocol/openid-connect/certs`).flush({keys: []}));
    await done;
    return {service, backend, http, storage, written, now};
}

afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    window.history.replaceState(null, '', '/');
    window.sessionStorage.clear();
});

describe('AuthService', () => {
    it('does nothing at all when the instance runs without authentication', async () => {
        // The shipped default. No discovery request, no redirect, no refresh timer and nothing
        // to attach — the tool has to run on one machine with no identity provider anywhere.
        const {service, backend, spy} = setUp();

        await initialised(service, backend, NONE);

        expect(spy.calls).toEqual([]);
        expect(service.phase()).toBe('off');
        expect(service.isOidc()).toBe(false);
        expect(service.token()).toBeNull();
        backend.verify();
    });

    it('configures the flow, loads the discovery document, exchanges a code and is signed in when the instance says oidc', async () => {
        const {service, backend, spy} = setUp();

        await initialised(service, backend, OIDC);

        expect(spy.calls).toEqual(['configure', 'discovery', 'exchange']);
        expect(service.phase()).toBe('signedIn');
        expect(service.token()).toBe('a-token');
        // The user menu reads these two: the claims as a user, and the avatar switch from the config.
        const signedIn = TestBed.inject(SignedInState);
        expect(signedIn.user()).toMatchObject({name: 'Somebody', issuer: ISSUER});
        expect(signedIn.gravatar()).toBe(true);
        backend.verify();
    });

    it('resolves rather than rejects when the API cannot be reached', async () => {
        // This runs in an app initializer, and an initializer that rejects stops Angular
        // bootstrapping. A backend that is down has to end as a screen reporting it, not
        // as a blank page where the application should have been.
        const {service, backend, spy} = setUp();

        const done = service.initialise();
        backend.expectOne('/api/v1/auth-config').error(new ProgressEvent('offline'));

        await expect(done).resolves.toBeUndefined();
        expect(spy.calls).toEqual([]);
        expect(service.token()).toBeNull();
        backend.verify();
    });

    it('treats a mode of oidc with no issuer as nothing to do', async () => {
        // The server refuses that combination at load, so this is the belt to that brace:
        // a half-answer must not put the browser into a redirect loop against undefined.
        const {service, backend, spy} = setUp();

        await initialised(service, backend, {mode: 'oidc', issuer: null, clientId: null, gravatar: true});

        expect(spy.calls).toEqual([]);
        expect(service.token()).toBeNull();
        backend.verify();
    });
});

describe('AuthService — a load that has to sign in (review finding 1)', () => {
    it('does not resolve while it leaves, leaves once, and nothing renders or requests on the way out', async () => {
        window.history.replaceState(null, '', '/shortlist/5?q=java');
        const stub = oauth();
        stub.valid = false;
        const {service, backend, http, spy} = setUp(stub);
        const expired = seen(authEvents.sessionExpired);
        let resolved = false;

        void service.initialise().then(() => (resolved = true));
        backend.expectOne('/api/v1/auth-config').flush(OIDC);
        await vi.waitFor(() => expect(spy.calls).toContain('sign-in'));
        // Whatever a screen would send while the browser leaves goes nowhere and ends nothing.
        const outcome = request(http, '/api/v1/offers');
        await vi.waitFor(() => expect(outcome).toEqual([401]));

        expect(resolved).toBe(false);
        expect(service.phase()).toBe('leaving');
        expect(spy.signInStates).toEqual(['/shortlist/5?q=java']);
        expect(renewals(spy)).toBe(0);
        expect(expired).toEqual([]);
        backend.verify();
    });
});

describe('AuthService — an issuer that does not answer at the start (review finding 5)', () => {
    for (const discovery of ['hang', 'fail'] as const) {
        it(`${discovery === 'hang' ? 'silent' : 'refusing'}: the page renders, says so once, and "Try again" asks again and leaves when it answers`, async () => {
            window.history.replaceState(null, '', '/pipeline/3');
            vi.useFakeTimers();
            const stub = oauth();
            stub.valid = false;
            stub.discovery = discovery;
            const {service, backend, spy} = setUp(stub);
            const unreachable = seen(authEvents.issuerUnreachable);

            const done = service.initialise();
            backend.expectOne('/api/v1/auth-config').flush(OIDC);
            await vi.advanceTimersByTimeAsync(DISCOVERY_TIMEOUT_MS);
            await done;

            expect(service.phase()).toBe('unreachable');
            expect(unreachable).toHaveLength(1);
            expect(spy.calls).not.toContain('sign-in');

            stub.discovery = 'ok';
            TestBed.inject(Dispatcher).dispatch(authEvents.signInRequested());
            await vi.advanceTimersByTimeAsync(0);
            expect(spy.signInStates).toEqual(['/pipeline/3']);
        });
    }
});

describe('AuthService — the place to come back to (code review 4, finding 5)', () => {
    it('an unanswered callback load retries without its code and state, and from wherever the person is now', async () => {
        window.history.replaceState(null, '', '/shortlist?code=X&state=nonce%3B%252Fshortlist&session_state=s&iss=https%3A%2F%2Fauth');
        vi.useFakeTimers();
        const stub = oauth();
        stub.discovery = 'fail';
        const {service, backend, spy} = setUp(stub);
        await initialised(service, backend, OIDC);
        expect(service.phase()).toBe('unreachable');

        window.history.replaceState(null, '', '/pipeline/3?view=board&code=X&state=nonce');
        stub.discovery = 'ok';
        TestBed.inject(Dispatcher).dispatch(authEvents.signInRequested());
        await vi.advanceTimersByTimeAsync(0);

        expect(spy.signInStates).toEqual(['/pipeline/3?view=board']);
    });

    it('leaves a query that is no callback exactly as it is spelled', async () => {
        window.history.replaceState(null, '', '/shortlist/5?q=java%20remote&state=open#ad');
        const stub = oauth();
        stub.valid = false;
        const {service, backend, spy} = setUp(stub);

        void service.initialise();
        backend.expectOne('/api/v1/auth-config').flush(OIDC);
        await vi.waitFor(() => expect(spy.calls).toContain('sign-in'));

        expect(spy.signInStates).toEqual(['/shortlist/5?q=java%20remote&state=open#ad']);
    });
});

describe('AuthService — the renewal (ISC-502)', () => {
    it('renews with the refresh-token grant on its own timer: no iframe, no library refresh, a margin, no offline_access', async () => {
        const {service, backend, spy} = setUp();

        await initialised(service, backend, OIDC);

        expect(spy.config).toMatchObject({responseType: 'code', useSilentRefresh: false});
        expect(spy.config?.silentRefreshRedirectUri).toBeUndefined();
        expect(spy.config?.decreaseExpirationBySec).toBeGreaterThan(0);
        // One place sends every refresh-token grant (review finding 6): the library's own refresh stays off.
        expect(spy.calls).not.toContain('library-refresh');
        // A refresh token bound to the SSO session is enough; an offline token would outlive the logout.
        expect(spy.config?.scope?.split(' ')).not.toContain('offline_access');
    });

    it('renews at 75 % of the lifetime, and again after that', async () => {
        vi.useFakeTimers({now: new Date('2026-10-01T10:00:00Z')});
        const lifetime = 300_000;
        const stub = oauth();
        stub.expiresAt = Date.now() + lifetime;
        const {service, backend, spy} = setUp(stub);
        await initialised(service, backend, OIDC);

        await vi.advanceTimersByTimeAsync(lifetime * RENEW_AT - 1);
        expect(renewals(spy)).toBe(0);
        stub.expiresAt = Date.now() + 1 + lifetime;
        await vi.advanceTimersByTimeAsync(1);
        expect(renewals(spy)).toBe(1);
        await vi.advanceTimersByTimeAsync(lifetime * RENEW_AT);
        expect(renewals(spy)).toBe(2);
        expect(service.token()).toBe('renewed-token');
    });

    it('renews before expiry with the real client, and the next request carries the renewed bearer; nothing reaches storage', async () => {
        const lifetime = 300_000;
        const {service, backend, http, storage, written, now} = await realClient(lifetime);
        expect(service.token()).toBe('first-token');

        // Around 75 % of the lifetime: the start itself took a few fake milliseconds, so the exact
        // instant is the stubbed spec's to pin, and this one asks for the window.
        await vi.advanceTimersByTimeAsync(now + lifetime * 0.74 - Date.now());
        expect(backend.match(`${ISSUER}/protocol/openid-connect/token`)).toEqual([]);
        await vi.advanceTimersByTimeAsync(lifetime * 0.02);
        const renewal = backend.expectOne(`${ISSUER}/protocol/openid-connect/token`);
        expect(renewal.request.method).toBe('POST');
        const body = renewal.request.body as HttpParams;
        expect(body.get('grant_type')).toBe('refresh_token');
        expect(body.get('refresh_token')).toBe('refresh-1');
        expect(Date.now()).toBeLessThan(now + lifetime);
        renewal.flush({access_token: 'renewed-token', refresh_token: 'refresh-2', expires_in: 300, token_type: 'Bearer'});
        await vi.advanceTimersByTimeAsync(0);

        http.get('/api/v1/offers').subscribe();
        expect(backend.expectOne('/api/v1/offers').request.headers.get('Authorization')).toBe('Bearer renewed-token');
        expect(storage.getItem('refresh_token')).toBe('refresh-2');
        // Tokens in memory only: neither the seeded session nor the renewal touched web storage.
        // The one write the library makes is its constructor's accessibility probe, a `test` key
        // it removes again at once; it carries nothing.
        expect(written.mock.calls.filter(([key]) => key !== 'test')).toEqual([]);
        expect(Object.keys(window.sessionStorage)).toEqual([]);
        expect(Object.keys(window.localStorage)).toEqual([]);
    });
});

describe('AuthService — the expiry margin, against the real library (code review 4, finding 1)', () => {
    it('sets the margin in the unit the library reads it in, with a clock skew of its own', async () => {
        const {spy} = await (async () => {
            const ctx = setUp();
            await initialised(ctx.service, ctx.backend, OIDC);
            return ctx;
        })();

        expect(spy.config?.clockSkewInSec).toBe(CLOCK_SKEW_S);
        expect(spy.config?.decreaseExpirationBySec).toBe((EXPIRY_MARGIN_S + CLOCK_SKEW_S) * 1000);
    });

    it('a token counts as lapsed EXPIRY_MARGIN_S before it expires, not ten minutes after', async () => {
        const lifetime = 120_000;
        const {service, now} = await realClient(lifetime);

        await vi.advanceTimersByTimeAsync(now + lifetime - (EXPIRY_MARGIN_S + 1) * 1000 - Date.now());
        expect(service.token()).toBe('first-token');
        await vi.advanceTimersByTimeAsync(2_000);
        expect(service.token()).toBeNull();
    });
});

describe('AuthService — a 401 to a request that carried the bearer (review finding 8)', () => {
    it('is answered with one renewal and one replay: a token that lapsed in flight ends nothing', async () => {
        const {service, backend, http, spy} = setUp();
        await initialised(service, backend, OIDC);
        const expired = seen(authEvents.sessionExpired);

        const outcome = request(http, '/api/v1/offers');
        backend.expectOne('/api/v1/offers').flush(null, UNAUTHORIZED);
        const replay = await sent(backend, '/api/v1/offers');
        expect(replay.request.headers.get('Authorization')).toBe('Bearer renewed-token');
        replay.flush([]);

        expect(outcome).toEqual(['ok']);
        expect(renewals(spy)).toBe(1);
        expect(expired).toEqual([]);
        expect(service.phase()).toBe('signedIn');
    });

    it('a replay still answered 401 ends the session: one event for the burst, then the sign-in from the current URL', async () => {
        window.history.replaceState(null, '', '/pipeline/3');
        const {service, backend, http, spy} = setUp();
        await initialised(service, backend, OIDC);
        const expired = seen(authEvents.sessionExpired);
        vi.useFakeTimers();

        const offers = request(http, '/api/v1/offers');
        const applications = request(http, '/api/v1/applications');
        backend.expectOne('/api/v1/offers').flush(null, UNAUTHORIZED);
        backend.expectOne('/api/v1/applications').flush(null, UNAUTHORIZED);
        await vi.advanceTimersByTimeAsync(0);
        // One renewal for both: the second refusal joins the first one's grant.
        expect(renewals(spy)).toBe(1);
        backend.expectOne('/api/v1/offers').flush(null, UNAUTHORIZED);
        backend.expectOne('/api/v1/applications').flush(null, UNAUTHORIZED);

        expect(offers).toEqual([401]);
        expect(applications).toEqual([401]);
        expect(expired).toEqual([{unsaved: false}]);
        expect(spy.calls).not.toContain('sign-in');
        await vi.advanceTimersByTimeAsync(SESSION_EXPIRED_NOTICE_MS);
        expect(spy.signInStates).toEqual(['/pipeline/3']);
    });

    it('a 403 is a signed-in person without the right: it passes through, renews nothing and ends nothing', async () => {
        const {service, backend, http, spy} = setUp();
        await initialised(service, backend, OIDC);
        const expired = seen(authEvents.sessionExpired);

        const outcome = request(http, '/api/v1/offers');
        backend.expectOne('/api/v1/offers').flush(null, {status: 403, statusText: 'Forbidden'});

        expect(outcome).toEqual([403]);
        expect(renewals(spy)).toBe(0);
        expect(expired).toEqual([]);
    });

    it('does nothing under none, whatever answers 401', async () => {
        const {service, backend, http, spy} = setUp();
        await initialised(service, backend, NONE);
        const expired = seen(authEvents.sessionExpired);

        const outcome = request(http, '/api/v1/offers');
        const sent = backend.expectOne('/api/v1/offers');
        expect(sent.request.headers.has('Authorization')).toBe(false);
        sent.flush(null, UNAUTHORIZED);

        expect(outcome).toEqual([401]);
        expect(expired).toEqual([]);
        expect(spy.calls).toEqual([]);
    });
});

describe('AuthService — an access token that lapsed while a refresh token stands', () => {
    it('renews first and then sends, and never sends without the bearer', async () => {
        const {service, backend, http, spy} = setUp();
        await initialised(service, backend, OIDC);
        spy.valid = false; // the laptop slept through the renewal

        http.get('/api/v1/offers').subscribe();
        http.get('/api/v1/applications').subscribe();
        expect(backend.match('/api/v1/offers')).toEqual([]);

        const offers = await sent(backend, '/api/v1/offers');
        const applications = await sent(backend, '/api/v1/applications');
        expect(renewals(spy)).toBe(1);
        for (const one of [offers, applications]) {
            expect(one.request.headers.get('Authorization')).toBe('Bearer renewed-token');
        }
    });

    it('with no renewal possible it reports the ended session and sends nothing', async () => {
        const {service, backend, http, spy} = setUp();
        await initialised(service, backend, OIDC);
        const expired = seen(authEvents.sessionExpired);
        vi.useFakeTimers();
        spy.valid = false;
        spy.refresh = null;

        const outcome = request(http, '/api/v1/offers');
        await vi.advanceTimersByTimeAsync(0);

        expect(outcome).toEqual([401]);
        expect(backend.match(() => true)).toEqual([]);
        expect(expired).toHaveLength(1);
    });

    it('a request waits for a renewal that keeps failing only so long, then fails as unreachable while the renewal carries on (review finding 4)', async () => {
        const stub = oauth();
        stub.refreshAnswers = Array.from({length: 20}, () => ({status: 503}));
        const {service, backend, http, spy} = setUp(stub);
        await initialised(service, backend, OIDC);
        const expired = seen(authEvents.sessionExpired);
        vi.useFakeTimers();
        spy.valid = false;

        const outcome = request(http, '/api/v1/offers');
        await vi.advanceTimersByTimeAsync(REQUEST_RENEWAL_TIMEOUT_MS - 1);
        expect(outcome).toEqual([]);
        await vi.advanceTimersByTimeAsync(1);

        expect(outcome).toEqual([0]);
        expect(backend.match(() => true)).toEqual([]);
        expect(expired).toEqual([]);
        const attempts = renewals(spy);
        await vi.advanceTimersByTimeAsync(RENEWAL_BACKOFF_MS.at(-1)!);
        expect(renewals(spy)).toBeGreaterThan(attempts);
        expect(service.phase()).toBe('signedIn');
    });
});

describe('AuthService — one refresh-token grant at a time (review findings 6 and 10)', () => {
    it('a request that finds the token lapsed while the timer renews joins that renewal', async () => {
        vi.useFakeTimers({now: new Date('2026-10-01T10:00:00Z')});
        const stub = oauth();
        stub.expiresAt = Date.now() + 100_000;
        stub.refreshAnswers = ['hang'];
        const {service, backend, http, spy} = setUp(stub);
        await initialised(service, backend, OIDC);

        await vi.advanceTimersByTimeAsync(100_000 * RENEW_AT);
        expect(renewals(spy)).toBe(1);
        spy.valid = false;
        request(http, '/api/v1/offers');
        await vi.advanceTimersByTimeAsync(0);

        expect(renewals(spy)).toBe(1);
        expect(service.phase()).toBe('signedIn');
    });

    it('a 401 to a token somebody else has already renewed replays with the fresh one, without a second grant', async () => {
        const {service, backend, http, spy} = setUp();
        await initialised(service, backend, OIDC);

        const outcome = request(http, '/api/v1/offers');
        const first = backend.expectOne('/api/v1/offers');
        spy.accessToken = 'renewed-elsewhere';
        first.flush(null, UNAUTHORIZED);
        const replay = await sent(backend, '/api/v1/offers');
        replay.flush([]);

        expect(replay.request.headers.get('Authorization')).toBe('Bearer renewed-elsewhere');
        expect(outcome).toEqual(['ok']);
        expect(renewals(spy)).toBe(0);
    });
});

describe('AuthService — a renewal that fails for a while', () => {
    it('a network failure or a 5xx keeps the session and retries with backoff until one succeeds', async () => {
        vi.useFakeTimers({now: new Date('2026-10-01T10:00:00Z')});
        const stub = oauth();
        stub.expiresAt = Date.now() + 100_000;
        stub.refreshAnswers = [{status: 0}, {status: 503}, 'ok'];
        const {service, backend, spy} = setUp(stub);
        await initialised(service, backend, OIDC);
        const expired = seen(authEvents.sessionExpired);

        await vi.advanceTimersByTimeAsync(100_000 * RENEW_AT);
        expect(renewals(spy)).toBe(1);
        stub.expiresAt = null;
        await vi.advanceTimersByTimeAsync(RENEWAL_BACKOFF_MS[0]!);
        expect(renewals(spy)).toBe(2);
        await vi.advanceTimersByTimeAsync(RENEWAL_BACKOFF_MS[1]!);
        expect(renewals(spy)).toBe(3);
        await vi.advanceTimersByTimeAsync(120_000);

        expect(renewals(spy)).toBe(3);
        expect(expired).toEqual([]);
        expect(spy.calls).not.toContain('sign-in');
        expect(service.token()).toBe('renewed-token');
    });

    it('offline, it waits for the online event before it tries again', async () => {
        vi.useFakeTimers({now: new Date('2026-10-01T10:00:00Z')});
        const stub = oauth();
        stub.expiresAt = Date.now() + 100_000;
        stub.refreshAnswers = [{status: 0}];
        const {service, backend, spy} = setUp(stub);
        await initialised(service, backend, OIDC);
        const online = vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false);

        await vi.advanceTimersByTimeAsync(100_000 * RENEW_AT);
        stub.expiresAt = null;
        await vi.advanceTimersByTimeAsync(600_000);
        expect(renewals(spy)).toBe(1);

        online.mockReturnValue(true);
        window.dispatchEvent(new Event('online'));
        await vi.advanceTimersByTimeAsync(0);
        expect(renewals(spy)).toBe(2);
    });

    it('an invalid_grant from the token endpoint during the retries ends the session', async () => {
        vi.useFakeTimers({now: new Date('2026-10-01T10:00:00Z')});
        const stub = oauth();
        stub.expiresAt = Date.now() + 100_000;
        stub.refreshAnswers = [{status: 502}, {status: 400, error: {error: 'invalid_grant'}}];
        const {service, backend, spy} = setUp(stub);
        await initialised(service, backend, OIDC);
        const expired = seen(authEvents.sessionExpired);

        await vi.advanceTimersByTimeAsync(100_000 * RENEW_AT);
        expect(expired).toEqual([]);
        await vi.advanceTimersByTimeAsync(RENEWAL_BACKOFF_MS[0]!);

        expect(expired).toHaveLength(1);
        await vi.advanceTimersByTimeAsync(SESSION_EXPIRED_NOTICE_MS);
        expect(spy.calls).toContain('sign-in');
    });
});

describe('AuthService — the way back from the identity provider', () => {
    it('asks the sign-in to come back to the URL it started from, and goes there afterwards', async () => {
        window.history.replaceState(null, '', '/shortlist/5?q=java');
        const stub = oauth();
        // What the library leaves in `state` after the code exchange: the additional state, still encoded once.
        stub.state = encodeURIComponent('/pipeline/3?view=board');
        const {service, backend} = setUp(stub);

        await initialised(service, backend, OIDC);

        expect(window.location.pathname + window.location.search).toBe('/pipeline/3?view=board');
    });

    it('never returns to another origin, whatever the state says', async () => {
        window.history.replaceState(null, '', '/');
        const stub = oauth();
        stub.state = encodeURIComponent('//evil.example/phish');
        const {service, backend} = setUp(stub);

        await initialised(service, backend, OIDC);

        expect(window.location.origin + window.location.pathname).toBe(`${window.location.origin}/`);
    });

    it('a failed code exchange leaves no dead app: it goes straight back to the identity provider and renders nothing on the way (code review 4, finding 3)', async () => {
        window.history.replaceState(null, '', '/shortlist?code=used&state=nonce%3B%252Fshortlist&session_state=s');
        const stub = oauth();
        stub.valid = false;
        stub.exchange = 'fail';
        const {service, backend, spy} = setUp(stub);
        const expired = seen(authEvents.sessionExpired);
        let resolved = false;

        void service.initialise().then(() => (resolved = true));
        backend.expectOne('/api/v1/auth-config').flush(OIDC);
        await vi.waitFor(() => expect(spy.calls).toContain('sign-in'));

        expect(resolved).toBe(false);
        // Nobody was signed in, so no session ended; and the used code does not go along.
        expect(expired).toEqual([]);
        expect(spy.signInStates).toEqual(['/shortlist']);
    });

    it('inside the loop window it says the sign-in failed — not that the server refuses — and Try again is the next attempt (code review 4, finding 7)', async () => {
        window.sessionStorage.setItem(SIGN_IN_MARKER, String(Date.now()));
        window.history.replaceState(null, '', '/pipeline?error=access_denied&state=nonce');
        const stub = oauth();
        stub.valid = false;
        stub.refresh = null;
        stub.exchange = 'fail';
        const {service, backend, http, spy} = setUp(stub);
        const failed = seen(authEvents.signInFailed);
        const refused = seen(authEvents.sessionRefused);

        await initialised(service, backend, OIDC);
        expect(service.phase()).toBe('failed');
        expect(failed).toHaveLength(1);
        expect(refused).toEqual([]);

        // Nothing is signed in: a request goes nowhere and tries no renewal.
        const outcome = request(http, '/api/v1/offers');
        await vi.waitFor(() => expect(outcome).toEqual([401]));
        expect(renewals(spy)).toBe(0);
        expect(spy.calls).not.toContain('sign-in');

        TestBed.inject(Dispatcher).dispatch(authEvents.signInRequested());
        expect(spy.signInStates).toEqual(['/pipeline']);
    });
});

describe('AuthService — the loop brake (a server that keeps answering 401)', () => {
    it('a sign-in leaves a marker, and a refused replay within the minute after raises the refusal instead of a second redirect', async () => {
        window.history.replaceState(null, '', '/pipeline/3');
        vi.useFakeTimers();
        const first = setUp(Object.assign(oauth(), {valid: false}));
        void first.service.initialise();
        first.backend.expectOne('/api/v1/auth-config').flush(OIDC);
        await vi.advanceTimersByTimeAsync(0);
        expect(first.spy.signInStates).toEqual(['/pipeline/3']);
        // The marker is a time, not a token: ISC-502's "tokens in memory only" still holds.
        expect(window.sessionStorage.getItem(SIGN_IN_MARKER)).toBe(String(Date.now()));

        // The browser comes back from the identity provider: a fresh page, a fresh service.
        TestBed.resetTestingModule();
        await vi.advanceTimersByTimeAsync(20_000);
        const second = setUp();
        await initialised(second.service, second.backend, OIDC);
        const expired = seen(authEvents.sessionExpired);
        const refused = seen(authEvents.sessionRefused);
        const outcome = request(second.http, '/api/v1/offers');
        second.backend.expectOne('/api/v1/offers').flush(null, UNAUTHORIZED);
        await vi.advanceTimersByTimeAsync(0);
        second.backend.expectOne('/api/v1/offers').flush(null, UNAUTHORIZED);
        await vi.advanceTimersByTimeAsync(SESSION_EXPIRED_NOTICE_MS * 10);

        expect(outcome).toEqual([401]);
        expect(second.spy.signInStates).toEqual([]);
        expect(expired).toEqual([]);
        expect(refused).toHaveLength(1);
        expect(second.service.phase()).toBe('refused');
    });

    it('the brake keeps the page alive: it still sends its bearer, and the first request let through lifts it and clears the marker (review finding 7)', async () => {
        window.sessionStorage.setItem(SIGN_IN_MARKER, String(Date.now()));
        const {service, backend, http, spy} = setUp();
        await initialised(service, backend, OIDC);
        const refused = seen(authEvents.sessionRefused);
        const accepted = seen(authEvents.sessionAccepted);

        request(http, '/api/v1/offers');
        backend.expectOne('/api/v1/offers').flush(null, UNAUTHORIZED);
        (await sent(backend, '/api/v1/offers')).flush(null, UNAUTHORIZED);
        expect(refused).toHaveLength(1);
        expect(accepted).toEqual([]);
        expect(service.phase()).toBe('refused');

        const later = request(http, '/api/v1/applications');
        const applications = backend.expectOne('/api/v1/applications');
        expect(applications.request.headers.get('Authorization')).toBe(`Bearer ${spy.accessToken}`);
        applications.flush([]);

        expect(later).toEqual(['ok']);
        expect(service.phase()).toBe('signedIn');
        // So the refusal line can go (code review 4, finding 4).
        expect(accepted).toHaveLength(1);
        expect(window.sessionStorage.getItem(SIGN_IN_MARKER)).toBeNull();
    });

    it('under the brake a 401 is handed on as it is: no refresh grant, no replay (code review 4, finding 2)', async () => {
        window.sessionStorage.setItem(SIGN_IN_MARKER, String(Date.now()));
        const {service, backend, http, spy} = setUp();
        await initialised(service, backend, OIDC);
        request(http, '/api/v1/offers');
        backend.expectOne('/api/v1/offers').flush(null, UNAUTHORIZED);
        (await sent(backend, '/api/v1/offers')).flush(null, UNAUTHORIZED);
        expect(service.phase()).toBe('refused');
        const before = renewals(spy);

        for (const screen of ['/api/v1/offers', '/api/v1/applications', '/api/v1/rules']) {
            const outcome = request(http, screen);
            backend.expectOne(screen).flush(null, UNAUTHORIZED);
            expect(outcome).toEqual([401]);
        }
        await Promise.resolve();

        expect(renewals(spy)).toBe(before);
        backend.verify();
    });

    it('no refusal proves the bearer was taken, a 403 or a 404 included: only a success lifts the brake (code review 7, findings 3 and 4)', async () => {
        // A 404 may be Traefik's own for a route it does not know; a 403 or the API's own 429 look
        // the same from outside. Too strict costs a minute of the refusal line, too generous a loop.
        window.sessionStorage.setItem(SIGN_IN_MARKER, String(Date.now()));
        const {service, backend, http} = setUp();
        await initialised(service, backend, OIDC);

        for (const status of [403, 404, 409, 422]) {
            const outcome = request(http, '/api/v1/rules');
            backend.expectOne('/api/v1/rules').flush(null, {status, statusText: 'Refused'});
            expect(outcome).toEqual([status]);
        }
        expect(window.sessionStorage.getItem(SIGN_IN_MARKER)).not.toBeNull();

        request(http, '/api/v1/rules');
        backend.expectOne('/api/v1/rules').flush([]);
        expect(window.sessionStorage.getItem(SIGN_IN_MARKER)).toBeNull();
    });

    it('a discovery document without an authorization endpoint does not start a sign-in that waits for ever (code review 10, finding 4)', async () => {
        const stub = oauth();
        stub.valid = false;
        stub.loginUrl = '';
        const {service, backend, spy} = setUp(stub);
        const failed = seen(authEvents.signInFailed);

        await initialised(service, backend, OIDC);

        expect(service.phase()).toBe('failed');
        expect(failed).toHaveLength(1);
        expect(spy.calls).not.toContain('sign-in');
    });

    it('a sign-in that cannot keep its nonce does not start: the page renders with the failure (code review 7, finding 2)', async () => {
        const stub = oauth();
        stub.valid = false;
        const {service, backend, spy} = setUp(stub);
        const failed = seen(authEvents.signInFailed);
        vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
            throw new DOMException('quota', 'QuotaExceededError');
        });

        await initialised(service, backend, OIDC);

        expect(service.phase()).toBe('failed');
        expect(failed).toHaveLength(1);
        expect(spy.calls).not.toContain('sign-in');
    });

    it('the first request the API accepts clears the marker, so a later expiry signs in as usual', async () => {
        vi.useFakeTimers();
        window.sessionStorage.setItem(SIGN_IN_MARKER, String(Date.now()));
        const {service, backend, http, spy} = setUp();
        await initialised(service, backend, OIDC);
        const refused = seen(authEvents.sessionRefused);

        request(http, '/api/v1/offers');
        backend.expectOne('/api/v1/offers').flush([]);
        expect(window.sessionStorage.getItem(SIGN_IN_MARKER)).toBeNull();
        request(http, '/api/v1/offers');
        backend.expectOne('/api/v1/offers').flush(null, UNAUTHORIZED);
        await vi.advanceTimersByTimeAsync(0);
        backend.expectOne('/api/v1/offers').flush(null, UNAUTHORIZED);
        await vi.advanceTimersByTimeAsync(SESSION_EXPIRED_NOTICE_MS);

        expect(refused).toEqual([]);
        expect(spy.calls).toContain('sign-in');
    });

    it('a marker older than the window is no loop: the next expiry signs in again as usual', async () => {
        vi.useFakeTimers();
        window.sessionStorage.setItem(SIGN_IN_MARKER, String(Date.now() - SIGN_IN_LOOP_WINDOW_MS - 1));
        const {service, backend, http, spy} = setUp();
        await initialised(service, backend, OIDC);
        const refused = seen(authEvents.sessionRefused);
        spy.valid = false;
        spy.refresh = null;

        request(http, '/api/v1/offers');
        await vi.advanceTimersByTimeAsync(SESSION_EXPIRED_NOTICE_MS);

        expect(refused).toEqual([]);
        expect(spy.calls).toContain('sign-in');
    });

    it('"Sign in now" after the brake leaves at once', async () => {
        window.sessionStorage.setItem(SIGN_IN_MARKER, String(Date.now()));
        window.history.replaceState(null, '', '/shortlist');
        const {service, backend, http, spy} = setUp();
        await initialised(service, backend, OIDC);
        spy.valid = false;
        spy.refresh = null;
        const outcome = request(http, '/api/v1/offers');
        await vi.waitFor(() => expect(outcome).toEqual([401]));
        expect(service.phase()).toBe('refused');

        TestBed.inject(Dispatcher).dispatch(authEvents.signInRequested());

        expect(spy.signInStates).toEqual(['/shortlist']);
    });
});

describe('AuthService — unsaved work holds the redirect (review finding 2)', () => {
    async function expiredWith(dirty: () => boolean) {
        window.history.replaceState(null, '', '/shortlist/5');
        const ctx = setUp();
        await initialised(ctx.service, ctx.backend, OIDC);
        const expired = seen(authEvents.sessionExpired);
        TestBed.inject(UnsavedWork).track(dirty);
        vi.useFakeTimers();
        ctx.spy.valid = false;
        ctx.spy.refresh = null;
        request(ctx.http, '/api/v1/offers');
        await vi.advanceTimersByTimeAsync(0);
        return {...ctx, expired};
    }

    it('the notice says so, and the sign-in waits for the person, however long', async () => {
        const {service, spy, expired} = await expiredWith(() => true);

        await vi.advanceTimersByTimeAsync(SESSION_EXPIRED_NOTICE_MS * 100);
        expect(expired).toEqual([{unsaved: true}]);
        expect(service.phase()).toBe('held');
        expect(spy.calls).not.toContain('sign-in');

        TestBed.inject(Dispatcher).dispatch(authEvents.signInRequested());
        expect(spy.signInStates).toEqual(['/shortlist/5']);
    });

    it('leaves by itself once nothing is left to lose, with no toast needed', async () => {
        const dirty = signal(true);
        const {service, spy} = await expiredWith(() => dirty());
        expect(service.phase()).toBe('held');

        dirty.set(false);
        TestBed.tick();
        await vi.advanceTimersByTimeAsync(SESSION_EXPIRED_NOTICE_MS);

        expect(spy.signInStates).toEqual(['/shortlist/5']);
    });

    it('while held, a request is not sent and fails as the 401 it would have got', async () => {
        const {backend, http, expired} = await expiredWith(() => true);

        const outcome = request(http, '/api/v1/applications');
        await vi.advanceTimersByTimeAsync(0);

        expect(outcome).toEqual([401]);
        expect(backend.match(() => true)).toEqual([]);
        expect(expired).toHaveLength(1);
    });

    it('a sign-in request with no session ending is ignored', async () => {
        const {service, backend, spy} = setUp();
        await initialised(service, backend, OIDC);

        TestBed.inject(Dispatcher).dispatch(authEvents.signInRequested());

        expect(spy.calls).not.toContain('sign-in');
    });

    it('waits long enough for the toast to be read', () => {
        expect(SESSION_EXPIRED_NOTICE_MS).toBe(2_500);
    });
});

describe('AuthService — code review 5', () => {
    it('a 5xx proves nothing about the bearer: a gateway answered it, so the brake stays (finding 1)', async () => {
        window.sessionStorage.setItem(SIGN_IN_MARKER, String(Date.now()));
        const {service, backend, http} = setUp();
        await initialised(service, backend, OIDC);

        for (const status of [502, 503]) {
            const outcome = request(http, '/api/v1/offers');
            backend.expectOne('/api/v1/offers').flush(null, {status, statusText: 'Bad Gateway'});
            expect(outcome).toEqual([status]);
        }

        expect(window.sessionStorage.getItem(SIGN_IN_MARKER)).not.toBeNull();
        const refused = seen(authEvents.sessionRefused);
        request(http, '/api/v1/offers');
        backend.expectOne('/api/v1/offers').flush(null, UNAUTHORIZED);
        (await sent(backend, '/api/v1/offers')).flush(null, UNAUTHORIZED);
        expect(refused).toHaveLength(1);
        expect(service.phase()).toBe('refused');
    });

    it('a token lapsed on arrival does not bounce back to the identity provider: the sign-in failed (finding 2)', async () => {
        window.sessionStorage.setItem(SIGN_IN_MARKER, String(Date.now()));
        window.history.replaceState(null, '', '/?code=X&state=nonce%3B%252Fpipeline&session_state=s');
        const stub = oauth();
        stub.valid = false;
        const {service, backend, spy} = setUp(stub);
        const failed = seen(authEvents.signInFailed);

        await initialised(service, backend, OIDC);

        expect(service.phase()).toBe('failed');
        expect(failed).toHaveLength(1);
        expect(spy.calls).not.toContain('sign-in');
    });

    it('a plain load right after a sign-in still signs in: only a callback can have failed (finding 2)', async () => {
        window.sessionStorage.setItem(SIGN_IN_MARKER, String(Date.now()));
        window.history.replaceState(null, '', '/pipeline/3');
        const stub = oauth();
        stub.valid = false;
        const {service, backend, spy} = setUp(stub);

        void service.initialise();
        backend.expectOne('/api/v1/auth-config').flush(OIDC);
        await vi.waitFor(() => expect(spy.calls).toContain('sign-in'));

        expect(service.phase()).toBe('leaving');
        expect(spy.signInStates).toEqual(['/pipeline/3']);
    });

    it('a sign-in that throws before it leaves renders the page with the failure, and counts as no round trip (finding 3; code review 6, finding 8)', async () => {
        const stub = oauth();
        stub.valid = false;
        stub.initCodeFlow = () => {
            throw new Error("loginUrl  must use HTTPS (with TLS)");
        };
        const {service, backend} = setUp(stub);
        const failed = seen(authEvents.signInFailed);

        await initialised(service, backend, OIDC);

        expect(service.phase()).toBe('failed');
        expect(failed).toHaveLength(1);
        expect(window.sessionStorage.getItem(SIGN_IN_MARKER)).toBeNull();
    });

    it('a start whose sign-in the page is still here for renders with the failure rather than blank for good (final review, finding 2)', async () => {
        vi.useFakeTimers();
        window.history.replaceState(null, '', '/pipeline/3');
        const stub = oauth();
        stub.valid = false;
        const {service, backend, spy} = setUp(stub);
        const failed = seen(authEvents.signInFailed);
        const stalled = seen(authEvents.signInStalled);
        let resolved = false;

        void service.initialise().then(() => (resolved = true));
        backend.expectOne('/api/v1/auth-config').flush(OIDC);
        await vi.advanceTimersByTimeAsync(SIGN_IN_STALLED_MS - 1);
        expect(resolved).toBe(false);
        expect(service.phase()).toBe('leaving');
        await vi.advanceTimersByTimeAsync(1);

        expect(resolved).toBe(true);
        expect(service.phase()).toBe('failed');
        expect(failed).toHaveLength(1);
        expect(stalled).toEqual([]);
        TestBed.inject(Dispatcher).dispatch(authEvents.signInRequested());
        expect(spy.signInStates).toEqual(['/pipeline/3', '/pipeline/3']);
    });

    it('a running page still here after a sign-in says so and offers it again, and leaves the phase alone (code review 11, finding 1)', async () => {
        window.history.replaceState(null, '', '/pipeline/3');
        const {service, backend, http, spy} = setUp();
        await initialised(service, backend, OIDC);
        vi.useFakeTimers();
        const stalled = seen(authEvents.signInStalled);
        spy.valid = false;
        spy.refresh = null;
        request(http, '/api/v1/offers');
        await vi.advanceTimersByTimeAsync(SESSION_EXPIRED_NOTICE_MS);
        expect(spy.signInStates).toEqual(['/pipeline/3']);

        await vi.advanceTimersByTimeAsync(SIGN_IN_STALLED_MS);

        expect(stalled).toHaveLength(1);
        expect(service.phase()).toBe('leaving');
        TestBed.inject(Dispatcher).dispatch(authEvents.signInRequested());
        expect(spy.signInStates).toEqual(['/pipeline/3', '/pipeline/3']);
        await vi.advanceTimersByTimeAsync(SIGN_IN_STALLED_MS);
        expect(stalled).toHaveLength(2);
    });

    it("a logout forgets the kept chat question, which is the person's own text (final security review)", () => {
        const {service} = setUp();
        keepUnsent('What came in?');

        service.logout();

        expect(unsentQuestion()).toBeNull();
    });

    it("a 4xx of a proxy's own proves nothing about the bearer either (code review 6, finding 6)", async () => {
        window.sessionStorage.setItem(SIGN_IN_MARKER, String(Date.now()));
        const {service, backend, http} = setUp();
        await initialised(service, backend, OIDC);

        for (const status of [413, 429]) {
            const outcome = request(http, '/api/v1/offers');
            backend.expectOne('/api/v1/offers').flush(null, {status, statusText: 'Proxy'});
            expect(outcome).toEqual([status]);
        }

        expect(window.sessionStorage.getItem(SIGN_IN_MARKER)).not.toBeNull();
    });

    it('the header and the download progress of a 401 are no success: the brake holds (code review 6, finding 4)', async () => {
        window.sessionStorage.setItem(SIGN_IN_MARKER, String(Date.now()));
        const {service, backend, http} = setUp();
        await initialised(service, backend, OIDC);
        const accepted = seen(authEvents.sessionAccepted);

        http.get('/api/v1/offers', {reportProgress: true, observe: 'events'}).subscribe({error: () => undefined});
        const first = backend.expectOne('/api/v1/offers');
        first.event(new HttpHeaderResponse({status: 401, statusText: 'Unauthorized'}));
        first.event({type: HttpEventType.DownloadProgress, loaded: 10});
        first.flush({title: 'Unauthorized'}, UNAUTHORIZED);
        const replay = await sent(backend, '/api/v1/offers');
        replay.event(new HttpHeaderResponse({status: 401, statusText: 'Unauthorized'}));
        replay.event({type: HttpEventType.DownloadProgress, loaded: 10});
        replay.flush({title: 'Unauthorized'}, UNAUTHORIZED);

        expect(service.phase()).toBe('refused');
        expect(accepted).toEqual([]);
        expect(window.sessionStorage.getItem(SIGN_IN_MARKER)).not.toBeNull();
    });

    it('a caller that takes only the first value still lifts the brake (finding 6)', async () => {
        window.sessionStorage.setItem(SIGN_IN_MARKER, String(Date.now()));
        const {service, backend, http} = setUp();
        await initialised(service, backend, OIDC);

        const answer = firstValueFrom(http.get('/api/v1/offers'));
        backend.expectOne('/api/v1/offers').flush([]);
        await answer;

        expect(window.sessionStorage.getItem(SIGN_IN_MARKER)).toBeNull();
    });

    it('the error an identity provider sent back does not come back after the next sign-in (finding 7)', async () => {
        // As the library leaves a callback it consumed: `code` and `state` gone, the error still there.
        window.history.replaceState(null, '', '/pipeline?error=login_required&error_description=Login%20required');
        const stub = oauth();
        stub.valid = false;
        stub.exchange = 'fail';
        const {service, backend, spy} = setUp(stub);

        void service.initialise();
        backend.expectOne('/api/v1/auth-config').flush(OIDC);
        await vi.waitFor(() => expect(spy.calls).toContain('sign-in'));

        expect(spy.signInStates).toEqual(['/pipeline']);
    });
});

describe('returnPath', () => {
    it('accepts a same-origin path and nothing else', () => {
        expect(returnPath(encodeURIComponent('/shortlist/5?q=java#ad'))).toBe('/shortlist/5?q=java#ad');
        expect(returnPath('/')).toBe('/');
        expect(returnPath(encodeURIComponent('https://evil.example/'))).toBeNull();
        expect(returnPath(encodeURIComponent('//evil.example'))).toBeNull();
        expect(returnPath(encodeURIComponent('/\\evil.example'))).toBeNull();
        expect(returnPath('javascript:alert(1)')).toBeNull();
        expect(returnPath('%E0%A4%A')).toBeNull();
        expect(returnPath('')).toBeNull();
        expect(returnPath(null)).toBeNull();
    });
});

describe('signedInUser', () => {
    it('leads with the name, then the given and family name, then the username or address', async () => {
        const {signedInUser} = await import('./auth.service');
        const issuer = ISSUER;

        expect(signedInUser({name: 'Ada Lovelace', email: 'ada@example.com', exp: 1_800_000_000}, issuer)).toEqual({
            name: 'Ada Lovelace',
            username: null,
            email: 'ada@example.com',
            emailVerified: null,
            issuer,
            expiresAt: new Date(1_800_000_000 * 1000),
        });
        expect(signedInUser({given_name: 'Ada', family_name: 'Lovelace'}, issuer)?.name).toBe('Ada Lovelace');
        expect(signedInUser({preferred_username: 'ada'}, issuer)?.name).toBe('ada');
        expect(signedInUser({email: 'ada@example.com'}, issuer)?.name).toBe('ada@example.com');
        expect(signedInUser(null, issuer)).toBeNull();
    });
});
