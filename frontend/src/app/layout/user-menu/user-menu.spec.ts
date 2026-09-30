import {signal} from '@angular/core';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {AuthService, SignedInUser} from '@core/auth/auth.service';
import {SignedInState} from '@core/auth/signed-in.state';
import en from '../../../../public/i18n/en.json';
import {UserMenu} from './user-menu';

const ADA: SignedInUser = {
    name: 'Ada Lovelace',
    username: 'ada',
    email: 'ada@example.com',
    emailVerified: true,
    issuer: 'https://auth.example.invalid/realms/codeministry',
    expiresAt: new Date('2026-09-30T12:34:00Z'),
};

/** The state the menu reads, and a service that records the logout. */
function fakeAuth(user: SignedInUser | null, gravatar: boolean) {
    return {state: {user: signal(user), gravatar: signal(gravatar)}, logout: vi.fn()};
}

describe('UserMenu', () => {
    let fixture: ComponentFixture<UserMenu>;
    let auth: ReturnType<typeof fakeAuth>;

    function render(user: SignedInUser | null, gravatar: boolean): void {
        auth = fakeAuth(user, gravatar);
        TestBed.configureTestingModule({
            providers: [
                {provide: SignedInState, useValue: auth.state},
                {provide: AuthService, useValue: {logout: auth.logout}},
            ],
        });
        fixture = TestBed.createComponent(UserMenu);
        fixture.detectChanges();
    }

    const el = (selector: string) => fixture.nativeElement.querySelector(selector) as HTMLElement | null;

    /** The hash is asynchronous; one turn of the loop and a render later the URL is there. */
    async function settle(): Promise<void> {
        await fixture.whenStable();
        await new Promise((resolve) => setTimeout(resolve, 0));
        fixture.detectChanges();
    }

    it('names the button after the person and lists the token claims in the panel', () => {
        render(ADA, false);

        expect(el('.user-button')?.getAttribute('aria-label')).toBe('Account: Ada Lovelace');
        expect(el('.user-button')?.getAttribute('popovertarget')).toBe('app-user');
        expect(el('.user-name')?.textContent?.trim()).toBe('Ada Lovelace');
        expect(el('.user-username')?.textContent?.trim()).toBe('ada');
        expect(el('.user-email-address')?.textContent?.trim()).toBe('ada@example.com');
        expect(el('.user-email .badge')?.textContent?.trim()).toBe(en.user.verified);
        expect(el('.user-issuer')?.textContent?.trim()).toBe('auth.example.invalid/realms/codeministry');
    });

    it('shows the initials and asks gravatar.com for nothing while Gravatar is off', async () => {
        render(ADA, false);
        await settle();

        expect(el('img')).toBeNull();
        expect(el('.user-initials')?.textContent?.trim()).toBe('AL');
    });

    it('loads the avatar by the address hash when Gravatar is on, and falls back on a failed load', async () => {
        render(ADA, true);
        // The hash resolves on its own schedule; wait for the image rather than for a fixed tick.
        await vi.waitFor(() => {
            fixture.detectChanges();
            expect(el('.user-button img')).not.toBeNull();
        });

        const img = el('.user-button img') as HTMLImageElement;
        expect(img.getAttribute('src')).toMatch(/^https:\/\/gravatar\.com\/avatar\/[0-9a-f]{64}\?s=80&d=404$/);
        expect(img.getAttribute('src')).not.toContain('ada@example.com');

        img.dispatchEvent(new Event('error'));
        fixture.detectChanges();

        expect(el('.user-button img')).toBeNull();
        expect(el('.user-button .user-initials')?.textContent?.trim()).toBe('AL');
    });

    it('logs out through the service, which ends the Keycloak session', () => {
        render(ADA, false);

        (el('.user-logout') as HTMLButtonElement).click();

        expect(auth.logout).toHaveBeenCalledTimes(1);
        expect(el('.user-logout')?.textContent?.trim()).toBe(en.user.logout);
    });
});
