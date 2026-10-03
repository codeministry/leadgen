import {TestBed} from '@angular/core/testing';
import {OAuthStorage} from 'angular-oauth2-oidc';
import {provideOidcClient, TokenStorage} from './oidc-client';

afterEach(() => {
    vi.restoreAllMocks();
    window.sessionStorage.clear();
});

describe('TokenStorage', () => {
    it('keeps every token in memory and never in web storage', () => {
        const written = vi.spyOn(Storage.prototype, 'setItem');
        const storage = new TokenStorage();

        for (const key of ['access_token', 'refresh_token', 'id_token', 'expires_at', 'id_token_claims_obj']) {
            storage.setItem(key, `${key}-value`);
            expect(storage.getItem(key)).toBe(`${key}-value`);
        }

        expect(written).not.toHaveBeenCalled();
        expect(new TokenStorage().getItem('access_token')).toBeFalsy();
    });

    it('puts the redirect round trip in this tab’s sessionStorage, because the page is gone while the IdP answers', () => {
        // The PKCE verifier and the nonce are written before the browser leaves for the identity
        // provider and read when it comes back — a fresh page with fresh memory. Neither is a
        // credential on its own, and the library removes them once the code is exchanged.
        const storage = new TokenStorage();

        storage.setItem('PKCE_verifier', 'verifier');
        storage.setItem('nonce', 'nonce');

        expect(window.sessionStorage.getItem('PKCE_verifier')).toBe('verifier');
        expect(new TokenStorage().getItem('nonce')).toBe('nonce');
        storage.removeItem('PKCE_verifier');
        expect(window.sessionStorage.getItem('PKCE_verifier')).toBeNull();
    });

    it('is the storage the OIDC client is provided with', () => {
        TestBed.configureTestingModule({providers: [provideOidcClient()]});

        expect(TestBed.inject(OAuthStorage)).toBeInstanceOf(TokenStorage);
    });
});
