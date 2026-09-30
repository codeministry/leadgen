import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {OAuthService} from 'angular-oauth2-oidc';
import {AuthConfig} from './auth-config.api';
import {AuthService} from './auth.service';
import {SignedInState} from './signed-in.state';

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
        answer(backend, {mode: 'none', issuer: null, clientId: null, gravatar: false});
        await done;

        expect(spy.calls).toEqual([]);
        expect(service.token()).toBeNull();
        expect(service.authenticated()).toBe(false);
        backend.verify();
    });

    it('configures the flow and signs in when the instance says oidc', async () => {
        const {service, backend, spy} = setUp();

        const done = service.initialise();
        answer(backend, {mode: 'oidc', issuer: 'https://auth.example/realms/x', clientId: 'leadgen-web', gravatar: true});
        await done;

        expect(spy.calls).toEqual(['configure', 'login']);
        expect(service.authenticated()).toBe(true);
        expect(service.token()).toBe('a-token');
        expect(service.name()).toBe('Somebody');
        // The user menu reads these two: the claims as a user, and the avatar switch from the config.
        const signedIn = TestBed.inject(SignedInState);
        expect(signedIn.user()).toMatchObject({name: 'Somebody', issuer: 'https://auth.example/realms/x'});
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

        const done = service.initialise();
        answer(backend, {mode: 'oidc', issuer: null, clientId: null, gravatar: true});
        await done;

        expect(spy.calls).toEqual([]);
        expect(service.token()).toBeNull();
        backend.verify();
    });
});

describe('signedInUser', () => {
    it('leads with the name, then the given and family name, then the username or address', async () => {
        const {signedInUser} = await import('./auth.service');
        const issuer = 'https://auth.example/realms/x';

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
