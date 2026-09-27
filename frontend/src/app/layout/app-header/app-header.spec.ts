import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {Component} from '@angular/core';
import {provideRouter, Router} from '@angular/router';
import {injectDispatch} from '@ngrx/signals/events';
import {Subject} from 'rxjs';
import {ChatApi} from '@core/api/chat.api';
import {ChatEvent} from '@core/model/chat';
import {chatEvents} from '@core/store/chat.events';
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

        // Spec 021: the ring is the brand on the button itself — at rest while nothing streams,
        // working while a turn is written behind a shut drawer — and the sparkle and the busy dot
        // are folded into it. The name is the only place the busy state is spoken.
        describe('the living mark on the button (ISC-469)', () => {
            const mark = () => el('.lg-chat-open lg-living-mark');

            it('carries the mark at rest in the icon slot and no sparkle', () => {
                answerCapability(true);

                expect(mark()).not.toBeNull();
                expect(mark().getAttribute('data-frame')).toBe('rest');
                expect(el('.lg-chat-open lg-icon')).toBeNull();
                expect(el('.lg-chat-busy')).toBeNull();
            });

            it('works while a turn streams with the drawer shut, and rests again once it ends', async () => {
                answerCapability(true);
                const router = TestBed.inject(Router);
                router.resetConfig([{path: 'offers', component: Blank}]);
                await router.navigateByUrl('/offers?chat=4');
                http.expectOne('/api/v1/chat/conversations/4').flush({
                    id: 4,
                    title: 'Conversation 4',
                    pinnedOfferId: null,
                    turns: [],
                    updatedAt: '2026-09-27T08:00:00Z',
                });
                const server = new Subject<ChatEvent>();
                vi.spyOn(TestBed.inject(ChatApi), 'ask').mockReturnValue(server.asObservable());
                const dispatch = TestBed.runInInjectionContext(() => injectDispatch(chatEvents));

                dispatch.asked('Which offers asked for Kafka?');
                server.next({event: 'turn', data: {turnId: 31}});
                fixture.detectChanges();
                // The drawer is open: the reader watches the turn, the button stays at rest.
                expect(mark().getAttribute('data-frame')).toBe('rest');

                // Shut by the chat's own close: a link without `?chat` no longer shuts it (ISC-444).
                dispatch.closeRequested();
                await vi.waitFor(() => expect(router.url).toBe('/offers'));
                fixture.detectChanges();
                expect(mark().getAttribute('data-frame')).toBe('working');
                expect(el('.lg-chat-busy')).toBeNull();
                expect(el('.lg-chat-open').getAttribute('aria-label')).toBe(`${en.chat.open}, ${en.chat.busyLabel}`);

                server.next({event: 'done', data: {state: 'DONE'}});
                server.complete();
                fixture.detectChanges();
                expect(mark().getAttribute('data-frame')).toBe('rest');
                expect(el('.lg-chat-open').getAttribute('aria-label')).toBe(en.chat.open);
            });
        });

        // Spec 020: ⌘K on macOS, Ctrl+K elsewhere, from any screen. Only the handled combination is
        // taken from the browser; everything else — Ctrl+K on a Mac, a plain k — goes on untouched.
        describe('the keyboard shortcut (ISC-461)', () => {
            const press = (init: KeyboardEventInit) => {
                const event = new KeyboardEvent('keydown', {key: 'k', bubbles: true, cancelable: true, ...init});
                document.body.dispatchEvent(event);
                fixture.detectChanges();
                return event;
            };
            const onPlatform = (platform: string) => vi.spyOn(navigator, 'platform', 'get').mockReturnValue(platform);
            const chatParam = () => new URL(TestBed.inject(Router).url, 'http://x').searchParams.get('chat');

            afterEach(() => vi.restoreAllMocks());

            const PLATFORMS = [
                {platform: 'MacIntel', handled: {metaKey: true}, other: {ctrlKey: true}, keys: 'Meta+K', hint: '⌘K'},
                {platform: 'Win32', handled: {ctrlKey: true}, other: {metaKey: true}, keys: 'Control+K', hint: 'Ctrl+K'},
            ] as const;

            for (const {platform, handled, other, keys, hint} of PLATFORMS) {
                describe(`on ${platform}`, () => {
                    beforeEach(() => onPlatform(platform));

                    it(`names ${hint} in the tooltip and in aria-keyshortcuts`, () => {
                        answerCapability(true);
                        const button = el('.lg-chat-open');
                        expect(button.getAttribute('aria-keyshortcuts')).toBe(keys);
                        expect(button.getAttribute('title')).toBe(en.chat.openShortcut.replace('{{keys}}', hint));
                        // The name stays the chat's own; the shortcut is announced by the attribute.
                        expect(button.getAttribute('aria-label')).toBe(en.chat.open);
                    });

                    it('opens the chat from any screen with the chat closed, and takes only that combination', async () => {
                        answerCapability(true);
                        const router = TestBed.inject(Router);
                        router.resetConfig(PATHS.map((path) => ({path, component: Blank})));
                        for (const path of PATHS.slice(0, 3)) {
                            await router.navigateByUrl(`/${path}`);
                            fixture.detectChanges();
                            expect(chatParam()).toBeNull();

                            expect(press({...other}).defaultPrevented).toBe(false);
                            expect(press({}).defaultPrevented).toBe(false);
                            expect(press({...handled, shiftKey: true}).defaultPrevented).toBe(false);
                            await Promise.resolve();
                            expect(chatParam()).toBeNull();

                            expect(press({...handled}).defaultPrevented).toBe(true);
                            await vi.waitFor(() => expect(chatParam()).toBe('new'));
                            expect(router.url.startsWith(`/${path}`)).toBe(true);
                            await router.navigateByUrl(`/${path}`);
                            fixture.detectChanges();
                        }
                    });

                    it('with the chat open, keeps it open and puts focus back into the composer', async () => {
                        answerCapability(true);
                        const router = TestBed.inject(Router);
                        router.resetConfig([{path: 'offers', component: Blank}]);
                        await router.navigateByUrl('/offers?chat=new');
                        fixture.detectChanges();
                        // The panel is not part of this fixture; its composer is stood in for by the field it renders.
                        const composer = document.createElement('textarea');
                        composer.className = 'lg-chat-input';
                        document.body.appendChild(composer);
                        el('.settings-button').focus();

                        expect(press({...handled}).defaultPrevented).toBe(true);
                        await Promise.resolve();

                        expect(chatParam()).toBe('new');
                        expect(document.activeElement).toBe(composer);
                        composer.remove();
                    });
                });
            }

            it('does nothing without a chat model', async () => {
                onPlatform('Win32');
                answerCapability(false);
                expect(press({ctrlKey: true}).defaultPrevented).toBe(false);
                await Promise.resolve();
                expect(chatParam()).toBeNull();
            });
        });
    });
});
