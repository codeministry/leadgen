import {CurrentRunView} from '@core/model/current-run';
import {WorkflowStage, WorkflowView} from '@core/model/workflow';
import {runState} from './run-state';

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

/**
 * Five phases, three ingest sources deliberately not alphabetical — the shape `runState` has to
 * join by `Stage.id`, never by position. Same house style as `workflow-layout.spec.ts`, one
 * source fewer so the fixture matches the claim's "five phases and three sources" wording.
 */
const WORKFLOW: WorkflowView = {
    phases: [
        {
            id: 'read',
            stages: [stage('INGEST zeta', 'zeta'), stage('INGEST alpha', 'alpha'), stage('INGEST mail', 'mail')],
        },
        {id: 'sort', stages: [stage('DEDUPE'), stage('FILTER'), stage('ARCHIVE')]},
        {id: 'understand', stages: [stage('ENRICH'), stage('CONTENT'), stage('FIELDS')]},
        {id: 'judge', stages: [stage('SCORE'), stage('RETRIEVAL')]},
        {id: 'hand', stages: [stage('OPEN'), stage('PACKAGE'), stage('DIGEST')]},
    ],
    unread: [{key: 'legacy.flag', value: 'true', file: 'pipeline.yaml'}],
};

/** The run order the server sends: phases in order, stages within a phase in order, sources first
 *  because the three sources are the whole `read` phase. */
const ORDER = [
    'INGEST zeta',
    'INGEST alpha',
    'INGEST mail',
    'DEDUPE',
    'FILTER',
    'ARCHIVE',
    'ENRICH',
    'CONTENT',
    'FIELDS',
    'SCORE',
    'RETRIEVAL',
    'OPEN',
    'PACKAGE',
    'DIGEST',
];

function currentRun(stageId: string): CurrentRunView {
    const position = ORDER.indexOf(stageId) + 1;
    return {
        id: 1,
        startedAt: '2026-09-25T10:00:00Z',
        scoreModel: 'gpt-oss:20b',
        stage: stageId,
        stagePosition: position,
        stageTotal: ORDER.length,
        stageStartedAt: '2026-09-25T10:05:00Z',
    };
}

describe('runState', () => {
    it('orders the phases in server order, and within a phase the stages in server order, sources first', () => {
        const state = runState(WORKFLOW, null);
        expect(state.order).toEqual(ORDER);
    });

    it('carries no state at all with no pass reported', () => {
        const state = runState(WORKFLOW, null);
        expect(state.running).toBeNull();
        expect(state.states.size).toBe(0);
    });

    it('marks exactly one node running, every earlier one done and every later one pending, at every stage of the run', () => {
        for (let i = 0; i < ORDER.length; i++) {
            const runningId = ORDER[i];
            if (runningId === undefined) throw new Error(`no stage at index ${i}`);

            const state = runState(WORKFLOW, currentRun(runningId));

            expect(state.running).toBe(runningId);
            expect(state.states.get(runningId)).toBe('running');

            for (let j = 0; j < i; j++) {
                const doneId = ORDER[j];
                if (doneId === undefined) throw new Error(`no stage at index ${j}`);
                expect(state.states.get(doneId)).toBe('done');
            }

            for (let j = i + 1; j < ORDER.length; j++) {
                const pendingId = ORDER[j];
                if (pendingId === undefined) throw new Error(`no stage at index ${j}`);
                expect(state.states.get(pendingId)).toBe('pending');
            }

            // the union of done + running + pending covers `order` exactly, nothing more and nothing less
            expect(state.states.size).toBe(ORDER.length);
            expect(new Set(state.states.keys())).toEqual(new Set(ORDER));
        }
    });

    it('draws a configured source the run will never walk by the canvas order, and ignores a stageTotal smaller than it', () => {
        // INGEST alpha is the second of the three Read-phase sources. A real pass may report a
        // stageTotal smaller than `order.length` when e.g. INGEST mail's connector type is not
        // implemented yet and IngestService never walks it — the join is still exact because it
        // reads only `current.stage`, never `stagePosition` or `stageTotal`.
        const run = currentRun('INGEST alpha');
        const state = runState(WORKFLOW, run);

        expect(state.running).toBe('INGEST alpha');
        expect(state.states.get('INGEST zeta')).toBe('done');
        expect(state.states.get('INGEST alpha')).toBe('running');
        expect(state.states.get('INGEST mail')).toBe('pending');
        expect(state.states.get('DEDUPE')).toBe('pending');

        const withSmallerTotal = runState(WORKFLOW, {...run, stageTotal: 5});
        expect(withSmallerTotal).toEqual(state);
    });

    it('runs nothing when the pass has opened its row but not yet entered a stage', () => {
        const run: CurrentRunView = {
            id: 1,
            startedAt: '2026-09-25T10:00:00Z',
            scoreModel: 'gpt-oss:20b',
            stage: null,
            stagePosition: null,
            stageTotal: null,
            stageStartedAt: null,
        };

        const state = runState(WORKFLOW, run);

        expect(state.running).toBeNull();
        expect(state.states.size).toBe(0);
    });

    it('leaves every node neutral when the reported stage name is not one the workflow contains', () => {
        const run: CurrentRunView = {
            id: 1,
            startedAt: '2026-09-25T10:00:00Z',
            scoreModel: 'gpt-oss:20b',
            stage: 'UNKNOWN_STAGE',
            stagePosition: 4,
            stageTotal: ORDER.length,
            stageStartedAt: '2026-09-25T10:05:00Z',
        };

        const state = runState(WORKFLOW, run);

        expect(state.running).toBeNull();
        expect(state.states.size).toBe(0);
    });

    it('reads nothing from stagePosition or stageTotal — two runs differing only there answer identically', () => {
        const reportedEarly: CurrentRunView = {...currentRun('SCORE'), stagePosition: 1, stageTotal: 99};
        const reportedLate: CurrentRunView = {...currentRun('SCORE'), stagePosition: 7, stageTotal: 7};

        expect(runState(WORKFLOW, reportedEarly)).toEqual(runState(WORKFLOW, reportedLate));
    });
});
