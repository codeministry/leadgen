import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {provideRouter} from '@angular/router';
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
    const runPosts = () => http.match(req => req.method === 'POST' && req.url === '/api/v1/ingest');

    describe('Run ingest asks first (ISC-318)', () => {
        const confirmDialog = () => el('.lg-run-confirm') as HTMLDialogElement;

        function activate(): void {
            const button = el('.ingest-button') as HTMLButtonElement;
            button.focus();
            button.click();
            fixture.detectChanges();
        }

        it('sends nothing on activation, only opens the confirmation', () => {
            activate();

            expect(confirmDialog().hasAttribute('open')).toBe(true);
            expect(confirmDialog().textContent).toContain(en.shell.confirmRun.title);
            expect(confirmDialog().textContent).toContain(en.shell.confirmRun.body);
            expect(runPosts().length).toBe(0);
        });

        it('starts nothing on cancel, and puts focus back on the button', () => {
            activate();
            (confirmDialog().querySelector('.btn-ghost') as HTMLButtonElement).click();
            fixture.detectChanges();

            expect(confirmDialog().hasAttribute('open')).toBe(false);
            expect(runPosts().length).toBe(0);
            expect(document.activeElement).toBe(el('.ingest-button'));
        });

        it('starts nothing on Escape', () => {
            activate();
            confirmDialog().dispatchEvent(new Event('cancel', {cancelable: true}));
            fixture.detectChanges();

            expect(confirmDialog().hasAttribute('open')).toBe(false);
            expect(runPosts().length).toBe(0);
            expect(document.activeElement).toBe(el('.ingest-button'));
        });

        it('starts exactly one run on confirm', () => {
            activate();
            (confirmDialog().querySelector('.btn-primary') as HTMLButtonElement).click();
            fixture.detectChanges();

            expect(confirmDialog().hasAttribute('open')).toBe(false);
            expect(runPosts().length).toBe(1);
        });
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
});
