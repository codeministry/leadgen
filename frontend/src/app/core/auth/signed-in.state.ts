import {Injectable, signal} from '@angular/core';
import type {SignedInUser} from './auth.service';

/**
 * Who is signed in, apart from how they signed in.
 *
 * <p>`AuthService` writes it; the header and the user menu read it. Kept out of `AuthService`
 * so that reading it does not construct the OIDC client: the header is on every screen, and a
 * screen rendered without the OIDC wiring (a spec, the `none` default) has nobody to show.
 */
@Injectable({providedIn: 'root'})
export class SignedInState {
    /** Null under `none`, before sign-in, and when the sign-in failed. */
    readonly user = signal<SignedInUser | null>(null);

    /** Whether the user menu may load an avatar from gravatar.com (`GRAVATAR_ENABLED`). */
    readonly gravatar = signal(false);
}
