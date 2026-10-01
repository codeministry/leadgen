import {HttpClient, HttpParams, provideHttpClient, withInterceptors} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {Events} from '@ngrx/signals/events';
import {AuthConfig as OidcConfig, OAuthErrorEvent, OAuthEvent, OAuthService, OAuthStorage} from 'angular-oauth2-oidc';
import {Subject} from 'rxjs';
import {AuthConfig} from './auth-config.api';
import {authEvents} from './auth.events';
import {AuthService, returnPath, SESSION_EXPIRED_NOTICE_MS} from './auth.service';
import {bearerInterceptor} from './bearer.interceptor';
import {provideOidcClient} from './oidc-client';
import {SignedInState} from './signed-in.state';

const ISSUER = 'https://auth.example/realms/x';
const OIDC: AuthConfig = {mode: 'oidc', issuer: ISSUER, clientId: 'leadgen-web', gravatar: true};
const NONE: AuthConfig = {mode: 'none', issuer: null, clientId: null, gravatar: false};

/** Every method this service reaches for, a record of what was called, and the library's event stream. */
interface Stub extends Partial<OAuthService> {
    calls: string[];
    config: OidcConfig | null;
    loginOptions: unknown;
    signInStates: string[];
    stream: Subject<OAuthEvent>;
    loggedIn: boolean;
}

function oauth(): Stub {
    const stream = new Subject<OAuthEvent>();
    const stub: Stub = {
        calls: [],
        config: null,
        loginOptions: null,
        signInStates: [],
        stream,
        loggedIn: true,
        state: '',
        events: stream.asObservable(),
        configure: (config: OidcConfig) => {
            stub.config = config;
            stub.calls.push('configure');
        },
        loadDiscoveryDocumentAndLogin: async (options) => {
            stub.loginOptions = options;
            stub.calls.push('login');
            return stub.loggedIn;
        },
        setupAutomaticSilentRefresh: () => {
            stub.calls.push('refresh');
        },
        stopAutomaticRefresh: () => {
            stub.calls.push('stop');
        },
        initCodeFlow: (additionalState?: string) => {
            stub.signInStates.push(additionalState ?? '');
            stub.calls.push('sign-in');
        },
        hasValidAccessToken: () => true,
        getAccessToken: () => 'a-token',
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

function answer(backend: HttpTestingController, config: AuthConfig): void {
    backend.expectOne('/api/v1/auth-config').flush(config);
}

async function initialised(service: AuthService, backend: HttpTestingController, config: AuthConfig): Promise<void> {
    const done = service.initialise();
    answer(backend, config);
    await done;
}

/** Every `sessionExpired` the service dispatches, in order. */
function expiries(): unknown[] {
    const seen: unknown[] = [];
    TestBed.inject(Events).on(authEvents.sessionExpired).subscribe((event) => seen.push(event));
    return seen;
}

afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    window.history.replaceState(null, '', '/');
});

