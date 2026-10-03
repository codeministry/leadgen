import {HttpErrorResponse, HttpInterceptorFn, HttpRequest, HttpStatusCode} from '@angular/common/http';
import {inject} from '@angular/core';
import {catchError, defer, Observable, switchMap, tap, throwError} from 'rxjs';
import {AuthService, SessionEnded} from './auth.service';

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
 * <p>**Under `oidc` nothing goes out without the bearer.** A valid token is attached at once; a
 * lapsed one (a laptop that slept through the renewal) is renewed first and the request waits
 * for it (`AuthService.bearer`). When no token can be had the request is not sent at all: it
 * fails as a 401 here, and the ended session is already reported.
 *
 * <p>**Whether an answer ended the session is `AuthService.refused`'s call**, the one rule this
 * interceptor and the chat stream share. The error still reaches the caller; the screen reports
 * its own failure the way it always did.
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
    const send = (token: string) =>
        next(request.clone({setHeaders: {Authorization: `Bearer ${token}`}})).pipe(
            tap({
                error: (error: unknown) => {
                    if (error instanceof HttpErrorResponse) auth.refused(error.status, true);
                },
            }),
        );
    const token = auth.token();
    if (token !== null) {
        return send(token);
    }
    return defer(() => auth.bearer()).pipe(
        catchError((error: unknown) => (error instanceof SessionEnded ? notSent(request) : throwError(() => error))),
        switchMap(send),
    );
};

/** The 401 a request fails with when the session ended before it could be sent. */
function notSent(request: HttpRequest<unknown>): Observable<never> {
    return throwError(
        () => new HttpErrorResponse({status: HttpStatusCode.Unauthorized, statusText: 'Session ended', url: request.url}),
    );
}
