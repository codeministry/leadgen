import {HttpClient, provideHttpClient, withInterceptors} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {Observable} from 'rxjs';
import {AuthService} from './auth.service';
import {bearerInterceptor} from './bearer.interceptor';

interface AuthStub extends Partial<AuthService> {
    /** The URL of every request handed to `call`. */
    called: string[];
}

/**
 * The two things the interceptor asks of the service: whether this instance signs in, and `call`,
 * the one way a request goes out under oidc. This one sends once with a fixed token; the renewal,
 * the replay and the ended session are `AuthService`'s and specified there, through this interceptor.
 */
function auth(oidc: boolean): AuthStub {
    const called: string[] = [];
    return {
        called,
        isOidc: () => oidc,
        call: <T>(url: string, attempt: (token: string) => Observable<T>): Observable<T> => {
            called.push(url);
            return attempt('a-token');
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
    it('under oidc hands an API request to AuthService.call, which sends it with the bearer', () => {
        const {http, backend, spy} = setUp(auth(true));

        http.get('/api/v1/offers').subscribe();

        expect(spy.called).toEqual(['/api/v1/offers']);
        expect(backend.expectOne('/api/v1/offers').request.headers.get('Authorization')).toBe('Bearer a-token');
        backend.verify();
    });

    it('hands the error on to the caller: the screen reports its own failure', () => {
        const {http, backend} = setUp(auth(true));
        const errors: number[] = [];

        http.get('/api/v1/offers').subscribe({error: (error: {status: number}) => errors.push(error.status)});
        backend.expectOne('/api/v1/offers').flush(null, UNAUTHORIZED);

        expect(errors).toEqual([401]);
    });

    it('under none sends no header at all, synchronously, as before any of this existed', () => {
        const {http, backend, spy} = setUp(auth(false));

        http.get('/api/v1/offers').subscribe({error: () => undefined});
        const request = backend.expectOne('/api/v1/offers');
        expect(request.request.headers.has('Authorization')).toBe(false);
        request.flush(null, UNAUTHORIZED);

        expect(spy.called).toEqual([]);
        backend.verify();
    });

    it('leaves a request that is not ours alone', () => {
        // A whitelist and not a blocklist: the operator's token is not handed to whatever
        // third party a later feature decides to fetch from.
        const {http, backend, spy} = setUp(auth(true));

        http.get('https://example.invalid/thing').subscribe();

        expect(backend.expectOne('https://example.invalid/thing').request.headers.has('Authorization')).toBe(false);
        expect(spy.called).toEqual([]);
        backend.verify();
    });

    it('leaves the auth-config request alone, which answers without a token by design', () => {
        const {http, backend, spy} = setUp(auth(true));

        http.get('/api/v1/auth-config').subscribe({error: () => undefined});
        const request = backend.expectOne('/api/v1/auth-config');
        expect(request.request.headers.has('Authorization')).toBe(false);
        request.flush(null, UNAUTHORIZED);

        expect(spy.called).toEqual([]);
    });
});