describe('AuthService', () => {
    it('does nothing at all when the instance runs without authentication', async () => {
        // The shipped default. No discovery request, no redirect, no refresh timer and nothing
        // to attach — the tool has to run on one machine with no identity provider anywhere.
        const {service, backend, spy} = setUp();

        await initialised(service, backend, NONE);

        expect(spy.calls).toEqual([]);
        expect(service.token()).toBeNull();
        expect(service.authenticated()).toBe(false);
        backend.verify();
    });

    it('configures the flow, signs in and sets up the renewal when the instance says oidc', async () => {
        const {service, backend, spy} = setUp();

        await initialised(service, backend, OIDC);

        expect(spy.calls).toEqual(['configure', 'login', 'refresh']);
        expect(service.authenticated()).toBe(true);
        expect(service.token()).toBe('a-token');
        expect(service.name()).toBe('Somebody');
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

describe('AuthService — the renewal (ISC-502)', () => {
    it('renews with the refresh-token grant: no iframe, no silent-refresh page, at 75 % of the lifetime, no offline_access', async () => {
        const {service, backend, spy} = setUp();

        await initialised(service, backend, OIDC);

        // With `useSilentRefresh: false` and the code flow, the library's automatic refresh calls
        // `refreshToken()` — a POST to the token endpoint — instead of loading a hidden iframe.
        expect(spy.calls).toContain('refresh');
        expect(spy.config).toMatchObject({responseType: 'code', useSilentRefresh: false, timeoutFactor: 0.75});
        expect(spy.config?.silentRefreshRedirectUri).toBeUndefined();
        // A refresh token bound to the SSO session is enough; an offline token would outlive the logout.
        expect(spy.config?.scope?.split(' ')).not.toContain('offline_access');
    });

    it('sets up no renewal when the sign-in sent the browser to the identity provider', async () => {
        // `false` means the redirect is under way; there is no token whose expiry could be watched.
        const stub = oauth();
        stub.loggedIn = false;
        const {service, backend, spy} = setUp(stub);

        await initialised(service, backend, OIDC);

        expect(spy.calls).toEqual(['configure', 'login']);
    });

    it('renews before expiry with the real client, and the next request carries the renewed bearer; nothing reaches storage', async () => {
        vi.useFakeTimers({now: new Date('2026-10-01T10:00:00Z'), toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'Date']});
        // Start from empty storages: specs share one worker, and another one's preference (the
        // theme, say) left behind would read as a token this test never wrote.
        window.sessionStorage.clear();
        window.localStorage.clear();
        const written = vi.spyOn(Storage.prototype, 'setItem');
        TestBed.configureTestingModule({
            providers: [provideHttpClient(withInterceptors([bearerInterceptor])), provideHttpClientTesting(), provideOidcClient()],
        });
        const lifetime = 300_000;
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
        answer(backend, OIDC);
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
        expect(service.token()).toBe('first-token');

        // Not before 75 % of the lifetime, and the library debounces the expiry by one second.
        await vi.advanceTimersByTimeAsync(lifetime * 0.75 - 1);
        expect(backend.match(`${ISSUER}/protocol/openid-connect/token`)).toEqual([]);
        await vi.advanceTimersByTimeAsync(1_000 + 1);
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

describe('AuthService — an expired session (ISC-503)', () => {
    it('a refused renewal raises one event and, after the notice, signs in again from the current URL', async () => {
        window.history.replaceState(null, '', '/shortlist/5?q=java#ad');
        const {service, backend, spy} = setUp();
        await initialised(service, backend, OIDC);
        const seen = expiries();
        vi.useFakeTimers();

        spy.stream.next(new OAuthErrorEvent('token_refresh_error', {status: 400}));

        expect(seen).toHaveLength(1);
        expect(spy.calls).not.toContain('sign-in');
        await vi.advanceTimersByTimeAsync(SESSION_EXPIRED_NOTICE_MS - 1);
        expect(spy.calls).not.toContain('sign-in');

        // A second failure while the notice stands is the same session ending, not a new one.
        spy.stream.next(new OAuthErrorEvent('token_refresh_error', {status: 400}));
        service.sessionExpired();
        await vi.advanceTimersByTimeAsync(1);

        expect(seen).toHaveLength(1);
        expect(spy.signInStates).toEqual(['/shortlist/5?q=java#ad']);
        expect(spy.calls).toContain('stop');
    });

    it('waits long enough for the toast to be read', () => {
        expect(SESSION_EXPIRED_NOTICE_MS).toBe(2_500);
    });

    it('a 401 on a request that carried the bearer does the same, once for the whole burst', async () => {
        window.history.replaceState(null, '', '/pipeline/3');
        const {service, backend, http, spy} = setUp();
        await initialised(service, backend, OIDC);
        const seen = expiries();
        vi.useFakeTimers();

        http.get('/api/v1/offers').subscribe({error: () => undefined});
        http.get('/api/v1/applications').subscribe({error: () => undefined});
        backend.expectOne('/api/v1/offers').flush(null, {status: 401, statusText: 'Unauthorized'});
        backend.expectOne('/api/v1/applications').flush(null, {status: 401, statusText: 'Unauthorized'});
        await vi.advanceTimersByTimeAsync(SESSION_EXPIRED_NOTICE_MS);

        expect(seen).toHaveLength(1);
        expect(spy.signInStates).toEqual(['/pipeline/3']);
    });

    it('does nothing under none, whatever answers 401', async () => {
        const {service, backend, http, spy} = setUp();
        await initialised(service, backend, NONE);
        const seen = expiries();
        vi.useFakeTimers();

        http.get('/api/v1/offers').subscribe({error: () => undefined});
        backend.expectOne('/api/v1/offers').flush(null, {status: 401, statusText: 'Unauthorized'});
        service.sessionExpired();
        await vi.advanceTimersByTimeAsync(SESSION_EXPIRED_NOTICE_MS * 2);

        expect(seen).toEqual([]);
        expect(spy.calls).toEqual([]);
    });

    it('asks the sign-in to come back to the URL it started from, and goes there afterwards', async () => {
        window.history.replaceState(null, '', '/shortlist/5?q=java');
        const stub = oauth();
        // What the library leaves in `state` after the code exchange: the additional state, still encoded once.
        stub.state = encodeURIComponent('/pipeline/3?view=board');
        const {service, backend} = setUp(stub);

        await initialised(service, backend, OIDC);

        expect(stub.loginOptions).toEqual({state: '/shortlist/5?q=java'});
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
