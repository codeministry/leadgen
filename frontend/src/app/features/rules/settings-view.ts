import {WorkflowSetting} from '@core/model/workflow';

/** One key as the settings list shows it: the last segment as the label, the rest kept. */
export interface SettingEntry {
    /** The full dotted key, kept so nothing is lost when only the label is on screen. */
    readonly key: string;
    /** The last segment of the key. */
    readonly label: string;
    /** The value exactly as the server rendered it. */
    readonly value: string;
    /** The items of a `[a, b]` value, `[]` for an empty list, null for anything else. */
    readonly items: readonly string[] | null;
}

/** The entries of one file that share a key prefix; `prefix` is null for keys without a dot. */
export interface SettingSubgroup {
    readonly prefix: string | null;
    readonly entries: readonly SettingEntry[];
}

/** One file's settings, subgrouped by key prefix. */
export interface SettingsFile {
    readonly file: string;
    readonly count: number;
    readonly subgroups: readonly SettingSubgroup[];
}

/**
 * Splits a key at its last dot. `hard_filters.remote.derive_from[].field` gives the prefix
 * `hard_filters.remote.derive_from[]` and the label `field`: the `[]` stays on the prefix, so
 * the subheading says "each element of this list" and the label names the field of one.
 */
export function splitKey(key: string): {prefix: string | null; label: string} {
    const dot = key.lastIndexOf('.');
    return dot < 0 ? {prefix: null, label: key} : {prefix: key.slice(0, dot), label: key.slice(dot + 1)};
}

/**
 * The items of a list value, or null when the value is not one.
 *
 * The rule, kept deliberately simple: a value is a list only when it starts with `[` and ends
 * with `]` (after trimming). The inside is split at commas that are not nested in a further
 * `[…]` or `{…}`, so `[[a, b], c]` is two items; each item is trimmed. An empty or blank
 * inside is the empty list. Quotes are not parsed — the server renders values unquoted.
 */
export function listItems(value: string): readonly string[] | null {
    const trimmed = value.trim();
    if (!trimmed.startsWith('[') || !trimmed.endsWith(']') || trimmed.length < 2) {
        return null;
    }
    const inner = trimmed.slice(1, -1);
    if (inner.trim() === '') {
        return [];
    }
    const items: string[] = [];
    let depth = 0;
    let start = 0;
    for (let i = 0; i < inner.length; i++) {
        const char = inner[i];
        if (char === '[' || char === '{') {
            depth++;
        } else if ((char === ']' || char === '}') && depth > 0) {
            depth--;
        } else if (char === ',' && depth === 0) {
            items.push(inner.slice(start, i).trim());
            start = i + 1;
        }
    }
    items.push(inner.slice(start).trim());
    return items;
}

/**
 * Groups settings by the file that won for each key (files in first-appearance order), then
 * each file by key prefix. Keys without a prefix come first, so they never read as sitting
 * under the subheading before them; the prefixes follow in first-appearance order.
 */
export function settingsView(settings: readonly WorkflowSetting[]): readonly SettingsFile[] {
    const files = new Map<string, Map<string | null, SettingEntry[]>>();
    for (const setting of settings) {
        let prefixes = files.get(setting.file);
        if (prefixes === undefined) {
            // The null bucket is created first so it keeps the first position.
            prefixes = new Map([[null, []]]);
            files.set(setting.file, prefixes);
        }
        const {prefix, label} = splitKey(setting.key);
        const entry: SettingEntry = {key: setting.key, label, value: setting.value, items: listItems(setting.value)};
        const bucket = prefixes.get(prefix);
        if (bucket === undefined) {
            prefixes.set(prefix, [entry]);
        } else {
            bucket.push(entry);
        }
    }
    return Array.from(files, ([file, prefixes]) => {
        const subgroups = Array.from(prefixes, ([prefix, entries]) => ({prefix, entries})).filter((s) => s.entries.length > 0);
        return {file, count: subgroups.reduce((sum, s) => sum + s.entries.length, 0), subgroups};
    });
}
