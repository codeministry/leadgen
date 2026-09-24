import {ChangeDetectionStrategy, Component, computed, input, linkedSignal, output} from '@angular/core';
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
 * into events. It renders nothing before `PACKAGED`, because before that there is no folder
 * and so no letter, and it is read-only from `SENT` on, because the letter is then what was
 * sent — the server refuses both writes with 409 there, and a button it would refuse is not
 * offered.
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

    protected setDraft(event: Event): void {
        this.draft.set((event.target as HTMLTextAreaElement).value);
    }

    protected save(): void {
        this.saved.emit(this.draft());
    }
}
