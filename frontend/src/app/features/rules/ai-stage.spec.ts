import {WorkflowStage} from '@core/model/workflow';
import {isAiStage} from './ai-stage';

function stage(extra: Partial<WorkflowStage>): WorkflowStage {
    return {
        id: 'X',
        kind: 'stage',
        sourceId: null,
        description: '',
        costClasses: ['free'],
        promptId: null,
        settings: [],
        knockouts: null,
        ...extra,
    };
}

describe('isAiStage (ISC-308)', () => {
    it.each([
        ['SCORE, cost class model', stage({id: 'SCORE', costClasses: ['model'], promptId: 'scoring'}), true],
        ['CONTENT, cost class model', stage({id: 'CONTENT', costClasses: ['model']}), true],
        ['DEDUPE, free and model', stage({id: 'DEDUPE', costClasses: ['free', 'model']}), true],
        ['an llm ingest source sending the extraction prompt', stage({id: 'INGEST llm', kind: 'ingest', sourceId: 'llm', costClasses: ['network'], promptId: 'extraction'}), true],
        ['an html-blocks ingest source without a prompt', stage({id: 'INGEST html', kind: 'ingest', sourceId: 'html', costClasses: ['network']}), false],
        ['FILTER, free', stage({id: 'FILTER'}), false],
        ['PACKAGE, file', stage({id: 'PACKAGE', costClasses: ['file']}), false],
    ])('%s -> %s', (_name, input, expected) => {
        expect(isAiStage(input)).toBe(expected);
    });
});
