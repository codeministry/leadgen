import {ApplicationStatus} from './application';

/**
 * Who wrote the letter that stands in the package now.
 *
 * `model` is a draft the guard accepted, `template` the fallback the build rendered when
 * there was no model or its draft was refused, and `edited` a letter somebody saved by hand —
 * the one a rebuild never overwrites.
 */
export type CoverLetterAuthor = 'model' | 'template' | 'edited';

/** Mirrors the `{text, author, at}` answer of `/api/v1/offers/{id}/cover-letter`. */
export interface CoverLetter {
    readonly text: string;
    readonly author: CoverLetterAuthor;
    /** ISO instant of the last write, by whichever author. */
    readonly at: string;
}

/**
 * The states in which an application has a letter at all: `PACKAGED` and everything after it.
 * Before it there is no folder, and after an archive the application comes back at `NEW`
 * without one — so the list is the answer, not a guess about the order of the enum.
 */
const LETTER_STATES: ReadonlySet<ApplicationStatus> = new Set<ApplicationStatus>([
    'PACKAGED',
    'SENT',
    'REPLIED',
    'INTERVIEW',
    'OFFER',
    'WON',
    'LOST',
    'REJECTED',
    'EXPIRED',
]);

export function hasLetter(status: ApplicationStatus): boolean {
    return LETTER_STATES.has(status);
}

/**
 * Whether the letter may still change. Only while it is `PACKAGED`: from `SENT` on the letter
 * is what went out, and the server answers an edit or a redraft with 409.
 */
export function letterEditable(status: ApplicationStatus): boolean {
    return status === 'PACKAGED';
}
