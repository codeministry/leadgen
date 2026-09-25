import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {provideRouter} from '@angular/router';
import {TranslocoService} from '@jsverse/transloco';
import {WorkflowStage} from '@core/model/workflow';
import de from '../../../../../public/i18n/de.json';
import {AI_ICON, FAILED_ICON} from '../stage-marks';
import {FlowNode} from './flow-node';

function stage(id: string, costClasses: readonly string[], sourceId: string | null = null): WorkflowStage {
    return {
        id,
        kind: sourceId === null ? 'stage' : 'ingest',
        sourceId,
        description: `What ${id} does.`,
        costClasses,
        promptId: null,
        settings: [],
        knockouts: null,
        width: null,
    };
}

const FILTER = stage('FILTER', ['free']);
const SCORE = stage('SCORE', ['model']);

interface NodeInputs {
    readonly stage: WorkflowStage;
    readonly phaseId: string;
    readonly count?: number | null;
    readonly failed?: boolean;
    readonly selected?: boolean;
    readonly expandable?: boolean;
    readonly expanded?: boolean;
}

function render(inputs: NodeInputs): ComponentFixture<FlowNode> {
    const fixture = TestBed.createComponent(FlowNode);
    fixture.componentRef.setInput('stage', inputs.stage);
    fixture.componentRef.setInput('phaseId', inputs.phaseId);
    fixture.componentRef.setInput('count', inputs.count ?? null);
    fixture.componentRef.setInput('failed', inputs.failed ?? false);
    fixture.componentRef.setInput('selected', inputs.selected ?? false);
    fixture.componentRef.setInput('expandable', inputs.expandable ?? false);
    fixture.componentRef.setInput('expanded', inputs.expanded ?? false);
    fixture.detectChanges();
    return fixture;
}

function host(fixture: ComponentFixture<FlowNode>): HTMLElement {
    return fixture.nativeElement as HTMLElement;
}

function chip(fixture: ComponentFixture<FlowNode>): HTMLElement | null {
    return host(fixture).querySelector('.flow-node-count');
}

