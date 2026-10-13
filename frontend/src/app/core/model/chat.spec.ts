import {RECORDED_MODELS} from '@core/api/chat-stub';

import {ChatConnection, ChatModels, hasModelChoice} from './chat';

/** A local primary connection listing these models, the first one flagged as the default when asked. */
function primary(names: readonly string[], flagFirst: boolean): ChatConnection {
    return {
        name: 'PRIMARY',
        kind: 'local',
        billed: false,
        reachable: true,
        reason: null,
        models: names.map((name, i) => ({name, defaultModel: flagFirst && i === 0, free: true, price: null})),
    };
}

describe('hasModelChoice', () => {
    it('is false without a catalog, and for an empty one', () => {
        expect(hasModelChoice(null)).toBe(false);
        expect(hasModelChoice({connections: [], defaultModelName: null})).toBe(false);
    });

    it('is false for one listed model that is the default', () => {
        const models: ChatModels = {connections: [primary(['model-a'], true)], defaultModelName: 'model-a'};
        expect(hasModelChoice(models)).toBe(false);
    });

    it('counts the default as an option of its own where no listed model is flagged as it', () => {
        const models: ChatModels = {connections: [primary(['model-a'], false)], defaultModelName: 'model-z'};
        expect(hasModelChoice(models)).toBe(true);
    });

    it('is true for more than one listed model', () => {
        expect(hasModelChoice(RECORDED_MODELS)).toBe(true);
        expect(hasModelChoice({connections: [primary(['model-a', 'model-b'], true)], defaultModelName: 'model-a'})).toBe(true);
    });
});
