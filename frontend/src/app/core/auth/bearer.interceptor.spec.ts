import {HttpClient, provideHttpClient, withInterceptors} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {AuthService} from './auth.service';
import {bearerInterceptor} from './bearer.interceptor';

/** Only the one method the interceptor asks for. */
function auth(token: string | null): Partial<AuthService> {
    return {token: () => token};
}

function setUp(token: string | null): {http: HttpClient; backend: HttpTestingController} {
    TestBed.configureTestingModule({
        providers: [
            provideHttpClient(withInterceptors([bearerInterceptor])),
            provideHttpClientTesting(),
            {provide: AuthService, useValue: auth(token)},
        ],
    });
    return {http: TestBed.inject(HttpClient), backend: TestBed.inject(HttpTestingController)};
}

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
});
