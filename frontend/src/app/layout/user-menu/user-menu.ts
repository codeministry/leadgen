import {ChangeDetectionStrategy, Component, computed, effect, inject, Injector, signal} from '@angular/core';
import {toSignal} from '@angular/core/rxjs-interop';
import {NgTemplateOutlet} from '@angular/common';
import {TranslocoPipe, TranslocoService} from '@jsverse/transloco';
import {AuthService} from '@core/auth/auth.service';
import {SignedInState} from '@core/auth/signed-in.state';
import {gravatarUrl, initials} from '@core/auth/gravatar';
import {Icon} from '@shared/icon/icon';

/**
 * Who is signed in, and the way out: an avatar button in the header with a popover of the ID
 * token's claims and a logout.
 *
 * <p>Rendered by the header only under `oidc` with somebody signed in; under `none` there is no
 * person to show and nothing to log out of. The avatar comes from gravatar.com when
 * `GRAVATAR_ENABLED` allows it, by a hash of the address, and a failed load — no Gravatar image,
 * no network, a CSP that refuses it — falls back to the initials without a broken image.
 */
@Component({
    selector: 'lg-user-menu',
    imports: [Icon, NgTemplateOutlet, TranslocoPipe],
    templateUrl: './user-menu.html',
    styleUrl: './user-menu.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UserMenu {
    private readonly signedIn = inject(SignedInState);
    private readonly injector = inject(Injector);
    private readonly transloco = inject(TranslocoService);
    private readonly lang = toSignal(this.transloco.langChanges$, {initialValue: this.transloco.getActiveLang()});

    protected readonly user = this.signedIn.user;
    protected readonly initials = computed(() => initials(this.user()?.name));

    /** The Gravatar URL once hashed, null while hashing, when off, or when there is no address. */
    protected readonly avatarUrl = signal<string | null>(null);

    /** Set by the image's own error event: the initials take its place for this address. */
    protected readonly avatarFailed = signal(false);

    /** Mirrored from the panel's `toggle` event, as the settings popover does it. */
    protected readonly open = signal(false);

    protected readonly buttonLabel = computed(() => {
        this.lang();
        const name = this.user()?.name;
        return name ? this.transloco.translate('user.menuFor', {name}) : this.transloco.translate('user.menu');
    });

    /** The issuer's host, which says which realm this is without the whole URL. */
    protected readonly issuerHost = computed(() => {
        const issuer = this.user()?.issuer;
        if (!issuer) return null;
        try {
            const url = new URL(issuer);
            return url.host + url.pathname.replace(/\/$/, '');
        } catch {
            return issuer;
        }
    });

    protected readonly expiresAt = computed(() => {
        const at = this.user()?.expiresAt;
        return at ? at.toLocaleTimeString(this.lang(), {hour: '2-digit', minute: '2-digit'}) : null;
    });

    constructor() {
        effect(() => {
            const email = this.signedIn.gravatar() ? this.user()?.email : null;
            this.avatarFailed.set(false);
            this.avatarUrl.set(null);
            if (!email) return;
            void gravatarUrl(email).then((url) => this.avatarUrl.set(url));
        });
    }

    protected onToggle(event: Event): void {
        this.open.set((event as ToggleEvent).newState === 'open');
    }

    /** Asked for on the click, so rendering the menu never constructs the OIDC client itself. */
    protected logout(): void {
        this.injector.get(AuthService).logout();
    }
}
