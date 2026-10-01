import {HttpClient, provideHttpClient, withInterceptors} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {AuthService, SessionEnded} from './auth.service';
import {bearerInterceptor} from './bearer.interceptor';

interface AuthStub extends Partial<AuthService> {
    refusals: [number, boolean][];
}

/**
 * The four things the interceptor asks of the service. `token` is the synchronous fast path,
 * `bearer` the renewal behind it, `refused` the one rule for an ended session — recorded here,
 * because the rule itself is `AuthService`'s and specified there.
 */
function auth(oidc: boolean, token: string | null, bearer: () => Promise<string> = () => Promise.reject(new SessionEnded())): AuthStub {
    const refusals: [number, boolean][] = [];
    return {
        refusals,
        isOidc: () => oidc,
        token: () => token,
        bearer,
        refused: (status: number, sentBearer: boolean) => {
            refusals.push([status, sentBearer]);
        },
    };
}

function setUp(spy: AuthStub): {http: HttpClient; backend: HttpTestingController; spy: AuthStub} {
    TestBed.configureTestingModule({
        providers: [
            provideHttpClient(withInterceptors([bearerInterceptor])),
            provideHttpClientTesting(),
            {provide: AuthService, useValue: spy},
        ],
    });
    return {http: TestBed.inject(HttpClient), backend: TestBed.inject(HttpTestingController), spy};
}

const UNAUTHORIZED = {status: 401, statusText: 'Unauthorized'};

describe('bearerInterceptor', () => {
    it('sends the token on an API request', () => {
        const {http, backend} = setUp(auth(true, 'a-token'));

        http.get('/api/v1/offers').subscribe();

        expect(backend.expectOne('/api/v1/offers').request.headers.get('Authorization')).toBe('Bearer a-token');
        backend.verify();
    });

    it('under none sends no header at all, synchronously, as before any of this existed', () => {
        const {http, backend, spy} = setUp(auth(false, null));

        http.get('/api/v1/offers').subscribe({error: () => undefined});
        const request = backend.expectOne('/api/v1/offers');
        expect(request.request.headers.has('Authorization')).toBe(false);
        request.flush(null, UNAUTHORIZED);

        expect(spy.refusals).toEqual([]);
        backend.verify();
    });

    it('leaves a request that is not ours alone', () => {
        // A whitelist and not a blocklist: the operator's token is not handed to whatever
        // third party a later feature decides to fetch from.
        const {http, backend} = setUp(auth(true, 'a-token'));

        http.get('https://example.invalid/thing').subscribe();

        expect(backend.expectOne('https://example.invalid/thing').request.headers.has('Authorization')).toBe(false);
        backend.verify();
    });

    it('under oidc with a lapsed token, waits for the renewal and sends the renewed bearer', async () => {
        let renewed: (token: string) => void = () => undefined;
        const {http, backend} = setUp(auth(true, null, () => new Promise((resolve) => (renewed = resolve))));

        http.get('/api/v1/offers').subscribe();
        expect(backend.match('/api/v1/offers')).toEqual([]);
        renewed('renewed-token');

        // Held, not sent anonymously: nothing went out before the renewal answered.
        await vi.waitFor(() => backend.expectOne('/api/v1/offers'));
    });

    it('carries the renewed bearer once the renewal answers', async () => {
        const {http, backend} = setUp(auth(true, null, () => Promise.resolve('renewed-token')));

        http.get('/api/v1/offers').subscribe();

        await vi.waitFor(() => expect(backend.expectOne('/api/v1/offers').request.headers.get('Authorization')).toBe('Bearer renewed-token'));
    });

    it('under oidc with no renewal possible, sends nothing and fails the request as a 401', async () => {
        const {http, backend} = setUp(auth(true, null));
        const errors: number[] = [];

        http.get('/api/v1/offers').subscribe({error: (error: {status: number}) => errors.push(error.status)});

        await vi.waitFor(() => expect(errors).toEqual([401]));
        backend.verify();
    });

    describe('a refusal (ISC-503)', () => {
        it('hands every refused status to the one rule, with whether the bearer went along, and still hands the error on', () => {
            const {http, backend, spy} = setUp(auth(true, 'a-token'));
            const errors: number[] = [];

            http.get('/api/v1/offers').subscribe({error: (error: {status: number}) => errors.push(error.status)});
            http.get('/api/v1/rules').subscribe({error: () => undefined});
            backend.expectOne('/api/v1/offers').flush(null, UNAUTHORIZED);
            backend.expectOne('/api/v1/rules').flush(null, {status: 403, statusText: 'Forbidden'});

            expect(spy.refusals).toEqual([
                [401, true],
                [403, true],
            ]);
            expect(errors).toEqual([401]);
        });

        it('leaves the auth-config request alone, which answers without a token by design', () => {
            const {http, backend, spy} = setUp(auth(true, 'a-token'));

            http.get('/api/v1/auth-config').subscribe({error: () => undefined});
            const request = backend.expectOne('/api/v1/auth-config');
            expect(request.request.headers.has('Authorization')).toBe(false);
            request.flush(null, UNAUTHORIZED);

            expect(spy.refusals).toEqual([]);
        });
    });
});