describe('FlowNode', () => {
    beforeEach(() => {
        TestBed.configureTestingModule({providers: [provideRouter([])]});
    });

    afterEach(() => {
        TestBed.inject(TranslocoService).setActiveLang('en');
    });

    it('names its phase and its stage', () => {
        const text = host(render({stage: FILTER, phaseId: 'sort'})).textContent;

        expect(text).toContain('Sort');
        expect(text).toContain('Hard filter');
    });

    it('names an ingest node by its source', () => {
        const text = host(render({stage: stage('INGEST alpha', ['network'], 'alpha'), phaseId: 'read'})).textContent;

        expect(text).toContain('alpha');
    });

    it('says what FILTER held back with a verb, a minus sign and English grouping', () => {
        const text = chip(render({stage: FILTER, phaseId: 'sort', count: 12548}))?.textContent ?? '';

        expect(text).toContain('−12,548');
        expect(text).toContain('held back');
    });

    it('groups the number the German way in a German session', () => {
        const transloco = TestBed.inject(TranslocoService);
        transloco.setTranslation(de, 'de');
        transloco.setActiveLang('de');

        const text = chip(render({stage: FILTER, phaseId: 'sort', count: 12548}))?.textContent ?? '';

        expect(text).toContain('−12.548');
        expect(text).toContain('aussortiert');
    });

    it('shows no chip and no zero without a run count', () => {
        const fixture = render({stage: FILTER, phaseId: 'sort', count: null});

        expect(chip(fixture)).toBeNull();
        expect(host(fixture).textContent).not.toMatch(/\b0\b/);
    });

    it('carries the failed marker only on the stage the run failed', () => {
        const failed = render({stage: FILTER, phaseId: 'sort', failed: true});
        const fine = render({stage: SCORE, phaseId: 'judge', failed: false});

        expect(host(failed).querySelector(`[data-icon="${FAILED_ICON}"]`)).not.toBeNull();
        expect(host(fine).querySelector(`[data-icon="${FAILED_ICON}"]`)).toBeNull();
    });

    it('carries the AI marker only on a model stage', () => {
        const model = render({stage: SCORE, phaseId: 'judge'});
        const free = render({stage: FILTER, phaseId: 'sort'});

        expect(host(model).querySelector(`[data-icon="${AI_ICON}"]`)).not.toBeNull();
        expect(host(model).querySelector('.flow-node')?.classList).toContain('is-ai');
        expect(host(free).querySelector(`[data-icon="${AI_ICON}"]`)).toBeNull();
    });

    it('draws one icon per cost class, each with an accessible name', () => {
        const icons = host(render({stage: stage('ENRICH', ['network', 'file']), phaseId: 'understand'})).querySelectorAll(
            '.flow-node-costs lg-icon',
        );

        expect(icons).toHaveLength(2);
    });

    it('links to itself through the stage query parameter', () => {
        const link = host(render({stage: FILTER, phaseId: 'sort'})).querySelector('a');

        expect(link?.getAttribute('href')).toContain('stage=FILTER');
        expect(link?.getAttribute('aria-current')).toBeNull();
    });

    it('marks itself current when selected', () => {
        const link = host(render({stage: FILTER, phaseId: 'sort', selected: true})).querySelector('a');

        expect(link?.getAttribute('aria-current')).toBe('page');
    });

    describe('width (ISC-402)', () => {
        const at = (value: number | null): WorkflowStage => ({
            ...stage('ENRICH', ['network']),
            width: value === null ? null : {key: 'enrichment.fetch.concurrency', value},
        });

        function card(fixture: ComponentFixture<FlowNode>): HTMLElement | null {
            return host(fixture).querySelector('.flow-node');
        }

        it('draws a stage wider than one as a stack carrying ×N with an accessible count', () => {
            const fixture = render({stage: at(4), phaseId: 'understand'});
            const width = host(fixture).querySelector<HTMLElement>('.flow-node-width');

            expect(card(fixture)?.classList).toContain('is-stacked');
            expect(width?.textContent?.trim()).toBe('×4');
            expect(width?.getAttribute('aria-label')).toBe('4 at once');
        });

        it('says the count in German in a German session', () => {
            const transloco = TestBed.inject(TranslocoService);
            transloco.setTranslation(de, 'de');
            transloco.setActiveLang('de');

            const width = host(render({stage: at(4), phaseId: 'understand'})).querySelector('.flow-node-width');

            expect(width?.getAttribute('aria-label')).toBe('4 gleichzeitig');
        });

        it('draws a stage of width one, or without a width, exactly as before: no stack, no multiplier', () => {
            for (const value of [1, null]) {
                const fixture = render({stage: at(value), phaseId: 'understand'});

                expect(card(fixture)?.classList).not.toContain('is-stacked');
                expect(host(fixture).querySelector('.flow-node-width')).toBeNull();
                expect(host(fixture).textContent).not.toContain('×');
            }
        });

        /**
         * Declared, not computed: jsdom does not load component styles, so the stack's rules are
         * read from flow-node.css and every custom property they name is checked against the AI
         * pair and the signal family.
         */
        it('paints the stack and the multiplier in neutral tokens only, never --lg-ai or the signal', () => {
            const css = readFileSync(resolve(process.cwd(), 'src/app/features/rules/flow-node/flow-node.css'), 'utf8');
            const bodies = Array.from(css.matchAll(/([^{}]+)\{([^}]*)\}/g))
                .filter(([, selector]) => /\.is-stacked\b|\.flow-node-width\b/.test(selector))
                .map(([, , body]) => body);
            const tokens = bodies.flatMap((body) => Array.from(body.matchAll(/var\(\s*(--[\w-]+)/g), (m) => m[1]));

            expect(bodies.length).toBeGreaterThan(0);
            expect(tokens).toContain('--color-base-300');
            for (const token of tokens) {
                expect(token).not.toMatch(/^--(lg-ai|lg-signal|color-accent|score-strong)/);
            }
        });
    });

    describe('expand toggle (ISC-390)', () => {
        function toggle(fixture: ComponentFixture<FlowNode>): HTMLButtonElement | null {
            return host(fixture).querySelector('button[data-action="expand"]');
        }

        it('offers no toggle on a stage with nothing to open', () => {
            expect(toggle(render({stage: FILTER, phaseId: 'sort'}))).toBeNull();
        });

        it('names the stage it opens and closes, and says whether it is open', () => {
            const closed = toggle(render({stage: FILTER, phaseId: 'sort', expandable: true}));
            const open = toggle(render({stage: FILTER, phaseId: 'sort', expandable: true, expanded: true}));

            expect(closed?.getAttribute('aria-expanded')).toBe('false');
            expect(closed?.getAttribute('aria-label')).toBe('Open Hard filter');
            expect(open?.getAttribute('aria-expanded')).toBe('true');
            expect(open?.getAttribute('aria-label')).toBe('Close Hard filter');
        });

        it('keeps the toggle a sibling of the link, never inside it', () => {
            const fixture = render({stage: FILTER, phaseId: 'sort', expandable: true});

            expect(host(fixture).querySelector('a button, a [role="button"]')).toBeNull();
            expect(toggle(fixture)?.closest('a')).toBeNull();
            expect(host(fixture).querySelector('a')?.getAttribute('href')).toContain('stage=FILTER');
        });

        it('emits the stage id on click', () => {
            const fixture = render({stage: FILTER, phaseId: 'sort', expandable: true});
            const seen: string[] = [];
            fixture.componentInstance.toggled.subscribe((id) => seen.push(id));

            toggle(fixture)?.click();

            expect(seen).toEqual(['FILTER']);
        });
    });
});
