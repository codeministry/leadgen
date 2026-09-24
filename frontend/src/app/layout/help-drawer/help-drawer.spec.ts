import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {Component} from '@angular/core';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {provideRouter, Router} from '@angular/router';
import {TranslocoService} from '@jsverse/transloco';
import {Dispatcher} from '@ngrx/signals/events';
import {HELP_CHAPTERS, HELP_SHOTS} from '@core/help/help-chapters';
import {themeEvents} from '@core/theme/theme.events';
import en from '../../../../public/i18n/en.json';
import {HelpDrawer} from './help-drawer';

@Component({selector: 'lg-stub-screen', template: ''})
class StubScreen {}

/**
 * jsdom implements `HTMLDialogElement` as a bare element with an `open` attribute. The two
 * methods the drawer calls are filled in with the part of their behaviour the drawer relies
 * on — the attribute and the `close` event — so the specs exercise the real wiring instead of
 * a guard that does nothing. Where the browser already has them, nothing is replaced.
 */
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

describe('HelpDrawer (ISC-311, ISC-312, ISC-313)', () => {
    let fixture: ComponentFixture<HelpDrawer>;
    let http: HttpTestingController;
    let trigger: HTMLButtonElement;

    beforeEach(() => {
        // The theme store remembers a choice in storage, and one spec here chooses light.
        localStorage.clear();
        polyfillDialog();
        TestBed.configureTestingModule({
            providers: [
                provideRouter([
                    {path: 'rules', data: {section: 'rules'}, component: StubScreen},
                    {path: 'shortlist', data: {section: 'shortlist'}, component: StubScreen, children: [{path: ':id', component: StubScreen}]},
                    {path: 'elsewhere', component: StubScreen},
                ]),
                provideHttpClient(),
                provideHttpClientTesting(),
            ],
        });
        http = TestBed.inject(HttpTestingController);
        fixture = TestBed.createComponent(HelpDrawer);
        fixture.detectChanges();
        trigger = document.createElement('button');
        document.body.appendChild(trigger);
    });

    afterEach(() => trigger.remove());

    const dialog = () => fixture.nativeElement.querySelector('dialog') as HTMLDialogElement;
    const isOpen = () => dialog().hasAttribute('open');

    async function openAt(url: string): Promise<void> {
        await TestBed.inject(Router).navigateByUrl(url);
        trigger.focus();
        fixture.componentInstance.open(trigger);
        fixture.detectChanges();
        TestBed.tick();
    }

    /**
     * Lets a flushed response reach the view. Not `whenStable()`: an overview chapter asks for
     * its diagrams as soon as it renders, and the app is not stable while those are pending.
     */
    async function settle(): Promise<void> {
        for (let i = 0; i < 3; i++) {
            TestBed.tick();
            await Promise.resolve();
        }
        fixture.detectChanges();
    }

    async function answer(chapter: string, body: string, lang = 'en'): Promise<void> {
        http.expectOne(`/help/${lang}/${chapter}.md`).flush(body);
        await settle();
    }

    describe('opening and closing', () => {
        it('opens modally, moves focus inside, and closes on Escape with focus back on the button', async () => {
            const showModal = vi.spyOn(HTMLDialogElement.prototype, 'showModal');
            await openAt('/rules');

            expect(showModal).toHaveBeenCalled();
            expect(isOpen()).toBe(true);
            expect(dialog().contains(document.activeElement)).toBe(true);

            // Escape is a `cancel` first; the drawer closes on that and nothing else.
            dialog().dispatchEvent(new Event('cancel', {cancelable: true}));
            fixture.detectChanges();

            expect(isOpen()).toBe(false);
            expect(document.activeElement).toBe(trigger);
            showModal.mockRestore();
        });

        it('closes on its close button', async () => {
            await openAt('/rules');

            (fixture.nativeElement.querySelector('.lg-help-close') as HTMLButtonElement).click();
            fixture.detectChanges();

            expect(isOpen()).toBe(false);
            expect(document.activeElement).toBe(trigger);
        });

        it('closes on the backdrop, and not on a click inside the panel', async () => {
            await openAt('/rules');

            (fixture.nativeElement.querySelector('.lg-help-body') as HTMLElement).click();
            fixture.detectChanges();
            expect(isOpen()).toBe(true);

            // A click on the backdrop is a click whose target is the dialog element itself:
            // the panel inside fills the dialog, so nothing else hits it.
            dialog().click();
            fixture.detectChanges();
            expect(isOpen()).toBe(false);
            expect(document.activeElement).toBe(trigger);
        });

        it('has an accessible name', () => {
            expect(dialog().getAttribute('aria-labelledby')).toBeTruthy();
            const title = fixture.nativeElement.querySelector(`#${dialog().getAttribute('aria-labelledby')}`) as HTMLElement;
            expect(title.textContent?.trim()).toBe(en.help.title);
        });
    });

    describe('the chapter it opens at', () => {
        const current = () =>
            (fixture.nativeElement.querySelector('.lg-help-chapter-title') as HTMLElement).textContent?.trim();

        it('opens at the rules chapter from /rules and loads its text', async () => {
            await openAt('/rules');
            await answer('rules', 'The rules decide what survives.');

            expect(current()).toBe(en.help.chapter.rules);
            expect(fixture.nativeElement.querySelector('.lg-help-content').textContent).toContain('The rules decide what survives.');
        });

        it('opens at the offer-detail chapter while an offer is open (ISC-355)', async () => {
            await openAt('/shortlist/7');
            await answer('offer-detail', 'The offer.');

            expect(current()).toBe(en.help.chapter['offer-detail']);
        });

        it('opens at the shortlist chapter on the list itself', async () => {
            await openAt('/shortlist');
            await answer('shortlist', 'The shortlist.');

            expect(current()).toBe(en.help.chapter.shortlist);
        });

        it('opens at the overview from a route without a section', async () => {
            await openAt('/elsewhere');
            await answer('how-it-works', 'Overview.');

            expect(current()).toBe(en.help.chapter['how-it-works']);
        });


        it('follows the language toggle: switching to German loads the German chapter (ISC-315)', async () => {
            await openAt('/rules');
            await answer('rules', 'Rules.');

            TestBed.inject(TranslocoService).setActiveLang('de');
            fixture.detectChanges();
            TestBed.tick();
            await answer('rules', 'Regeln.', 'de');

            expect(fixture.nativeElement.textContent).toContain('Regeln.');
        });

        it('says so when a chapter cannot be loaded', async () => {
            await openAt('/rules');
            http.expectOne('/help/en/rules.md').flush('nope', {status: 404, statusText: 'Not Found'});
            await fixture.whenStable();
            fixture.detectChanges();

            expect(fixture.nativeElement.querySelector('.lg-help-content').textContent).toContain(en.help.failed);
        });
    });

    describe('the contents and the way back (ISC-312)', () => {
        const back = () => fixture.nativeElement.querySelector('.lg-help-back') as HTMLButtonElement | null;
        const rows = () => [...fixture.nativeElement.querySelectorAll('.lg-help-toc li button')] as HTMLButtonElement[];
        const row = (id: string) => fixture.nativeElement.querySelector(`.lg-help-toc [data-chapter="${id}"]`) as HTMLButtonElement;
        const heading = () => fixture.nativeElement.querySelector('.lg-help-chapter-title') as HTMLElement | null;

        async function toContents(): Promise<void> {
            back()!.click();
            await settle();
        }

        it('opens from /rules at the rules chapter with a way back to all chapters, and no chapter row', async () => {
            await openAt('/rules');
            await answer('rules', 'Rules.');

            expect(heading()!.textContent?.trim()).toBe(en.help.chapter.rules);
            expect(back()!.tagName).toBe('BUTTON');
            expect(back()!.textContent).toContain(en.help.allChapters);
            expect(fixture.nativeElement.querySelector('.lg-help-toc')).toBeNull();
            expect(fixture.nativeElement.querySelector('.lg-help-nav')).toBeNull();
        });

        it('lists every chapter, the overview last, each with an icon, its title and a hint', async () => {
            await openAt('/rules');
            await answer('rules', 'Rules.');
            await toContents();

            const nav = fixture.nativeElement.querySelector('nav.lg-help-toc') as HTMLElement;
            expect(nav.getAttribute('aria-label')).toBe(en.help.chapters);
            expect(rows().map(b => b.dataset['chapter'])).toEqual([...HELP_CHAPTERS]);
            expect(HELP_CHAPTERS.at(-1)).toBe('how-it-works');
            for (const [i, b] of rows().entries()) {
                const id = HELP_CHAPTERS[i];
                expect(b.querySelector('lg-icon'), `${id} has no icon`).not.toBeNull();
                expect(b.querySelector('.lg-help-toc-title')!.textContent?.trim()).toBe(en.help.chapter[id]);
                expect(b.querySelector('.lg-help-toc-hint')!.textContent?.trim()).toBe(en.help.chapterHint[id]);
            }
            expect(heading()).toBeNull();
            expect(back()).toBeNull();
        });

        it('marks the current screen\'s chapter, and only that one', async () => {
            await openAt('/rules');
            await answer('rules', 'Rules.');
            await toContents();

            expect(rows().filter(b => b.getAttribute('aria-current') === 'true').map(b => b.dataset['chapter'])).toEqual(['rules']);
        });

        it('opens a chosen chapter, loads it and moves focus to its heading', async () => {
            await openAt('/rules');
            await answer('rules', 'Rules.');
            await toContents();

            row('sources').click();
            await settle();
            await answer('sources', 'Sources.');

            expect(heading()!.textContent?.trim()).toBe(en.help.chapter.sources);
            expect(document.activeElement).toBe(heading());
            expect(fixture.nativeElement.querySelector('.lg-help-content').textContent).toContain('Sources.');
        });

        it('goes back to the list with focus on the row of the chapter it came from', async () => {
            await openAt('/rules');
            await answer('rules', 'Rules.');
            await toContents();
            row('sources').click();
            await settle();
            await answer('sources', 'Sources.');

            await toContents();

            expect(document.activeElement).toBe(row('sources'));
        });

        it('opens at the chapter again, not at the list, the next time', async () => {
            await openAt('/rules');
            await answer('rules', 'Rules.');
            await toContents();
            (fixture.nativeElement.querySelector('.lg-help-close') as HTMLButtonElement).click();
            fixture.detectChanges();

            await openAt('/shortlist');
            await answer('shortlist', 'Shortlist.');

            expect(heading()!.textContent?.trim()).toBe(en.help.chapter.shortlist);
        });
    });

    describe('the diagrams', () => {
        const figures = () => [...fixture.nativeElement.querySelectorAll('.lg-help-content figure')] as HTMLElement[];
        const HOW_IT_WORKS = ['run-phases', 'parts', 'application-states'] as const;

        /** A stand-in for the committed SVG, carrying what the drawer must strip on the way in. */
        const svgFor = (id: string) =>
            `<!-- source-sha256: 0 -->\n<svg id="help-diagram-${id}" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10" onload="alert(1)">` +
            `<script>alert(1)</script><rect width="10" height="10" style="fill:var(--color-base-100)"/><text>${id}</text></svg>`;

        /** Answers each diagram fetch; the figures only ask while the drawer is open. */
        async function answerDiagrams(ids: readonly string[] = HOW_IT_WORKS): Promise<void> {
            for (const id of ids) http.expectOne(`/help/diagrams/${id}.svg`).flush(svgFor(id));
            await settle();
        }

        /** The diagram figures only; how-it-works also carries a screenshot figure. */
        const diagrams = () => figures().filter(f => f.querySelector('lg-help-diagram') !== null);
        const inlined = () => diagrams().map(f => f.querySelector('svg'));

        it('inlines the three how-it-works diagrams, named by their captions (ISC-314)', async () => {
            await openAt('/elsewhere');
            await answer('how-it-works', 'From a mail to a package.');
            await answerDiagrams();

            expect(diagrams().length).toBe(3);
            expect(inlined().map(svg => svg?.id)).toEqual(HOW_IT_WORKS.map(id => `help-diagram-${id}`));
            expect(diagrams().map(f => f.querySelector('figcaption')!.textContent?.trim())).toEqual([
                en.help.diagram['run-phases'],
                en.help.diagram.parts,
                en.help.diagram['application-states'],
            ]);
            for (const [i, svg] of inlined().entries()) {
                expect(svg!.getAttribute('role')).toBe('img');
                expect(svg!.getAttribute('aria-label')).toBe(en.help.diagram[HOW_IT_WORKS[i]]);
            }
            expect(fixture.nativeElement.querySelector('img[src*="diagrams"]')).toBeNull();
        });

        it('strips scripts and event handlers from an inlined diagram', async () => {
            await openAt('/elsewhere');
            await answer('how-it-works', 'Text.');
            await answerDiagrams();

            const svg = inlined()[0]!;
            expect(svg.querySelector('script')).toBeNull();
            expect(svg.hasAttribute('onload')).toBe(false);
            expect(svg.querySelector('rect')).not.toBeNull();
        });

        it('is one file for both themes: switching the theme fetches nothing', async () => {
            await openAt('/elsewhere');
            await answer('how-it-works', 'Text.');
            await answerDiagrams();

            TestBed.inject(Dispatcher).dispatch(themeEvents.chosen('light'));
            TestBed.tick();
            fixture.detectChanges();

            http.expectNone(() => true);
            expect(inlined()[0]?.id).toBe('help-diagram-run-phases');
        });

        it('puts a diagram where its placeholder is, and the rest after the text', async () => {
            await openAt('/elsewhere');
            await answer('how-it-works', 'Before the parts.\n\n<!-- diagram: parts -->\n\nAfter the parts.');
            await answerDiagrams(['parts', 'run-phases', 'application-states']);

            const content = fixture.nativeElement.querySelector('.lg-help-content') as HTMLElement;
            const order = [...content.querySelectorAll('lg-markdown, figure')].map(node =>
                node.tagName === 'FIGURE'
                    ? (node.querySelector('svg')?.id ?? node.querySelector('img')!.getAttribute('src'))
                    : node.textContent?.trim(),
            );
            // The chapter's screenshot is listed but not placed either, so it follows the diagrams.
            expect(order).toEqual([
                'Before the parts.',
                'help-diagram-parts',
                'After the parts.',
                'help-diagram-run-phases',
                'help-diagram-application-states',
                '/help/shots/en/run-phases-rail-dark.webp',
            ]);
        });

        it('shows a screen chapter\'s screenshot and no diagram', async () => {
            await openAt('/rules');
            await answer('rules', 'Rules.');

            expect(figures().length).toBe(1);
            expect(diagrams().length).toBe(0);
            expect(figures()[0]!.querySelector('img')).not.toBeNull();
        });
    });

    describe('the screenshots', () => {
        const shot = () => fixture.nativeElement.querySelector('.lg-help-content img') as HTMLImageElement | null;

        it('shows the file for the reader\'s language and resolved theme, and follows both (ISC-353)', async () => {
            TestBed.inject(Dispatcher).dispatch(themeEvents.chosen('light'));
            await openAt('/rules');
            await answer('rules', 'Before.\n\n<!-- screenshot: rules-stage -->\n\nAfter.');

            expect(shot()!.getAttribute('src')).toBe('/help/shots/en/rules-stage-light.webp');
            expect(shot()!.getAttribute('alt')).toBe(en.help.shot['rules-stage']);

            TestBed.inject(Dispatcher).dispatch(themeEvents.chosen('dark'));
            await settle();
            expect(shot()!.getAttribute('src')).toBe('/help/shots/en/rules-stage-dark.webp');

            TestBed.inject(TranslocoService).setActiveLang('de');
            fixture.detectChanges();
            TestBed.tick();
            await answer('rules', '<!-- screenshot: rules-stage -->', 'de');
            expect(shot()!.getAttribute('src')).toBe('/help/shots/de/rules-stage-dark.webp');
        });

        it('reserves the registered size before the file loads, and loads it lazily', async () => {
            await openAt('/rules');
            await answer('rules', 'Rules.');

            expect(shot()!.getAttribute('width')).toBe(String(HELP_SHOTS['rules-stage'].width));
            expect(shot()!.getAttribute('height')).toBe(String(HELP_SHOTS['rules-stage'].height));
            expect(shot()!.getAttribute('loading')).toBe('lazy');
        });

        it('puts a screenshot where its placeholder is, and drops one the chapter does not list', async () => {
            await openAt('/rules');
            await answer('rules', 'Before.\n\n<!-- screenshot: dashboard -->\n<!-- screenshot: rules-stage -->\n\nAfter.');

            const content = fixture.nativeElement.querySelector('.lg-help-content') as HTMLElement;
            expect(content.querySelectorAll('img').length).toBe(1);
            expect(content.textContent!.indexOf('Before.')).toBeLessThan(content.textContent!.indexOf('After.'));
            const figure = shot()!.closest('figure')!;
            expect(figure.previousElementSibling?.textContent).toContain('Before.');
        });
    });
});
