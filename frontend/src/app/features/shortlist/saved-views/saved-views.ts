import {ChangeDetectionStrategy, Component, ElementRef, inject, input, output, signal, viewChild} from '@angular/core';
import {TranslocoPipe} from '@jsverse/transloco';
import {injectDispatch} from '@ngrx/signals/events';
import {filterViewEvents} from '@core/filter-views/filter-views.events';
import {FILTER_VIEW_NAME_MAX, FilterView} from '@core/filter-views/filter-view.model';
import {FilterViewsStore} from '@core/filter-views/filter-views.store';
import {Icon} from '@shared/icon/icon';
import {AnchorFor} from '@shared/popover/anchor-for';

/** One id per instance, because a popover is addressed by id. */
let panels = 0;

/**
 * Saved views: the screen as it stands, under a name.
 *
 * <p>In the page header beside the count and the archive toggle, and deliberately not down in
 * the filter column. A view is not a filter — it carries the order and which set is being
 * read as well — so it belongs where this screen's own statements are. It is also what keeps
 * the filter row from wrapping: three controls already want about 500 of the column's 540px.
 *
 * <p>Applying one is emitted upward rather than navigated here, because the page is the only
 * writer of the query string. Saving and deleting are dispatched straight to the store: they
 * touch this browser's own list and no URL at all.
 */
@Component({
  selector: 'lg-saved-views',
  imports: [AnchorFor, Icon, TranslocoPipe],
  templateUrl: './saved-views.html',
  styleUrl: './saved-views.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SavedViews {
  /** The query string as it stands, without its `?`. Empty means nothing is set. */
  readonly current = input.required<string>();

  readonly applied = output<string>();

  protected readonly store = inject(FilterViewsStore);
  private readonly dispatch = injectDispatch(filterViewEvents);

  protected readonly panelId = `lg-views-panel-${++panels}`;
  protected readonly open = signal(false);
  protected readonly nameMax = FILTER_VIEW_NAME_MAX;

  /**
   * Optional-called throughout, like the shortlist's own confirmation: jsdom implements
   * `HTMLDialogElement` as a bare `HTMLElement` carrying an `open` attribute and nothing
   * else, so an unguarded `showModal` takes down every spec that renders this.
   */
  private readonly naming = viewChild<ElementRef<HTMLDialogElement>>('naming');
  private readonly field = viewChild<ElementRef<HTMLInputElement>>('nameField');

  protected onToggle(event: Event): void {
    this.open.set((event as ToggleEvent).newState === 'open');
  }

  protected apply(view: FilterView, panel: HTMLElement): void {
    this.applied.emit(view.query);
    panel.hidePopover?.();
  }

  protected remove(view: FilterView): void {
    this.dispatch.removed(view.id);
  }

  /**
   * The popover closes and the dialog opens. Both would otherwise stand in the top layer at
   * once, with the panel visible behind a modal that has made the rest of the page inert.
   */
  protected askForName(panel: HTMLElement): void {
    panel.hidePopover?.();
    this.naming()?.nativeElement.showModal?.();
  }

  protected save(): void {
    const name = this.field()?.nativeElement.value.trim() ?? '';
    if (name === '') {
      return;
    }
    this.dispatch.saved({name, query: this.current()});
    this.close();
  }

  protected close(): void {
    const field = this.field()?.nativeElement;
    if (field) {
      field.value = '';
    }
    this.naming()?.nativeElement.close?.();
  }
}
