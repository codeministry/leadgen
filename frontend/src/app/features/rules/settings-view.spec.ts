import {WorkflowSetting} from '@core/model/workflow';
import {listItems, settingsView, splitKey} from './settings-view';

function setting(key: string, value = 'x', file = 'matching-rules.yaml'): WorkflowSetting {
    return {key, value, file};
}

describe('splitKey', () => {
    it('splits at the last dot: the prefix is the subgroup, the last segment the label', () => {
        expect(splitKey('hard_filters.remote.reject_keywords_de')).toEqual({
            prefix: 'hard_filters.remote',
            label: 'reject_keywords_de',
        });
    });

    it('keeps the list marker on the prefix, so the label is the field of each element', () => {
        expect(splitKey('hard_filters.remote.derive_from[].field')).toEqual({
            prefix: 'hard_filters.remote.derive_from[]',
            label: 'field',
        });
    });

    it('gives a key without a dot no prefix', () => {
        expect(splitKey('enabled')).toEqual({prefix: null, label: 'enabled'});
    });
});

describe('listItems', () => {
    it('splits a bracketed, comma-separated value into trimmed items', () => {
        expect(listItems('[100% vor Ort, onsite , vor Ort]')).toEqual(['100% vor Ort', 'onsite', 'vor Ort']);
    });

    it('reads an empty list as no items, not as one empty item', () => {
        expect(listItems('[]')).toEqual([]);
        expect(listItems('[  ]')).toEqual([]);
    });

    it('does not split at a comma nested inside inner brackets or braces', () => {
        expect(listItems('[[a, b], {c: 1, d: 2}, e]')).toEqual(['[a, b]', '{c: 1, d: 2}', 'e']);
    });

    it('leaves a scalar, or a value that only starts or ends with a bracket, alone', () => {
        expect(listItems('true')).toBeNull();
        expect(listItems('a, b')).toBeNull();
        expect(listItems('[a, b')).toBeNull();
        expect(listItems('a]')).toBeNull();
    });
});

describe('settingsView', () => {
    it('groups by file in first-appearance order and counts the entries of each', () => {
        const files = settingsView([
            setting('a.b', 'x', 'matching-rules.yaml'),
            setting('c.d', 'x', 'pipeline.yaml'),
            setting('a.e', 'x', 'matching-rules.yaml'),
        ]);

        expect(files.map((f) => [f.file, f.count])).toEqual([
            ['matching-rules.yaml', 2],
            ['pipeline.yaml', 1],
        ]);
    });

    it('groups a file by key prefix, prefixes in first-appearance order, keys without one first', () => {
        const [file] = settingsView([
            setting('hard_filters.remote.reject_keywords_de', '[vor Ort, onsite]'),
            setting('hard_filters.location.allowed', '[]'),
            setting('hard_filters.remote.derive_from[].field', '[location, title]'),
            setting('enabled', 'true'),
            setting('hard_filters.remote.accept_unknown', 'false'),
        ]);

        expect(file.subgroups.map((s) => s.prefix)).toEqual([
            null,
            'hard_filters.remote',
            'hard_filters.location',
            'hard_filters.remote.derive_from[]',
        ]);
        expect(file.subgroups[1].entries.map((e) => e.label)).toEqual(['reject_keywords_de', 'accept_unknown']);
    });

    it('keeps the full key and the raw value beside the label and the items', () => {
        const [file] = settingsView([setting('hard_filters.remote.reject_keywords_de', '[vor Ort, onsite]')]);

        expect(file.subgroups[0].entries[0]).toEqual({
            key: 'hard_filters.remote.reject_keywords_de',
            label: 'reject_keywords_de',
            value: '[vor Ort, onsite]',
            items: ['vor Ort', 'onsite'],
        });
    });

    it('returns nothing for no settings', () => {
        expect(settingsView([])).toEqual([]);
    });
});
