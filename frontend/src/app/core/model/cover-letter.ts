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
    /** The application has ever been sent: the server refuses a save and a redraft. Its reading, not the status. */
    readonly frozen: boolean;
}

/**
 * The states in which an application has a letter at all: `PACKAGED` and everything after it.
 * Before it there is no folder. An application restored after an archive stands at `NEW`: it
 * has no letter if it was never sent, and keeps its folder and letter if it was — which is why
 * the section also shows whenever a package exists, not only by this list.
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
