import {HttpClient, provideHttpClient, withInterceptors} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {AuthService} from './auth.service';
import {bearerInterceptor} from './bearer.interceptor';

/** Only the two methods the interceptor asks for, the second one counted. */
function auth(token: string | null): Partial<AuthService> & {expired: ReturnType<typeof vi.fn>} {
    const expired = vi.fn();
    return {token: () => token, sessionExpired: expired, expired};
}

function setUp(token: string | null): {http: HttpClient; backend: HttpTestingController; spy: ReturnType<typeof auth>} {
    const spy = auth(token);
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
        const {http, backend} = setUp('a-token');

        http.get('/api/v1/offers').subscribe();

        expect(backend.expectOne('/api/v1/offers').request.headers.get('Authorization')).toBe(
            'Bearer a-token',
        );
        backend.verify();
    });

    it('sends no header at all when there is no token', () => {
        // The shipped default: `auth: none`, nothing to attach, and the request has to go
        // out exactly as it did before any of this existed.
        const {http, backend} = setUp(null);

        http.get('/api/v1/offers').subscribe();

        expect(backend.expectOne('/api/v1/offers').request.headers.has('Authorization')).toBe(false);
        backend.verify();
    });

    it('leaves a request that is not ours alone', () => {
        // A whitelist and not a blocklist: the operator's token is not handed to whatever
        // third party a later feature decides to fetch from.
        const {http, backend} = setUp('a-token');

        http.get('https://example.invalid/thing').subscribe();

        expect(
            backend.expectOne('https://example.invalid/thing').request.headers.has('Authorization'),
        ).toBe(false);
        backend.verify();
    });

    describe('a 401 (ISC-503)', () => {
        it('reports the session as expired when the request carried the bearer, and still hands the error on', () => {
            const {http, backend, spy} = setUp('a-token');
            const errors: number[] = [];

            http.get('/api/v1/offers').subscribe({error: (error: {status: number}) => errors.push(error.status)});
            backend.expectOne('/api/v1/offers').flush(null, UNAUTHORIZED);

            expect(spy.expired).toHaveBeenCalledTimes(1);
            // The screen still reports its own failure; the interceptor only adds the sign-in.
            expect(errors).toEqual([401]);
        });

        it('reports nothing for a request that carried no bearer', () => {
            // Under `none` there is never a bearer, so a 401 there is the API's business, not a session.
            const {http, backend, spy} = setUp(null);

            http.get('/api/v1/offers').subscribe({error: () => undefined});
            backend.expectOne('/api/v1/offers').flush(null, UNAUTHORIZED);

            expect(spy.expired).not.toHaveBeenCalled();
        });

        it('reports nothing for the auth-config request, which answers without a token', () => {
            const {http, backend, spy} = setUp('a-token');

            http.get('/api/v1/auth-config').subscribe({error: () => undefined});
            backend.expectOne('/api/v1/auth-config').flush(null, UNAUTHORIZED);

            expect(spy.expired).not.toHaveBeenCalled();
        });

        it('reports nothing for any other refusal', () => {
            // A 403 is a signed-in person without the right, not a session that ended.
            const {http, backend, spy} = setUp('a-token');

            http.get('/api/v1/offers').subscribe({error: () => undefined});
            http.get('/api/v1/rules').subscribe({error: () => undefined});
            backend.expectOne('/api/v1/offers').flush(null, {status: 403, statusText: 'Forbidden'});
            backend.expectOne('/api/v1/rules').flush(null, {status: 500, statusText: 'Server Error'});

            expect(spy.expired).not.toHaveBeenCalled();
        });
    });
});
