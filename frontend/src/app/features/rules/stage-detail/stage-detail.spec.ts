import {ComponentFixture, TestBed} from '@angular/core/testing';
import {FunnelView} from '@core/model/funnel';
import {PromptView} from '@core/model/prompt-view';
import {RulesView} from '@core/model/rules-view';
import {WorkflowSetting, WorkflowStage} from '@core/model/workflow';
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
    {id: 'scoring', model: 'judge-model', system: 'Score this offer.', user: 'OFFER {title}'},
    {id: 'content', model: null, system: 'Label every block.', user: 'BLOCKS {blocks}'},
    {id: 'fields', model: 'judge-model', system: 'Read the dates.', user: 'ADVERT {advert}'},
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
    ): {fixture: ComponentFixture<StageDetail>; page: HTMLElement} {
        const fixture = TestBed.createComponent(StageDetail);
        fixture.componentRef.setInput('stage', selected);
        fixture.componentRef.setInput('unread', unread);
        fixture.componentRef.setInput('rules', RULES);
        fixture.componentRef.setInput('prompts', PROMPTS);
        fixture.componentRef.setInput('funnel', FUNNEL);
        fixture.detectChanges();
        return {fixture, page: fixture.nativeElement as HTMLElement};
    }

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
        const {page} = render(stage('CONTENT', {promptId: 'content'}));
        const blocks = Array.from(page.querySelectorAll('pre'), (pre) => pre.textContent?.trim());

        expect(blocks).toEqual(['Label every block.', 'BLOCKS {blocks}']);
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

    it('says the FILTER funnel is the current working list, not the last run', () => {
        const {page} = render(FILTER);
        const caption = page.querySelector('[data-section="funnel"] .funnel-scope');

        expect(caption?.textContent).toContain('current working list');
        expect(caption?.textContent).toContain('not the last run');
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
        const CONTENT = stage('CONTENT', {costClasses: ['model'], promptId: 'content'});

        it('opens SCORE with the AI band, its sparkle and the model that answers', () => {
            const band = render(SCORE).page.querySelector('.ai-band');

            expect(band).not.toBeNull();
            expect(band?.querySelector('lg-icon')).not.toBeNull();
            expect(band?.textContent).toContain('AI step');
            expect(band?.textContent).toContain('judge-model');
        });

        it('opens CONTENT with the AI band and no model name when none is configured', () => {
            const band = render(CONTENT).page.querySelector('.ai-band');

            expect(band?.textContent).toContain('AI step');
            expect(band?.querySelector('.ai-band-model')).toBeNull();
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
});
