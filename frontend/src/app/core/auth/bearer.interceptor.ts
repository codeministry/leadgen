import {HttpErrorResponse, HttpInterceptorFn, HttpStatusCode} from '@angular/common/http';
import {inject} from '@angular/core';
import {tap} from 'rxjs';
import {AuthService} from './auth.service';

/**
 * Adds the bearer, and only where it belongs.
 *
 * <p>**Same-origin API paths only.** A token attached to every outgoing request would
 * hand the operator's credentials to whatever third party a future feature fetches from,
 * and the rule that prevents that has to be a whitelist rather than a blocklist.
 *
 * <p>**`/api/v1/auth-config` is deliberately included and harmless**: under `none` there
 * is no token to add, and under `oidc` the endpoint ignores the header. Excluding it
 * would be a second rule to keep in step with the server's own list for no gain.
 *
 * <p>**A 401 on a request that carried the bearer ends the session** (`AuthService.sessionExpired`,
 * which says so once and signs in again). Only with a bearer: under `none` there is none, and a
 * 401 there is the API's business, not a session. Never for `auth-config`, which answers
 * without a token by design. The error still reaches the caller; the screen reports its own
 * failure the way it always did.
 */
const AUTH_CONFIG = '/api/v1/auth-config';

export const bearerInterceptor: HttpInterceptorFn = (request, next) => {
    if (!request.url.startsWith('/api/')) {
        return next(request);
    }
    const auth = inject(AuthService);
    const token = auth.token();
    if (!token) {
        return next(request);
    }
    const sent = next(request.clone({setHeaders: {Authorization: `Bearer ${token}`}}));
    if (request.url === AUTH_CONFIG) {
        return sent;
    }
    return sent.pipe(
        tap({
            error: (error: unknown) => {
                if (error instanceof HttpErrorResponse && error.status === HttpStatusCode.Unauthorized) {
                    auth.sessionExpired();
                }
            },
        }),
    );
};
