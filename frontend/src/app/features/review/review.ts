import {
    ChangeDetectionStrategy,
    Component,
    computed,
    effect,
    ElementRef,
    inject,
    input,
    OnInit,
    signal,
    viewChild,
} from '@angular/core';
import {Router, RouterLink} from '@angular/router';
import {injectDispatch} from '@ngrx/signals/events';
import {TranslocoPipe} from '@jsverse/transloco';
import {ManualOfferFields} from '@core/model/manual-document';
import {manualEvents} from '@core/store/manual.events';
import {ManualStore} from '@core/store/manual.store';
import {EmptyState} from '@shared/empty-state/empty-state';
import {Icon} from '@shared/icon/icon';
import {PageHeader} from '@shared/page-header/page-header';
import {ReviewCard} from './review-card/review-card';

/**
 * What stands between an uploaded document and the shortlist.
 *
 * An upload lands in `pending/`, which no source globs. It becomes an offer only once
 * somebody has seen what the extraction made of it, because a pasted ad can be read wrong
 * and the shortlist is what gets trusted instead of the mailbox.
 */
@Component({
  selector: 'lg-review',
    imports: [EmptyState, Icon, PageHeader, ReviewCard, RouterLink, TranslocoPipe],
  templateUrl: './review.html',
  styleUrl: './review.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
    // Whether the right column is showing a document; the drop zone and the page header sit
    // outside the split and have to disappear with the queue on a narrow screen.
    host: {'[class.detail-open]': 'selected() !== undefined'},
})
export class Review implements OnInit {
  private readonly dispatch = injectDispatch(manualEvents);
    private readonly router = inject(Router);
  protected readonly store = inject(ManualStore);

    private readonly detailPane = viewChild<ElementRef<HTMLElement>>('detailPane');

    /**
     * Which document is open, as a query parameter rather than the path segment the other two
     * split views use. The difference is not a style: there the right column is a routed
     * component, so a child route has something to render; here the document is already in
     * the store this screen reads, and a child route would have to render a component whose
     * only job is to look up by name what the parent is holding. Angular refuses a
     * componentless leaf route outright (NG04014), which is the same point made by the
     * framework.
     *
     * <p>The transform is not optional. Router input binding writes `undefined` for a
     * parameter absent from the URL rather than leaving the declared default, and the first
     * read of it throws inside the template.
     */
    readonly doc = input('', {transform: (value: string | undefined) => value ?? ''});

    /**
     * A name and not an index: the queue shrinks with every confirm, and an index would then
     * point at whatever moved up.
     */
    protected readonly selectedName = computed(() => (this.doc() === '' ? null : this.doc()));

    /**
     * The document itself, or nothing. Undefined is a real state and not an error: the name
     * in the URL is a file, and a file that has been confirmed or rejected is gone.
     */
    protected readonly selected = computed(() =>
        this.store.documents().find((document) => document.name === this.selectedName()),
    );

  /** Only for the drop zone's own highlight; the queue is the store's business. */
  protected readonly dragging = signal(false);

  /**
   * Assembled here rather than interleaved with `@if` in the template. Control flow around
   * punctuation puts the template's own whitespace into the sentence, and the result read
   * "1 waiting for review , 1 already in the pipeline ." on the page.
   */
  /**
   * Assembled here rather than in the template, because punctuation around an `@if` picks
   * up the template's own whitespace and renders as "1 waiting for review , 1 already in
   * the pipeline .". Two keys and one join, and the sentence is the catalog's problem.
   */
  protected readonly summary = computed(() => ({
    key: this.store.duplicates() > 0 ? 'review.summaryWithDuplicates' : 'review.summary',
    params: { waiting: this.store.waiting(), duplicates: this.store.duplicates() },
  }));

    constructor() {
        // A different document starts at its own top, the same as the offer detail: the column
        // survives the navigation and would otherwise open where the last one was left.
        effect(() => {
            this.selectedName();
            const pane = this.detailPane()?.nativeElement;
            if (pane !== undefined) {
                pane.scrollTop = 0;
            }
        });
    }

  ngOnInit(): void {
    this.dispatch.opened();
  }

  protected pick(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.send(input.files);
    // Cleared so picking the same file twice fires a second change event.
    input.value = '';
  }

  protected drop(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
    this.send(event.dataTransfer?.files ?? null);
  }

  protected over(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(true);
  }

  protected leave(): void {
    this.dragging.set(false);
  }

    /**
     * Both of these end the document: confirming moves the file into the inbox and rejecting
     * deletes it, so the queue loses the row either way and the URL has to let go of the name
     * with it. `replaceUrl`, because a document that no longer exists is not a place the back
     * button should be able to return to.
     */
  protected confirm(name: string, fields: ManualOfferFields): void {
    this.dispatch.confirmed({ name, fields });
        this.closeDetail();
  }

  protected reject(name: string): void {
    this.dispatch.rejected(name);
      this.closeDetail();
  }

    private closeDetail(): void {
        void this.router.navigate([], {
            queryParams: {doc: null},
            queryParamsHandling: 'merge',
            replaceUrl: true,
        });
  }

  /**
   * Every file, one request each. The endpoint answers per document with what the
   * extraction read, and a batch endpoint would have to invent a shape for partial
   * failure.
   */
  private send(files: FileList | null): void {
    if (files === null) {
      return;
    }
    for (const file of Array.from(files)) {
      this.dispatch.uploaded(file);
    }
  }
}
