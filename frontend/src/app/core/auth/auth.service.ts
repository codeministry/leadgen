import {inject, Injectable, signal} from '@angular/core';
import {AuthConfig as OidcConfig, OAuthService} from 'angular-oauth2-oidc';
import {firstValueFrom} from 'rxjs';
import {AuthConfig, AuthConfigApi} from './auth-config.api';

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
 * <p>**The token lives in memory only.** `angular-oauth2-oidc` would keep it in
 * `sessionStorage` by default; a token in storage survives an XSS long enough to be read,
 * and nothing here needs it to survive a reload. A reload re-runs the silent flow instead.
 */
@Injectable({providedIn: 'root'})
export class AuthService {
    private readonly oauth = inject(OAuthService);
    private readonly api = inject(AuthConfigApi);

    private readonly enabled = signal(false);

    /** True once the mode is known and, under `oidc`, somebody is actually signed in. */
    readonly authenticated = signal(false);

    /** The signed-in subject's display name, or null under `none`. */
    readonly name = signal<string | null>(null);

    /**
     * Runs once, before the first route, and **never rejects**.
     *
     * <p>An initializer that rejects stops Angular bootstrapping, so every failure in here
     * is a blank page rather than a screen with an error on it. Two of them are reachable
     * and neither is this function's business to have an opinion about: an API that is
     * down, and an identity provider that is. Both end as "not signed in", the interceptor
     * sends no header, and the screens report their own failures the way they already do.
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
        this.enabled.set(true);
        this.oauth.configure(this.oidcConfig(config.issuer, config.clientId));
        try {
            await this.oauth.loadDiscoveryDocumentAndLogin();
            this.authenticated.set(this.oauth.hasValidAccessToken());
            this.name.set((this.oauth.getIdentityClaims() as {name?: string} | null)?.name ?? null);
        } catch {
            // Deliberately swallowed and surfaced as "not signed in": an unreachable
            // issuer is not a reason to leave the operator looking at nothing.
            this.authenticated.set(false);
        }
    }

    /** The bearer to send, or null when there is nothing to send. */
    token(): string | null {
        if (!this.enabled()) {
            return null;
        }
        return this.oauth.hasValidAccessToken() ? this.oauth.getAccessToken() : null;
    }

    logout(): void {
        if (this.enabled()) {
            this.oauth.logOut();
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
            showDebugInformation: false,
            // `remoteOnly` and not `false`: the dev server is plain HTTP on localhost and
            // has to work, but anywhere else a code flow over HTTP puts the token on the
            // wire. `false` would have allowed exactly that, silently, on the one
            // deployment where it matters.
            requireHttps: 'remoteOnly',
        };
    }
}
