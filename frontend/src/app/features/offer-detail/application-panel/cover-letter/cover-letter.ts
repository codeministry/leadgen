import {ChangeDetectionStrategy, Component, computed, input, linkedSignal, output, signal} from '@angular/core';
import {TranslocoPipe} from '@jsverse/transloco';
import {ApplicationStatus} from '@core/model/application';
import {CoverLetter, CoverLetterAuthor, hasLetter, letterEditable} from '@core/model/cover-letter';
import {Badge, BadgeTone} from '@shared/badge/badge';
import {DayPipe} from '@shared/date/day.pipe';
import {Icon} from '@shared/icon/icon';

/**
 * The badge per author, spelled out. `template` takes the warning tone because it is the
 * fallback: a letter the template wrote means the model had nothing to say or said something
 * the guard refused, and that is worth a second look before it goes out.
 */
const AUTHOR_TONE: Readonly<Record<CoverLetterAuthor, BadgeTone>> = {
    model: 'neutral',
    template: 'warning',
    edited: 'success',
};

/**
 * The letter in the package, where it can still be changed before it goes out.
 *
 * Presentational: the offer detail feeds it from `CoverLetterStore` and turns its two outputs
 * into events. It renders nothing without a folder, because then there is no letter, and it is
 * read-only from `SENT` on, because the letter is then what was sent: only Copy is offered
 * there, since the server refuses both writes with 409. Before `SENT` — `NEW` included, when
 * an application was moved back with its folder — Edit and Redraft stand beside Copy.
 *
 * The draft is a `linkedSignal` of the stored letter, the same as the application panel's
 * form: the store replaces the letter with the server's answer after a save or a redraft,
 * which resets the textarea to what is actually in `cover_letter.txt`.
 */
@Component({
    selector: 'lg-cover-letter',
    imports: [Badge, DayPipe, Icon, TranslocoPipe],
    templateUrl: './cover-letter.html',
    styleUrl: './cover-letter.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CoverLetterSection {
    readonly status = input.required<ApplicationStatus>();
    /** A package folder exists; a sent-then-restored application at `NEW` still has one. */
    readonly hasPackage = input(false);
    readonly letter = input<CoverLetter | null>(null);
    readonly loading = input(false);
    readonly saving = input(false);
    readonly drafting = input(false);
    /** A catalog key or the server's sentence, shown beside the controls. */
    readonly error = input<string | null>(null);

    readonly saved = output<string>();
    readonly regenerate = output<void>();

    protected readonly shown = computed(() => hasLetter(this.status()) || this.hasPackage());
    // The server says whether the letter went out; the status is only the guess before it answered.
    protected readonly readOnly = computed(() => this.letter()?.frozen ?? !letterEditable(this.status()));
    protected readonly busy = computed(() => this.saving() || this.drafting());

    protected readonly draft = linkedSignal(() => this.letter()?.text ?? '');
    protected readonly dirty = computed(() => {
        const letter = this.letter();
        return letter !== null && this.draft() !== letter.text;
    });

    protected readonly tone = computed(() => AUTHOR_TONE[this.letter()?.author ?? 'template']);

    /**
     * Reading, not editing, is the first state: the letter is usually copied into a mail as it
     * stands. Any new answer from the server — a save, a redraft — closes the editor again.
     */
    protected readonly editing = linkedSignal(() => {
        this.letter();
        return false;
    });

    /** Paragraphs split on blank lines, lines kept: how the letter reads in a mail. */
    protected readonly paragraphs = computed(() =>
        (this.letter()?.text ?? '')
            .trim()
            .split(/\n\s*\n/)
            .map((paragraph) => paragraph.split('\n')),
    );

    protected readonly copied = signal<'ok' | 'failed' | null>(null);

    /**
     * Onto the clipboard as HTML and as plain text at once, so a mail client pastes paragraphs
     * and a plain field still gets the text. Inline feedback, not a toast: nothing was written.
     */
    protected async copy(): Promise<void> {
        const text = this.letter()?.text ?? '';
        const html = this.paragraphs()
            .map((lines) => `<p>${lines.map(escapeHtml).join('<br>')}</p>`)
            .join('');
        try {
            if (typeof ClipboardItem === 'function' && navigator.clipboard.write) {
                await navigator.clipboard.write([
                    new ClipboardItem({
                        'text/html': new Blob([html], {type: 'text/html'}),
                        'text/plain': new Blob([text], {type: 'text/plain'}),
                    }),
                ]);
            } else {
                await navigator.clipboard.writeText(text);
            }
            this.copied.set('ok');
        } catch {
            this.copied.set('failed');
        }
    }

    protected edit(): void {
        this.copied.set(null);
        this.editing.set(true);
    }

    protected closeEditor(): void {
        this.draft.set(this.letter()?.text ?? '');
        this.editing.set(false);
    }

    protected setDraft(event: Event): void {
        this.draft.set((event.target as HTMLTextAreaElement).value);
    }

    protected save(): void {
        this.saved.emit(this.draft());
    }
}

function escapeHtml(text: string): string {
    return text.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
}
