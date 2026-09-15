import {ConfigLayer, SourceKind} from './source-summary';

/**
 * A piece of a configuration file, as it is written in it.
 *
 * The line range is what makes the excerpt checkable: a reader can open the file and find
 * exactly these lines, rather than trusting that this is what is in it.
 */
export interface YamlBlock {
  readonly text: string;
  readonly firstLine: number;
  readonly lastLine: number;
}

/**
 * What one source did on one run.
 *
 * A day and not an instant: the time of day a mailbox is read is the operator's working
 * hours, it is on a screen whose pictures get published, and nothing here needs it.
 */
export interface SourceRun {
  readonly ranOn: string;
  readonly documents: number;
  readonly extracted: number;
  /** Recorded since the run table existed and shown nowhere until this panel. */
  readonly written: number;
  readonly announced: number | null;
  /** `announced - extracted`, or null where the documents state no count. */
  readonly missing: number | null;
}

/**
 * When this source's numbers last moved.
 *
 * Data and not a sentence: the catalog picks the wording and puts the numbers where that
 * language puts them. The comparison itself is the server's, out of a `lag()` window, because
 * every number printed beside a list in this application is counted by the server.
 */
export interface SourceTrend {
  readonly extractedChangedOn: string | null;
  readonly extractedBefore: number | null;
  readonly extractedNow: number | null;
  readonly divergedOn: string | null;
  readonly missing: number | null;
  /** False is not a failure: most sources state no count, and saying so beats a blank. */
  readonly announcedStated: boolean;
}

/**
 * One source, opened: what defines it and what it has been doing.
 *
 * Fetched when a row is opened and never as part of the list, which is refetched on every
 * finished run and every tab focus. `block` and `connection` are file text with the secrets
 * already masked on the server; the browser never sees the unmasked file.
 */
export interface SourceDetail {
  readonly id: string;
  readonly kind: SourceKind;
  readonly enabled: boolean;
  readonly file: SourceFile;
  readonly block: YamlBlock | null;
  /** The connection this source names, because an imap source is half-defined elsewhere. */
  readonly connection: YamlBlock | null;
  readonly runs: readonly SourceRun[];
  readonly trend: SourceTrend;
}

/**
 * Which file defines the source, and where it was read from.
 *
 * `origin` is a path on somebody's machine, which is why it is here and not on the list's
 * envelope: this payload is one deliberate click away, that one is behind every screenshot.
 */
export interface SourceFile {
  readonly name: string;
  readonly layer: ConfigLayer;
  readonly origin: string | null;
}
