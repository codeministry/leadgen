import {LastRunView} from '@core/model/last-run';
import {WorkflowStage, WorkflowView} from '@core/model/workflow';
import {countVerbKey, formatStageCount, stageCounts} from './stage-count';

function stage(id: string, sourceId: string | null = null): WorkflowStage {
    return {
        id,
        kind: sourceId === null ? 'stage' : 'ingest',
        sourceId,
        description: `What ${id} does.`,
        costClasses: ['free'],
        promptId: null,
        settings: [],
        knockouts: null,
        width: null,
    };
}

const WORKFLOW: WorkflowView = {
    phases: [
        {id: 'read', stages: [stage('INGEST zeta', 'zeta'), stage('INGEST alpha', 'alpha'), stage('INGEST quiet', 'quiet')]},
        {id: 'sort', stages: [stage('DEDUPE'), stage('FILTER'), stage('ARCHIVE')]},
        {id: 'understand', stages: [stage('ENRICH'), stage('CONTENT'), stage('FIELDS')]},
        {id: 'judge', stages: [stage('SCORE'), stage('RETRIEVAL')]},
        {id: 'hand', stages: [stage('OPEN'), stage('PACKAGE'), stage('DIGEST')]},
    ],
    unread: [],
};

const SOURCE = {documents: 1, written: 0, announced: null, complete: true};

/** Every number distinct, so a count read from the wrong field cannot pass by coincidence. */
const LAST_RUN: LastRunView = {
    finishedAt: '2026-09-24T04:13:00Z',
    status: 'COMPLETE',
    scoreModel: null,
    extracted: 101,
    written: 77,
    merged: 13,
    enriched: 45,
    removed: {abroad: 8, rate: 5, stack: 0},
    filterConsidered: 64,
    filterPassed: 51,
    scored: 29,
    shortlisted: 4,
    review: 6,
    packaged: 2,
    digestWritten: true,
    sources: [
        {...SOURCE, sourceId: 'zeta', extracted: 61},
        {...SOURCE, sourceId: 'alpha', extracted: 40},
    ],
    stages: [],
};

describe('stageCounts', () => {
    it.each([
        ['INGEST zeta', 61],
        ['INGEST alpha', 40],
        ['FILTER', 13],
        ['ENRICH', 45],
        ['SCORE', 29],
        ['PACKAGE', 2],
    ])('reads %s as %d', (id, expected) => {
        expect(stageCounts(WORKFLOW, LAST_RUN)[id]).toBe(expected);
    });

    it.each(['DEDUPE', 'OPEN', 'ARCHIVE', 'CONTENT', 'FIELDS', 'RETRIEVAL', 'DIGEST'])(
        'leaves %s null: a standing total or a figure the run does not carry',
        (id) => {
            expect(stageCounts(WORKFLOW, LAST_RUN)[id]).toBeNull();
        },
    );

    it('leaves an ingest source the run did not report null, never zero', () => {
        expect(stageCounts(WORKFLOW, LAST_RUN)['INGEST quiet']).toBeNull();
    });

    it('has one entry per stage of the workflow', () => {
        const ids = WORKFLOW.phases.flatMap((phase) => phase.stages.map((s) => s.id));

        expect(Object.keys(stageCounts(WORKFLOW, LAST_RUN)).sort()).toEqual([...ids].sort());
    });

    it('leaves a stage the run never reached null, so a failed run shows no stand-in zero', () => {
        const timed = (position: number, name: string, status = 'OK') => ({
            position,
            stage: name,
            startedAt: '2026-09-24T04:00:00Z',
            endedAt: '2026-09-24T04:00:01Z',
            millis: 1000,
            status,
            note: null,
            width: null,
        });
        const failed: LastRunView = {
            ...LAST_RUN,
            status: 'FAILED',
            removed: {},
            enriched: 0,
            scored: 0,
            packaged: 0,
            stages: [timed(0, 'DEDUPE'), timed(1, 'FILTER'), timed(2, 'ENRICH', 'FAILED')],
        };
        const counts = stageCounts(WORKFLOW, failed);

        expect(counts['FILTER']).toBe(0);
        expect(counts['ENRICH']).toBeNull();
        expect(counts['SCORE']).toBeNull();
        expect(counts['PACKAGE']).toBeNull();
        expect(counts['INGEST zeta']).toBe(61);
    });

    it('is empty without a last run', () => {
        expect(stageCounts(WORKFLOW, null)).toEqual({});
    });

    it('is empty without a workflow', () => {
        expect(stageCounts(null, LAST_RUN)).toEqual({});
    });
});

describe('countVerbKey', () => {
    it.each([
        ['INGEST zeta', 'rules.count.read'],
        ['FILTER', 'rules.count.filter'],
        ['ENRICH', 'rules.count.enrich'],
        ['SCORE', 'rules.count.score'],
        ['PACKAGE', 'rules.count.package'],
    ])('names what %s did with %s', (id, key) => {
        const found = WORKFLOW.phases.flatMap((phase) => phase.stages).find((s) => s.id === id)!;

        expect(countVerbKey(found)).toBe(key);
    });

    it.each(['DEDUPE', 'OPEN', 'ARCHIVE', 'CONTENT', 'FIELDS', 'RETRIEVAL', 'DIGEST'])(
        'has no verb for %s, which carries no run count',
        (id) => {
            expect(countVerbKey(stage(id))).toBeNull();
        },
    );
});

describe('formatStageCount', () => {
    it('groups the number the way the language does', () => {
        expect(formatStageCount(stage('ENRICH'), 12548, 'en')).toBe('12,548');
        expect(formatStageCount(stage('ENRICH'), 12548, 'de')).toBe('12.548');
    });

    it('writes the removals FILTER held back with a leading minus sign', () => {
        expect(formatStageCount(stage('FILTER'), 12548, 'en')).toBe('\u221212,548');
        expect(formatStageCount(stage('FILTER'), 12548, 'de')).toBe('\u221212.548');
    });

    it('writes no minus sign before a filter that removed nothing', () => {
        expect(formatStageCount(stage('FILTER'), 0, 'en')).toBe('0');
    });
});
