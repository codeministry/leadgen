import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {Component} from '@angular/core';
import {provideRouter, Router} from '@angular/router';
import {routes} from '../../app.routes';
import en from '../../../../public/i18n/en.json';
import {AppHeader} from './app-header';

/** The same two methods the help drawer's spec fills in, for the same reason: jsdom has neither. */
function polyfillDialog(): void {
    const proto = HTMLDialogElement.prototype as Partial<HTMLDialogElement> & HTMLElement;
    if (typeof proto.showModal !== 'function') {
        proto.showModal = function (this: HTMLDialogElement) {
            this.setAttribute('open', '');
        };
    }
    if (typeof proto.close !== 'function') {
        proto.close = function (this: HTMLDialogElement) {
            if (!this.hasAttribute('open')) return;
            this.removeAttribute('open');
            this.dispatchEvent(new Event('close'));
        };
    }
}

@Component({template: ''})
class Blank {}

/** The app's own top-level paths, read off its route table rather than restated. */
const PATHS = routes.map((r) => r.path ?? '').filter((p) => p !== '' && p !== '**');

describe('AppHeader', () => {
    let fixture: ComponentFixture<AppHeader>;
    let http: HttpTestingController;

    beforeEach(() => {
        polyfillDialog();
        TestBed.configureTestingModule({
            providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
        });
        http = TestBed.inject(HttpTestingController);
        fixture = TestBed.createComponent(AppHeader);
        document.body.appendChild(fixture.nativeElement);
        fixture.detectChanges();
    });

    afterEach(() => (fixture.nativeElement as HTMLElement).remove());

    const el = (selector: string) => fixture.nativeElement.querySelector(selector) as HTMLElement;

    // Operator, 2026-09-27: Run ingest and the model choice moved to the workflow screen, at every
    // width. A header that grows them back is a second place that starts a run.
    it('holds no run control and no model choice, even with several models to pick from', () => {
        http.match('/api/v1/scoring-models').forEach((req) => req.flush({available: ['model-a', 'model-b'], preferred: 'model-a'}));
        fixture.detectChanges();
        const host = fixture.nativeElement as HTMLElement;

        expect(host.querySelector('.ingest-button, .ingest-live, lg-run-confirm, lg-run-control')).toBeNull();
        expect(host.querySelector('select')).toBeNull();
    });

    describe('the help button (ISC-311)', () => {
        const helpButton = () => el('.help-button') as HTMLButtonElement;
        const drawer = () => el('.lg-help-drawer') as HTMLDialogElement;

        it('sits at the right end of the header with a name of its own', () => {
            const ops = el('.ops');
            const buttons = [...ops.querySelectorAll(':scope > button')];
            expect(buttons.at(-1)).toBe(helpButton());
            expect(buttons.at(-2)).toBe(el('.settings-button'));
            expect(helpButton().getAttribute('aria-label')).toBe(en.shell.help);
        });

        it('opens the drawer, and Escape closes it with focus back on the button', () => {
            helpButton().focus();
            helpButton().click();
            fixture.detectChanges();
            expect(drawer().hasAttribute('open')).toBe(true);

            drawer().dispatchEvent(new Event('cancel', {cancelable: true}));
            fixture.detectChanges();

            expect(drawer().hasAttribute('open')).toBe(false);
            expect(document.activeElement).toBe(helpButton());
        });
    });

    describe('the chat entry (ISC-422, ISC-432)', () => {
        const chatRequests = () => http.match((r) => r.url.startsWith('/api/v1/chat') && r.url !== '/api/v1/chat/capability');
        const answerCapability = (present: boolean) => {
            http.expectOne('/api/v1/chat/capability').flush({present});
            fixture.detectChanges();
        };

        it('asks for the capability once, and with a chat model draws a named button', () => {
            answerCapability(true);
            const button = el('.lg-chat-open');
            expect(button).not.toBeNull();
            expect(button.getAttribute('aria-label')).toBe(en.chat.open);
            // First in the operations cluster, so arriving late moves nothing to its right.
            expect(el('.ops').firstElementChild).toBe(button);
        });

        it('without a chat model draws no button, and no chat request leaves on any route', async () => {
            answerCapability(false);
            expect(el('.lg-chat-open')).toBeNull();

            // Every top-level path the app routes, visited in turn with the header on screen.
            const router = TestBed.inject(Router);
            router.resetConfig(PATHS.map((path) => ({path, component: Blank})));
            for (const path of PATHS) {
                await router.navigateByUrl(`/${path}`);
                fixture.detectChanges();
            }
            expect(el('.lg-chat-open')).toBeNull();
            // Counted at the HTTP seam: every request to `/api/v1/chat` but the capability
            // call itself, which `answerCapability` already took out of the queue.
            expect(chatRequests().length).toBe(0);
        });
    });
});
