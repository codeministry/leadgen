import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {provideRouter} from '@angular/router';
import {Dispatcher} from '@ngrx/signals/events';
import {ingestEvents} from '@core/store/ingest.events';
import {ScoringModelStore} from '@core/store/scoring-model.store';
import en from '../../../../public/i18n/en.json';
import {RunControl} from './run-control';

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

const RUNNING = {
    id: 7,
    startedAt: '2026-09-26T08:00:00Z',
    scoreModel: 'judge',
    stage: 'SCORE',
    stagePosition: 9,
    stageTotal: 15,
    stageStartedAt: '2026-09-26T08:04:00Z',
};

describe('RunControl', () => {
    let fixture: ComponentFixture<RunControl>;
    let http: HttpTestingController;

    beforeEach(() => {
        polyfillDialog();
        TestBed.configureTestingModule({
            providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
        });
        http = TestBed.inject(HttpTestingController);
        fixture = TestBed.createComponent(RunControl);
        document.body.appendChild(fixture.nativeElement);
        fixture.detectChanges();
    });

    afterEach(() => (fixture.nativeElement as HTMLElement).remove());

    const el = (selector: string) => fixture.nativeElement.querySelector(selector) as HTMLElement | null;
    const runButton = () => el('button.ingest-button') as HTMLButtonElement;
    const runPosts = () => http.match((req) => req.method === 'POST' && req.url === '/api/v1/ingest');

    function answerModels(available: string[]): void {
        http.match('/api/v1/scoring-models').forEach((req) => req.flush({available, preferred: available[0] ?? null}));
        fixture.detectChanges();
    }

    function running(): void {
        TestBed.inject(Dispatcher).dispatch(ingestEvents.currentLoaded(RUNNING));
        fixture.detectChanges();
    }

    describe('Run ingest asks first (ISC-318)', () => {
        const confirmDialog = () => el('.lg-run-confirm') as HTMLDialogElement;

        function activate(): void {
            const button = runButton();
            button.focus();
            button.click();
            fixture.detectChanges();
        }

        it('sends nothing on activation, only opens the confirmation', () => {
            activate();

            expect(confirmDialog().hasAttribute('open')).toBe(true);
            expect(confirmDialog().textContent).toContain(en.rules.runControl.confirm.title);
            expect(confirmDialog().textContent).toContain(en.rules.runControl.confirm.body);
            expect(runPosts().length).toBe(0);
        });

        it('starts nothing on cancel, and puts focus back on the button', () => {
            activate();
            (confirmDialog().querySelector('.btn-ghost') as HTMLButtonElement).click();
            fixture.detectChanges();

            expect(confirmDialog().hasAttribute('open')).toBe(false);
            expect(runPosts().length).toBe(0);
            expect(document.activeElement).toBe(runButton());
        });

        it('starts nothing on Escape', () => {
            activate();
            confirmDialog().dispatchEvent(new Event('cancel', {cancelable: true}));
            fixture.detectChanges();

            expect(confirmDialog().hasAttribute('open')).toBe(false);
            expect(runPosts().length).toBe(0);
            expect(document.activeElement).toBe(runButton());
        });

        it('cancel, then confirm: nothing after the cancel, exactly one run after the confirm', () => {
            activate();
            (confirmDialog().querySelector('.btn-ghost') as HTMLButtonElement).click();
            fixture.detectChanges();
            expect(runPosts().length).toBe(0);

            activate();
            (confirmDialog().querySelector('.modal-action .btn-primary:not(.btn-ghost)') as HTMLButtonElement).click();
            fixture.detectChanges();

            expect(confirmDialog().hasAttribute('open')).toBe(false);
            expect(runPosts().length).toBe(1);
        });

        it('keeps the button disabled while a run is going, with the step as its reason', () => {
            running();

            const button = runButton();
            expect(button, 'the button stays, disabled').not.toBeNull();
            expect(button.disabled).toBe(true);
            expect(button.textContent).toContain(en.rules.runControl.running);
            expect(button.getAttribute('title')).toContain('SCORE');
            // The status chip under it already links into the run; no second link here.
            expect(el('a')).toBeNull();
        });
    });

    describe('the tier', () => {
        it('is the soft primary, never the filled one: the workflow screen has no filled primary (ISC-419)', () => {
            const button = runButton();
            expect(button.classList.contains('btn-soft')).toBe(true);
            expect(button.classList.contains('btn-primary')).toBe(true);
            expect(button.textContent).toContain(en.rules.runControl.start);
        });
    });

    describe('the model choice', () => {
        const select = () => el('select.run-model') as HTMLSelectElement | null;

        it('draws no select for one model or none', () => {
            answerModels(['only-model']);
            expect(select()).toBeNull();
        });

        it('offers every model the server lists, names itself, and hands the choice to the store', () => {
            answerModels(['model-a', 'model-b']);

            const box = select();
            expect(box).not.toBeNull();
            expect([...box!.options].map((o) => o.value)).toEqual(['model-a', 'model-b']);
            expect(el(`label[for="${box!.id}"]`)?.textContent).toContain(en.rules.runControl.model);

            box!.value = 'model-b';
            box!.dispatchEvent(new Event('change'));
            fixture.detectChanges();
            expect(TestBed.inject(ScoringModelStore).effective()).toBe('model-b');
        });

        it('is disabled while a run is going', () => {
            answerModels(['model-a', 'model-b']);
            running();
            expect(select()!.disabled).toBe(true);
        });
    });
});
