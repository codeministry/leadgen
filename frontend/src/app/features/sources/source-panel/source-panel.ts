import {ChangeDetectionStrategy, Component, computed, effect, inject, input, OnDestroy, signal} from '@angular/core';
import {TranslocoPipe} from '@jsverse/transloco';
import {injectDispatch} from '@ngrx/signals/events';
import {SourceRun} from '@core/model/source-detail';
import {configEvents} from '@core/store/config.events';
import {ConfigStore} from '@core/store/config.store';
import {DayPipe} from '@shared/date/day.pipe';
import {Badge} from '@shared/badge/badge';
import {Icon} from '@shared/icon/icon';
import {hljs} from '@shared/markdown/highlight';

/**
 * One history row, with how far it moved from the run before it.
 *
 * <p>The delta is arithmetic between two adjacent rows of a list the server ordered, not a
 * second opinion about what "changed" means — that judgement is the server's `lag()`, and it
 * arrives as the sentence above the table.
 */
interface RunRow {
  readonly run: SourceRun;
  readonly extractedDelta: number | null;
  readonly writtenDelta: number | null;
}

/**
 * A source, opened: the block of `sources.yaml` that defines it, and the runs it has had.
 *
 * <p><b>A routed panel below the table rather than an overlay.</b> The screen already had
 * about 565px of empty page under a table that is 300px tall, full content width, directly
 * under the row that was clicked — which is the shape a 59-line block and a 30-row history
 * needs. An expanding row would have put that document inside `.table-scroll`, which already
 * scrolls sideways on a phone, and a modal would make the table inert in order to read about
 * the table. It also cannot be mistaken for a control that writes, which on this screen is the
 * property that decides.
 *
 * <p><b>Nothing here writes.</b> The application never edits a configuration file, and six
 * places in this repository say so deliberately.
 */
@Component({
  selector: 'lg-source-panel',
  imports: [Badge, DayPipe, Icon, TranslocoPipe],
  templateUrl: './source-panel.html',
  styleUrl: './source-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SourcePanel implements OnDestroy {
  /**
   * Which source, from the route. The transform is not optional: router input binding writes
   * `undefined` for a parameter it cannot find, overriding the declared default, and the
   * first read of it throws inside the template with nothing in the console pointing here.
   */
  readonly id = input('', {transform: (value: string | undefined) => value ?? ''});

  protected readonly store = inject(ConfigStore);
  private readonly dispatch = injectDispatch(configEvents);

  /**
   * Which block is unfolded, as an id and not a boolean.
   *
   * <p>The component is reused across ids, so a boolean would survive the navigation and the
   * next source would open unfolded for a decision nobody made about it.
   */
  private readonly unfoldedId = signal<string | null>(null);

  constructor() {
    effect(() => {
      const id = this.id();
      if (id !== '') {
        this.dispatch.sourceOpened(id);
      }
    });
  }

  ngOnDestroy(): void {
    // The panel is gone, so the block behind it goes too: kept, it is what the next source
    // would open with for a frame.
    this.dispatch.sourceClosed();
  }

  protected readonly detail = computed(() => this.store.detail());

  /** True while the panel is showing the source the route asked for. */
  protected readonly ready = computed(() => this.detail()?.id === this.id());

  protected readonly unfolded = computed(() => this.unfoldedId() === this.id());

  /**
   * How many lines the block has. A count and never a measured height: `scrollHeight` against
   * a clamp and a `ResizeObserver` are both suspended in a backgrounded tab, so a toggle
   * decided that way is missing exactly where a screenshot says the page is fine.
   */
  protected readonly lineCount = computed(() => this.detail()?.block?.text.split('\n').length ?? 0);

  protected readonly foldable = computed(() => this.lineCount() > SourcePanel.FOLD_ABOVE);

  private static readonly FOLD_ABOVE = 24;

  /**
   * The block, highlighted. The registry is the shared one — highlight.js ships close to two
   * hundred grammars and this app registers the handful it uses, `yaml` among them.
   *
   * <p>The text arrives already masked. Masking is the server's, or the unmasked file would
   * be in the network tab; and it happens before highlighting, so the mask paints as an
   * ordinary scalar and nothing here has to post-process highlight.js's output.
   */
  protected readonly blockHtml = computed(() => this.highlight(this.detail()?.block?.text));

  protected readonly connectionHtml = computed(() => this.highlight(this.detail()?.connection?.text));

  private highlight(text: string | undefined): string {
    return text === undefined || text === ''
      ? ''
      : hljs.highlight(text, {language: 'yaml', ignoreIllegals: true}).value;
  }

  /**
   * The runs, each with how far it moved from the one before it. The list is newest first, so
   * the run before a row is the row after it.
   */
  protected readonly rows = computed<readonly RunRow[]>(() => {
    const runs = this.detail()?.runs ?? [];
    return runs.map((run, index) => {
      const previous = runs[index + 1];
      return {
        run,
        extractedDelta: previous === undefined ? null : run.extracted - previous.extracted,
        writtenDelta: previous === undefined ? null : run.written - previous.written,
      };
    });
  });

  protected toggleFold(): void {
    this.unfoldedId.set(this.unfolded() ? null : this.id());
  }

  /** `+12` / `−12`, with the minus sign rather than a hyphen. */
  protected delta(value: number): string {
    return value > 0 ? `+${value}` : `−${Math.abs(value)}`;
  }
}
