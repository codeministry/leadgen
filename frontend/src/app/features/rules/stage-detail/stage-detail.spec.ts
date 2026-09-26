import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {TranslocoService} from '@jsverse/transloco';
import {FunnelView} from '@core/model/funnel';
import {PromptView} from '@core/model/prompt-view';
import {RulesView} from '@core/model/rules-view';
import {WorkflowSetting, WorkflowStage} from '@core/model/workflow';
import de from '../../../../../public/i18n/de.json';
import {SUB_ICONS} from '../stage-marks';
import {StageDetail} from './stage-detail';

const RULES: RulesView = {
    version: '2026-09-01',
    weights: [
        {key: 'skill', points: 40},
        {key: 'domain', points: 25},
    ],
    penalties: [{key: 'onsite', points: -15}],
    thresholds: {autoShortlist: 70, review: 50, discard: 30},
    archiveAfterDays: 21,
    knockouts: [],
    interestTopics: [{name: 'kotlin', weight: 5}],
    disinterestTopics: [{name: 'sap', weight: -10}],
};

const PROMPTS: readonly PromptView[] = [
    {
        id: 'scoring',
        model: 'judge-model',
        modelKey: 'llm.models.scoring',
        ownKey: 'llm.models.scoring',
        modelFallback: false,
        system: 'Score this offer.',
        user: 'OFFER {title}',
    },
    {
        id: 'content',
        model: 'content-model',
        modelKey: 'llm.models.content',
        ownKey: 'llm.models.content',
        modelFallback: false,
        system: 'Label every block.',
        user: 'BLOCKS {blocks}',
    },
    {
        id: 'fields',
        model: 'fields-model',
        modelKey: 'llm.models.fields',
        ownKey: 'llm.models.fields',
        modelFallback: false,
        system: 'Read the dates.',
        user: 'ADVERT {advert}',
    },
    {
        id: 'unanswered',
        model: null,
        modelKey: null,
        ownKey: null,
        modelFallback: false,
        system: 'Nobody reads this.',
        user: 'NOTHING {nothing}',
    },
    {
        id: 'writing',
        model: 'writer-model',
        modelKey: 'llm.models.writing',
        ownKey: 'llm.models.writing',
        modelFallback: false,
        system: 'Write one cover letter.',
        user: 'STYLE RULES {rules}',
    },
];

const FUNNEL: FunnelView = {
    total: 100,
    stages: ['remote', 'abroad', 'rate', 'stack', 'excluded', 'duplicate'].map((id) => ({id, label: id, removed: 5})),
    survived: 70,
    archived: 0,
};

function stage(id: string, extra: Partial<WorkflowStage> = {}): WorkflowStage {
    return {
        id,
        kind: 'stage',
        sourceId: null,
        description: `What ${id} does.`,
        costClasses: ['free'],
        promptId: null,
        settings: [],
        knockouts: null,
        width: null,
        ...extra,
    };
}

const FILTER = stage('FILTER', {
    settings: [
        {key: 'remote.accept_unknown', value: 'true', file: 'matching-rules.yaml'},
        {key: 'filter.enabled', value: 'true', file: 'pipeline.yaml'},
        {key: 'stack.required', value: 'java', file: 'matching-rules.yaml'},
    ],
    knockouts: [
        {id: 'remote', description: 'Drops on-site offers.', keys: ['remote.accept_unknown']},
        {id: 'stack', description: 'Drops offers without the stack.', keys: ['stack.required']},
    ],
});

