import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {provideRouter} from '@angular/router';
import {PromptView} from '@core/model/prompt-view';
import {RulesView} from '@core/model/rules-view';
import {Rules, RULES_PROMPTS_SECTION, RULES_SECTIONS} from './rules';

const RULES: RulesView = {
    version: '2026-09-01',
    weights: [{key: 'skill', points: 40}],
    penalties: [],
    thresholds: {autoShortlist: 70, review: 50, discard: 30},
    archiveAfterDays: 21,
    knockouts: [{key: 'abroad', label: 'Abroad', value: 'DE', values: []}],
    interestTopics: [],
    disinterestTopics: [],
};

const PROMPT: PromptView = {id: 'judge', model: 'some-model', system: 'You judge.', user: 'An offer.'};

describe('Rules', () => {
    let http: HttpTestingController;

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
        });
        http = TestBed.inject(HttpTestingController);
    });

    function render(prompts: readonly PromptView[]): ComponentFixture<Rules> {
        const fixture = TestBed.createComponent(Rules);
        fixture.detectChanges();
        http.expectOne('/api/v1/rules').flush(RULES);
        http.expectOne('/api/v1/prompts').flush(prompts);
        fixture.detectChanges();
        return fixture;
    }

    function hrefs(fixture: ComponentFixture<Rules>): (string | null)[] {
        return Array.from(
            (fixture.nativeElement as HTMLElement).querySelectorAll('lg-anchor-rail nav a'),
            (a) => (a.getAttribute('href') ?? '').replace(/^[^#]*/, ''),
        );
    }

    it('declares its three sections and renders a focusable heading for each', () => {
        const fixture = render([]);
        const element = fixture.nativeElement as HTMLElement;

        expect(hrefs(fixture)).toEqual(RULES_SECTIONS.map((section) => `#${section.id}`));
        for (const section of RULES_SECTIONS) {
            const heading = element.querySelector(`h2#${section.id}.lg-anchor-target`);
            expect(heading?.getAttribute('tabindex'), section.id).toBe('-1');
        }
    });

    it('adds the prompts as a fourth section only when a model has prompts to show', () => {
        const fixture = render([PROMPT]);
        const element = fixture.nativeElement as HTMLElement;

        expect(hrefs(fixture)).toContain(`#${RULES_PROMPTS_SECTION.id}`);
        expect(element.querySelector(`h2#${RULES_PROMPTS_SECTION.id}.lg-anchor-target`)).not.toBeNull();
    });

    afterEach(() => http.verify());
});
