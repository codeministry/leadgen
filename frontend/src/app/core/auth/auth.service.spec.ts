import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {OAuthService} from 'angular-oauth2-oidc';
import {AuthConfig} from './auth-config.api';
import {AuthService} from './auth.service';

/** Every method this service reaches for, and a record of what was called. */
function oauth(): Partial<OAuthService> & {calls: string[]} {
    const calls: string[] = [];
    return {
        calls,
        configure: () => calls.push('configure'),
        loadDiscoveryDocumentAndLogin: async () => {
            calls.push('login');
            return true;
        },
        hasValidAccessToken: () => true,
        getAccessToken: () => 'a-token',
        getIdentityClaims: () => ({name: 'Somebody'}),
    } as Partial<OAuthService> & {calls: string[]};
}

function setUp(): {service: AuthService; backend: HttpTestingController; spy: ReturnType<typeof oauth>} {
    const spy = oauth();
    TestBed.configureTestingModule({
        providers: [provideHttpClient(), provideHttpClientTesting(), {provide: OAuthService, useValue: spy}],
    });
    return {service: TestBed.inject(AuthService), backend: TestBed.inject(HttpTestingController), spy};
}

function answer(backend: HttpTestingController, config: AuthConfig): void {
    backend.expectOne('/api/v1/auth-config').flush(config);
}

describe('AuthService', () => {
    it('does nothing at all when the instance runs without authentication', async () => {
        // The shipped default. No discovery request, no redirect, and nothing to attach —
        // the tool has to run on one machine with no identity provider anywhere.
        const {service, backend, spy} = setUp();

        const done = service.initialise();
        answer(backend, {mode: 'none', issuer: null, clientId: null});
        await done;

        expect(spy.calls).toEqual([]);
        expect(service.token()).toBeNull();
        expect(service.authenticated()).toBe(false);
        backend.verify();
    });

    it('configures the flow and signs in when the instance says oidc', async () => {
        const {service, backend, spy} = setUp();

        const done = service.initialise();
        answer(backend, {mode: 'oidc', issuer: 'https://auth.example/realms/x', clientId: 'leadgen-web'});
        await done;

        expect(spy.calls).toEqual(['configure', 'login']);
        expect(service.authenticated()).toBe(true);
        expect(service.token()).toBe('a-token');
        expect(service.name()).toBe('Somebody');
        backend.verify();
    });

    it('treats a mode of oidc with no issuer as nothing to do', async () => {
        // The server refuses that combination at load, so this is the belt to that brace:
        // a half-answer must not put the browser into a redirect loop against undefined.
        const {service, backend, spy} = setUp();

        const done = service.initialise();
        answer(backend, {mode: 'oidc', issuer: null, clientId: null});
        await done;

        expect(spy.calls).toEqual([]);
        expect(service.token()).toBeNull();
        backend.verify();
    });
});
