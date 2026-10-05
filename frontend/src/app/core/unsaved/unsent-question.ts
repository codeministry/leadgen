/**
 * The chat question last sent from the composer, until the server names its turn.
 *
 * <p>**In `sessionStorage`, so it survives a redirect.** A question refused or broken off before
 * the server stored it would otherwise exist nowhere once the field was emptied on send, and an
 * ended session takes the page through the identity provider. The composer brings it back on the
 * page as soon as the stream is closed, and after a sign-in when it is created again. It is the
 * person's own text, never a credential, and goes with the tab. Where the browser refuses storage
 * it is kept in memory, which survives everything but leaving the page.
 *
 * <p>Only the composer keeps one: a follow-up or a suggestion is a click away from being asked again.
 */
const KEY = 'leadgen.chat.unsent';

let memory: string | null = null;
/**
 * Whether the storage holds the kept question. True until a write fails on this page: after a reload
 * the storage is the only copy there is. A refused write (a full quota) may leave reads working, so
 * the storage is then read no more, or it would answer with nothing or an older question.
 */
let stored = true;

export function keepUnsent(question: string): void {
    memory = question;
    try {
        window.sessionStorage.setItem(KEY, question);
        stored = true;
    } catch {
        // Storage refused: the memory copy stands in until the page goes.
        stored = false;
    }
}

export function unsentQuestion(): string | null {
    if (!stored) return memory;
    try {
        return window.sessionStorage.getItem(KEY);
    } catch {
        return memory;
    }
}

/** Whether `question` is kept where it outlives the page — a sign-in's redirect, a reload. */
export function keptBeyondThePage(question: string): boolean {
    return stored && unsentQuestion() === question;
}

/** Forgets the kept question, or only `question` when given and it is not the kept one any more. */
export function forgetUnsent(question?: string): void {
    if (question !== undefined && unsentQuestion() !== question) return;
    memory = null;
    try {
        window.sessionStorage.removeItem(KEY);
        stored = true;
    } catch {
        // Storage refused: there was nothing in it to forget.
    }
}
