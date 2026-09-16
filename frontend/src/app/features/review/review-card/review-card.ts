import {ChangeDetectionStrategy, Component, computed, input, linkedSignal, output,} from '@angular/core';
import {TranslocoPipe} from '@jsverse/transloco';
import {Badge} from '@shared/badge/badge';
import {MarkdownSource} from '@shared/markdown/markdown-source';
import {ManualOfferFields, PendingDocument} from '@core/model/manual-document';

/**
 * One upload, with what the extraction read beside the text it read it from.
 *
 * <p>The fields are editable because they have to be: a key spelled differently in the
 * frontmatter is read and then ignored, and a pasted ad has no frontmatter at all. This is
 * the step between a wrong extraction and the shortlist, which is the one list that gets
 * trusted instead of the mailbox.
 */
@Component({
    selector: 'lg-review-card',
  imports: [Badge, MarkdownSource, TranslocoPipe],
    templateUrl: './review-card.html',
    styleUrl: './review-card.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReviewCard {
    readonly document = input.required<PendingDocument>();
    readonly busy = input(false);
    readonly confirmed = output<ManualOfferFields>();
    readonly rejected = output<void>();

    /** Seeded from the extraction and reset whenever the server hands back a new reading. */
    protected readonly title = linkedSignal(() => this.document().offer?.title ?? '');
    protected readonly url = linkedSignal(() => this.document().offer?.url ?? '');
    protected readonly location = linkedSignal(() => this.document().offer?.location ?? '');
    protected readonly portal = linkedSignal(() => this.document().offer?.portal ?? '');
    protected readonly agency = linkedSignal(() => this.document().offer?.agency ?? '');
    protected readonly published = linkedSignal(() => this.document().offer?.publishedOn ?? '');
    protected readonly tags = linkedSignal(() => (this.document().offer?.tags ?? []).join(', '));
    protected readonly description = linkedSignal(() => this.document().offer?.description ?? '');

    /** Without a title there is no offer: the pipeline drops a block that has none. */
    protected readonly ready = computed(() => this.title().trim().length > 0);

  /** Whether anything at all was read by a model, which is what the note above the form says. */
  protected readonly readByModel = computed(() => (this.document().fromModel ?? []).length > 0);

  /**
   * Whether this one field was read by a model rather than by the frontmatter rule.
   *
   * The name is the server's, not this form's: the date field is `published` there and
   * `publishedOn` on the offer beside it, and passing the wrong one would mark nothing
   * while looking entirely correct.
   */
  protected fromModel(field: string): boolean {
    return (this.document().fromModel ?? []).includes(field);
  }

    protected set(target: { set: (value: string) => void }, event: Event): void {
        target.set((event.target as HTMLInputElement | HTMLTextAreaElement).value);
    }

    protected confirm(): void {
        this.confirmed.emit({
            title: this.title().trim(),
            url: blankToNull(this.url()),
            description: blankToNull(this.description()),
            location: blankToNull(this.location()),
            portal: blankToNull(this.portal()),
            agency: blankToNull(this.agency()),
            published: blankToNull(this.published()),
            tags: this.tags()
                .split(',')
                .map((tag) => tag.trim())
                .filter((tag) => tag.length > 0),
        });
    }
}

/** An empty field means "not stated", never an empty string in the frontmatter. */
function blankToNull(value: string): string | null {
    const trimmed = value.trim();
    return trimmed.length === 0 ? null : trimmed;
}
