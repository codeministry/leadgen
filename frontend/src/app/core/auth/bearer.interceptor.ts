import {HttpInterceptorFn} from '@angular/common/http';
import {inject} from '@angular/core';
import {AuthService} from './auth.service';

/**
 * Adds the bearer, and only where it belongs.
 *
 * <p>**Same-origin API paths only.** A token attached to every outgoing request would
 * hand the operator's credentials to whatever third party a future feature fetches from,
 * and the rule that prevents that has to be a whitelist rather than a blocklist.
 *
 * <p>**Under `none` a request passes untouched and synchronously**, exactly as before any of
 * this existed. **`/api/v1/auth-config` passes untouched too**: it answers without a token by
 * design, and it is the request that decides the mode in the first place.
 *
 * <p>**Under `oidc` the request goes through `AuthService.call`**, the one path it shares with
 * the chat stream: a lapsed token is renewed first, a 401 gets one renewal and one replay, and
 * nothing goes out without the bearer. The error still reaches the caller; the screen reports its
 * own failure the way it always did.
 */
const AUTH_CONFIG = '/api/v1/auth-config';

export const bearerInterceptor: HttpInterceptorFn = (request, next) => {
    if (!request.url.startsWith('/api/') || request.url === AUTH_CONFIG) {
        return next(request);
    }
    const auth = inject(AuthService);
    if (!auth.isOidc()) {
        return next(request);
    }
    return auth.call(request.url, (token) => next(request.clone({setHeaders: {Authorization: `Bearer ${token}`}})));
};