describe('StageDetail', () => {
    function render(
        selected: WorkflowStage | 'unread',
        unread: readonly WorkflowSetting[] = [],
        prompts: readonly PromptView[] = PROMPTS,
    ): {fixture: ComponentFixture<StageDetail>; page: HTMLElement} {
        const fixture = TestBed.createComponent(StageDetail);
        fixture.componentRef.setInput('stage', selected);
        fixture.componentRef.setInput('unread', unread);
        fixture.componentRef.setInput('rules', RULES);
        fixture.componentRef.setInput('prompts', prompts);
        fixture.componentRef.setInput('funnel', FUNNEL);
        fixture.detectChanges();
        return {fixture, page: fixture.nativeElement as HTMLElement};
    }

    describe('marks each section a sub-node links to, headed by its kind\'s icon (ISC-406)', () => {
        const sub = (page: HTMLElement, id: string): HTMLElement | undefined =>
            Array.from(page.querySelectorAll<HTMLElement>('[data-sub]')).find((el) => el.dataset['sub'] === id);
        const headingIcon = (el: HTMLElement | undefined): string | undefined =>
            el?.querySelector<HTMLElement>('h3 [data-icon]')?.dataset['icon'];

        it('gives every knockout row its sub id and heads the knockouts with the knockout icon', () => {
            const {page} = render(FILTER);
            expect(sub(page, 'knockout:remote')?.tagName).toBe('LI');
            expect(sub(page, 'knockout:stack')?.textContent).toContain('Drops offers without the stack.');
            expect(page.querySelector<HTMLElement>('[data-section="funnel"] h3 [data-icon]')?.dataset['icon']).toBe(SUB_ICONS.knockout);
        });

        it('gives SCORE\'s four blocks their sub ids and each heading its block\'s icon', () => {
            const {page} = render(stage('SCORE', {promptId: 'scoring'}));
            for (const block of ['weights', 'penalties', 'bands', 'topics'] as const) {
                expect(sub(page, `score:${block}`), block).toBeDefined();
                expect(headingIcon(sub(page, `score:${block}`)), block).toBe(SUB_ICONS[block]);
            }
            expect(headingIcon(sub(page, 'prompt:scoring'))).toBe(SUB_ICONS.prompt);
        });
    });

    it('names the stage and carries the server description', () => {
        const {page} = render(FILTER);

        expect(page.querySelector('h2')?.textContent?.trim()).toBe('Hard filter');
        expect(page.textContent).toContain('What FILTER does.');
    });

    it('groups the settings under one heading per file, in the order the files first appear', () => {
        const {page} = render(FILTER);
        const groups = Array.from(page.querySelectorAll('.settings-group'));

        expect(groups.map((g) => g.querySelector('.settings-file-name')?.textContent?.trim())).toEqual([
            'matching-rules.yaml',
            'pipeline.yaml',
        ]);
        expect(groups[0].querySelector('.settings-file lg-icon')).not.toBeNull();
        expect(groups[0].querySelector('.settings-file-count')?.textContent?.trim()).toBe('2 keys');
        expect(groups[1].querySelector('.settings-file-count')?.textContent?.trim()).toBe('1 key');
        expect(groups[0].querySelector('dd')?.textContent?.trim()).toBe('true');
    });

    it('stacks the settings as a list, the key above its value, never as a two-column table', () => {
        for (const selected of [FILTER, 'unread'] as const) {
            const {page} = render(selected, [{key: 'hard_filters.remote.reject_keywords_de', value: '[vor Ort, onsite]', file: 'matching-rules.yaml'}]);
            const stack = page.querySelector('[data-section="settings"] .settings-stack');

            expect(stack).not.toBeNull();
            expect(page.querySelector('[data-section="settings"] .settings-groups, [data-section="settings"] .pairs')).toBeNull();
            // Every group is a direct block child of the stack: one per row, never side by side.
            for (const group of Array.from(page.querySelectorAll('.settings-group'))) {
                expect(group.parentElement).toBe(stack);
                expect(group.querySelector('dl')?.classList.contains('setting-list')).toBe(true);
            }
        }
    });

    describe('gives the settings a hierarchy (ISC-289)', () => {
        const RICH: readonly WorkflowSetting[] = [
            {key: 'hard_filters.remote.reject_keywords_de', value: '[100% vor Ort, onsite]', file: 'matching-rules.yaml'},
            {key: 'hard_filters.remote.derive_from[].field', value: '[location, title]', file: 'matching-rules.yaml'},
            {key: 'hard_filters.location.allowed', value: '[]', file: 'matching-rules.yaml'},
            {key: 'enabled', value: 'true', file: 'matching-rules.yaml'},
        ];
        const subgroups = (page: HTMLElement): Element[] => Array.from(page.querySelectorAll('.settings-group .setting-subgroup'));

        it('names each key prefix once as a subheading, keys without one first under none', () => {
            const {page} = render('unread', RICH);

            expect(subgroups(page).map((s) => s.querySelector('.setting-prefix')?.textContent?.trim() ?? null)).toEqual([
                null,
                'hard_filters.remote',
                'hard_filters.remote.derive_from[]',
                'hard_filters.location',
            ]);
        });

        it('labels each entry by its last segment and keeps the full key as its title', () => {
            const {page} = render('unread', RICH);
            const labels = Array.from(page.querySelectorAll('.setting-key'));

            expect(labels.map((l) => l.textContent?.trim())).toEqual(['enabled', 'reject_keywords_de', 'field', 'allowed']);
            expect(labels.map((l) => l.getAttribute('title'))).toEqual([
                'enabled',
                'hard_filters.remote.reject_keywords_de',
                'hard_filters.remote.derive_from[].field',
                'hard_filters.location.allowed',
            ]);
        });

        it('sets the label in the sans face and the value in mono on a code surface', () => {
            const {page} = render('unread', RICH);
            const scalar = page.querySelector('.setting-value .setting-code');

            expect(page.querySelector('.setting-key')?.classList.contains('type-mono-data')).toBe(false);
            expect(scalar?.textContent?.trim()).toBe('true');
            expect(scalar?.classList.contains('type-mono-data')).toBe(true);
        });

        it('draws a list value as one chip per item, and an empty list as a muted word', () => {
            const {page} = render('unread', RICH);
            const chips = (index: number): string[] =>
                Array.from(subgroups(page)[index].querySelectorAll('.setting-chip'), (c) => c.textContent?.trim() ?? '');

            expect(chips(1)).toEqual(['100% vor Ort', 'onsite']);
            expect(chips(2)).toEqual(['location', 'title']);
            expect(chips(3)).toEqual([]);
            expect(subgroups(page)[3].querySelector('.setting-empty')?.textContent?.trim()).toBe('empty');
        });
    });

    it('draws FILTER through the funnel rail, six rows with the survivors last', () => {
        const {page} = render(FILTER);
        const rows = Array.from(page.querySelectorAll('lg-funnel-rail li.row'));

        expect(rows.filter((r) => !r.classList.contains('head') && !r.classList.contains('survived'))).toHaveLength(6);
        expect(rows.at(-1)?.classList.contains('survived')).toBe(true);
    });

    it('lists each knockout with its description and the keys it reads', () => {
        const {page} = render(FILTER);
        const items = Array.from(page.querySelectorAll('.knockout'));

        expect(items).toHaveLength(2);
        expect(items[1].textContent).toContain('Drops offers without the stack.');
        expect(items[1].textContent).toContain('stack.required');
    });

    it('shows SCORE with weights, penalties, bands, topics and its prompt', () => {
        const {page} = render(stage('SCORE', {promptId: 'scoring'}));
        const section = (name: string): string => page.querySelector(`[data-section="${name}"]`)?.textContent ?? '';

        expect(section('weights')).toContain('skill');
        expect(section('weights')).toContain('25');
        expect(section('penalties')).toContain('onsite');
        expect(section('penalties')).toContain('-15');
        expect(section('bands')).toContain('70');
        expect(section('bands')).toContain('50');
        // The scorer discards everything below `review`; `thresholds.discard` is read by nothing.
        expect(section('bands').replace(/\s+/g, ' ')).toContain('< 50');
        expect(section('bands')).not.toContain('30');
        expect(section('topics')).toContain('kotlin');
        expect(section('topics')).toContain('sap');
        expect(section('prompt')).toContain('Score this offer.');
        expect(section('prompt')).toContain('judge-model');
    });

    it('shows the prompt a model stage sends, and says when no model answers it', () => {
        const {page} = render(stage('CONTENT', {promptId: 'unanswered'}));
        const blocks = Array.from(page.querySelectorAll('pre'), (pre) => pre.textContent?.trim());

        expect(blocks).toEqual(['Nobody reads this.', 'NOTHING {nothing}']);
        expect(page.textContent).toContain('no model configured');
    });

    it('shows the prompt FIELDS sends, under its own label', () => {
        const {page} = render(stage('FIELDS', {promptId: 'fields', costClasses: ['model']}));
        const section = page.querySelector('[data-section="prompt"]');

        expect(section?.querySelector('h3')?.textContent?.trim()).toBe('Field extractor');
        expect(Array.from(section?.querySelectorAll('pre') ?? [], (pre) => pre.textContent?.trim())).toEqual([
            'Read the dates.',
            'ADVERT {advert}',
        ]);
    });

    it('shows the cover-letter writer prompt on PACKAGE, under its own label (ISC-326)', () => {
        // The letter is written at PACKAGE, and the stage keeps its file cost class: the
        // prompt section is keyed on the prompt id alone, the same as the other model stages.
        const {page} = render(stage('PACKAGE', {promptId: 'writing', costClasses: ['file']}));
        const section = page.querySelector('[data-section="prompt"]');

        expect(section?.querySelector('h3')?.textContent?.trim()).toBe('Cover-letter writer');
        expect(section?.textContent).toContain('writer-model');
        expect(Array.from(section?.querySelectorAll('pre') ?? [], (pre) => pre.textContent?.trim())).toEqual([
            'Write one cover letter.',
            'STYLE RULES {rules}',
        ]);
    });

    it('says the FILTER funnel is the current working list, not the last run', () => {
        const {page} = render(FILTER);
        const caption = page.querySelector('[data-section="funnel"] .funnel-scope');

        expect(caption?.textContent).toContain('current working list');
        expect(caption?.textContent).toContain('not the last run');
    });

    // ISC-393: the graph's chips count the last run; the sheet's figures are the working list.
    it('labels the FILTER figures as the working list, apart from the graph\'s last-run chips', () => {
        const {page} = render(FILTER);
        const figures = page.querySelector('[data-section="funnel"] [data-figures="working-list"]');
        const text = (figures?.textContent ?? '').replace(/\s+/g, ' ').trim();

        expect(figures).not.toBeNull();
        expect(text).toContain('Working list');
        expect(text).toContain(`${FUNNEL.total.toLocaleString('en')} → ${FUNNEL.survived.toLocaleString('en')}`);
        expect(page.querySelector('[data-section="funnel"] .funnel-scope')?.textContent).toContain('numbers on the graph');
    });

    it('points a later ingest entry at the first source for the shared settings', () => {
        const {page} = render(stage('INGEST second', {kind: 'ingest', sourceId: 'second', settings: []}));
        const settings = page.querySelector('[data-section="settings"]')?.textContent ?? '';

        expect(settings).not.toContain('reads no configuration key');
        expect(settings).toContain('first source');
    });

    it('shows no prompt, weights or funnel on a stage that has none', () => {
        const {page} = render(stage('DEDUPE'));

        expect(page.querySelector('lg-funnel-rail')).toBeNull();
        expect(page.querySelector('pre')).toBeNull();
        expect(page.querySelector('[data-section="weights"]')).toBeNull();
        expect(page.textContent).toContain('reads no configuration key');
    });

    it('shows the unread keys grouped by file with the explanation line', () => {
        const {page} = render('unread', [
            {key: 'legacy.flag', value: 'true', file: 'pipeline.yaml'},
            {key: 'old.topic', value: 'x', file: 'skill-profile.yaml'},
        ]);

        expect(page.querySelector('h2')?.textContent?.trim()).toBe('Read by nothing');
        expect(Array.from(page.querySelectorAll('.settings-file-name'), (h) => h.textContent?.trim())).toEqual([
            'pipeline.yaml',
            'skill-profile.yaml',
        ]);
        expect(page.querySelector('.unread-note')?.textContent?.trim()).toBeTruthy();
    });

    it('offers no primary action', () => {
        expect(render(FILTER).page.querySelector('.btn-primary')).toBeNull();
    });

    describe('names an AI step in a head band (ISC-308)', () => {
        const SCORE = stage('SCORE', {costClasses: ['model'], promptId: 'scoring'});

        it('opens SCORE with the AI band, its sparkle and the model that answers', () => {
            const band = render(SCORE).page.querySelector('.ai-band');

            expect(band).not.toBeNull();
            expect(band?.querySelector('lg-icon')).not.toBeNull();
            expect(band?.textContent).toContain('AI step');
            expect(band?.textContent).toContain('judge-model');
        });

        it('opens CONTENT with the AI band and no model name when none is configured', () => {
            const band = render(stage('CONTENT', {costClasses: ['model'], promptId: 'unanswered'})).page.querySelector('.ai-band');

            expect(band?.textContent).toContain('AI step');
            expect(band?.querySelector('.ai-band-model')).toBeNull();
            expect(band?.querySelector('.ai-band-origin')).toBeNull();
        });

        it('opens an ingest source that sends the extraction prompt with the AI band', () => {
            const source = stage('INGEST', {kind: 'ingest', sourceId: 'mail', costClasses: ['network'], promptId: 'extraction'});

            expect(render(source).page.querySelector('.ai-band')?.textContent).toContain('AI step');
        });

        it('draws no band for an ingest source that sends no prompt', () => {
            const source = stage('INGEST', {kind: 'ingest', sourceId: 'feed', costClasses: ['network']});

            expect(render(source).page.querySelector('.ai-band')).toBeNull();
        });

        it('draws the band first in the pane', () => {
            const page = render(SCORE).page;

            expect(page.firstElementChild?.classList.contains('ai-band')).toBe(true);
        });

        it('draws no band for FILTER or the unread entry', () => {
            expect(render(FILTER).page.querySelector('.ai-band')).toBeNull();
            expect(render('unread').page.querySelector('.ai-band')).toBeNull();
        });
    });
    describe('names the key that picked the model, as text in the band (ISC-386)', () => {
        const origin = (page: HTMLElement): Element | null => page.querySelector('.ai-band .ai-band-origin');
        const flat = (el: Element | null): string => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();
        const FIELDS = stage('FIELDS', {costClasses: ['model'], promptId: 'fields'});
        const ON_FALLBACK: PromptView = {
            ...PROMPTS[2],
            model: 'judge-model',
            modelKey: 'llm.models.scoring',
            modelFallback: true,
        };

        it('CONTENT with its own model: the key, then "— this stage\'s own key"', () => {
            const line = origin(render(stage('CONTENT', {costClasses: ['model'], promptId: 'content'})).page);

            expect(line?.querySelector('code')?.textContent?.trim()).toBe('llm.models.content');
            expect(flat(line)).toBe("llm.models.content — this stage's own key");
        });

        it('prints the own key the server sent, never one rebuilt from the prompt id', () => {
            const renamed: PromptView = {...ON_FALLBACK, ownKey: 'llm.models.labelling'};
            const line = origin(render(FIELDS, [], [renamed]).page);

            expect(Array.from(line?.querySelectorAll('code') ?? [], (c) => c.textContent?.trim())).toEqual([
                'llm.models.labelling',
                'llm.models.scoring',
            ]);
        });

        it('leaves the second line to inherit base-content: no colour on the origin or the key', () => {
            const css = readFileSync(resolve(process.cwd(), 'src/app/features/rules/stage-detail/stage-detail.css'), 'utf8');
            const blocks = Array.from(css.matchAll(/([^{}]+)\{([^}]*)\}/g))
                .filter(([, selector]) => /\.ai-band-(origin|key)\b/.test(selector))
                .map(([, , body]) => body);

            expect(blocks.length).toBeGreaterThan(0);
            for (const body of blocks) {
                expect(body).not.toMatch(/(^|[;\s])color\s*:/);
            }
        });

        it('FIELDS with its own model names llm.models.fields', () => {
            const line = origin(render(FIELDS).page);

            expect(Array.from(line?.querySelectorAll('code') ?? [], (c) => c.textContent?.trim())).toEqual(['llm.models.fields']);
        });

        it('FIELDS on the fallback names its own empty key, then the scoring key that answered', () => {
            const line = origin(render(FIELDS, [], [ON_FALLBACK]).page);

            expect(Array.from(line?.querySelectorAll('code') ?? [], (c) => c.textContent?.trim())).toEqual([
                'llm.models.fields',
                'llm.models.scoring',
            ]);
            expect(flat(line)).toBe('llm.models.fields is empty, so the scoring judge answers (llm.models.scoring)');
        });

        it('sets the line in the ink, never the muted text', () => {
            const line = origin(render(FIELDS).page);

            expect(line).not.toBeNull();
            expect(line?.classList.contains('text-muted')).toBe(false);
        });

        it('reads in German', () => {
            const transloco = TestBed.inject(TranslocoService);
            transloco.setTranslation(de, 'de');
            transloco.setActiveLang('de');

            expect(flat(origin(render(stage('CONTENT', {costClasses: ['model'], promptId: 'content'})).page))).toBe(
                'llm.models.content — der eigene Schlüssel dieses Schritts',
            );
            expect(flat(origin(render(FIELDS, [], [ON_FALLBACK]).page))).toBe(
                'llm.models.fields ist leer, daher antwortet der Scoring-Judge (llm.models.scoring)',
            );
        });
    });

    describe('states the width of a bounded stage in its head (ISC-386)', () => {
        const widthLine = (page: HTMLElement): Element | null => page.querySelector('.detail-head .detail-width');
        const flat = (el: Element | null): string => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();

        it('ENRICH at 3 with enrichment.fetch.concurrency', () => {
            const line = widthLine(render(stage('ENRICH', {width: {key: 'enrichment.fetch.concurrency', value: 3}})).page);

            expect(flat(line)).toBe('Works on up to 3 adverts at once enrichment.fetch.concurrency');
            expect(line?.querySelector('code')?.textContent?.trim()).toBe('enrichment.fetch.concurrency');
            expect(line?.querySelector('lg-icon')?.getAttribute('aria-hidden')).toBe('true');
        });

        it('CONTENT at 1 reads "one advert" and still names the key', () => {
            const line = widthLine(render(stage('CONTENT', {promptId: 'content', width: {key: 'llm.concurrency', value: 1}})).page);

            expect(flat(line)).toBe('Works on one advert at once llm.concurrency');
        });

        it('sits under the description, inside the head', () => {
            const head = render(stage('FIELDS', {width: {key: 'llm.concurrency', value: 4}})).page.querySelector('.detail-head');

            expect(head?.lastElementChild?.classList.contains('detail-width')).toBe(true);
        });

        it('FILTER shows no width line', () => {
            expect(widthLine(render(FILTER).page)).toBeNull();
        });

        it('reads in German at 1 and at 4', () => {
            const transloco = TestBed.inject(TranslocoService);
            transloco.setTranslation(de, 'de');
            transloco.setActiveLang('de');

            expect(flat(widthLine(render(stage('CONTENT', {width: {key: 'llm.concurrency', value: 1}})).page))).toBe(
                'Bearbeitet eine Anzeige gleichzeitig llm.concurrency',
            );
            expect(flat(widthLine(render(stage('SCORE', {width: {key: 'llm.concurrency', value: 4}})).page))).toBe(
                'Bearbeitet bis zu 4 Anzeigen gleichzeitig llm.concurrency',
            );
        });
    });
});
