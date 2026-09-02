import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { injectDispatch } from '@ngrx/signals/events';
import { TranslocoPipe } from '@jsverse/transloco';
import { ManualOfferFields } from '@core/model/manual-document';
import { manualEvents } from '@core/store/manual.events';
import { ManualStore } from '@core/store/manual.store';
import { EmptyState } from '@shared/empty-state/empty-state';
import { Icon } from '@shared/icon/icon';
import { PageHeader } from '@shared/page-header/page-header';
import { ReviewCard } from './review-card/review-card';

/**
 * What stands between an uploaded document and the shortlist.
 *
 * An upload lands in `pending/`, which no source globs. It becomes an offer only once
 * somebody has seen what the extraction made of it, because a pasted ad can be read wrong
 * and the shortlist is what gets trusted instead of the mailbox.
 */
@Component({
  selector: 'lg-review',
  imports: [EmptyState, Icon, PageHeader, ReviewCard, TranslocoPipe],
  templateUrl: './review.html',
  styleUrl: './review.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Review implements OnInit {
  private readonly dispatch = injectDispatch(manualEvents);
  protected readonly store = inject(ManualStore);

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

  protected confirm(name: string, fields: ManualOfferFields): void {
    this.dispatch.confirmed({ name, fields });
  }

  protected reject(name: string): void {
    this.dispatch.rejected(name);
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
