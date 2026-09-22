import {HttpClient} from '@angular/common/http';
import {inject, Injectable} from '@angular/core';
import {Observable} from 'rxjs';

/**
 * What `GET /api/v1/auth-config` returns. Kept in sync with `AuthConfigController` by hand.
 *
 * The one endpoint that answers without a token, because it is what tells the browser how
 * to get one. Under `none` the other two fields are absent and there is nothing to do.
 */
export interface AuthConfig {
    readonly mode: 'none' | 'oidc';
    readonly issuer: string | null;
    readonly clientId: string | null;
}

@Injectable({providedIn: 'root'})
export class AuthConfigApi {
    private readonly http = inject(HttpClient);

    load(): Observable<AuthConfig> {
        return this.http.get<AuthConfig>('/api/v1/auth-config');
    }
}
