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
 * <p>**`/api/v1/auth-config` is deliberately included and harmless**: under `none` there
 * is no token to add, and under `oidc` the endpoint ignores the header. Excluding it
 * would be a second rule to keep in step with the server's own list for no gain.
 */
export const bearerInterceptor: HttpInterceptorFn = (request, next) => {
    if (!request.url.startsWith('/api/')) {
        return next(request);
    }
    const token = inject(AuthService).token();
    if (!token) {
        return next(request);
    }
    return next(request.clone({setHeaders: {Authorization: `Bearer ${token}`}}));
};
