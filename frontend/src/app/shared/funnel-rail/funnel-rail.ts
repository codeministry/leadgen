import {
  ChangeDetectionStrategy,
  Component,
  DOCUMENT,
  afterNextRender,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { FunnelStage } from './funnel-stage';

interface RailSegment {
  readonly stage: FunnelStage;
  readonly remaining: number;
  readonly widthPercent: number;
  readonly removedPercent: number;
}

/**
 * The one loud thing in the app: 1,289 offers in, a couple of hundred out, and
 * the five stages that did the removing. The mark in the header is the same
 * shape at 28px.
 */
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

@Component({
  selector: 'lg-funnel-rail',
  imports: [TranslocoPipe],
  templateUrl: './funnel-rail.html',
  styleUrl: './funnel-rail.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FunnelRail {
  private readonly transloco = inject(TranslocoService);
  readonly stages = input.required<readonly FunnelStage[]>();
  readonly total = input.required<number>();
  /** A catalog key, not a sentence — `shared/` holds no prose either. */
  readonly survivorLabel = input('funnel.survived');

  /**
   * Width tracks what is *left* after each stage rather than what the stage
   * removed, so the rail physically narrows down the page. The drop count is the
   * label; the geometry is the remainder.
   */
  protected readonly segments = computed<readonly RailSegment[]>(() => {
    const total = this.total();
    let remaining = total;

    return this.stages().map((stage) => {
      remaining -= stage.removed;
      return {
        stage,
        remaining,
        widthPercent: total === 0 ? 0 : (remaining / total) * 100,
        removedPercent: total === 0 ? 0 : (stage.removed / total) * 100,
      };
    });
  });

  protected readonly survived = computed(() =>
    this.stages().reduce((left, stage) => left - stage.removed, this.total()),
  );

  protected readonly survivedPercent = computed(() => {
    const total = this.total();
    return total === 0 ? 0 : (this.survived() / total) * 100;
  });

  /**
   * The one orchestrated moment in the app. Every bar starts at full width and
   * settles into what is left after its stage, staggered down the rail, so the
   * narrowing is something you watch rather than something you read.
   *
   * It has to be two states, not one: a CSS transition fires on a *change*, and
   * a width rendered correctly the first time never changes. `afterNextRender`
   * plus one frame is what guarantees the browser painted the full-width state
   * before the real one arrives.
   */
  protected readonly revealed = signal(false);

  /**
   * The reader's own locale, not a pinned one. A German session used to get English
   * grouping on the loudest numbers in the app, because the format was fixed at `en-GB`
   * while everything around it followed the language toggle.
   */
  protected readonly formatter = computed(() => new Intl.NumberFormat(this.transloco.getActiveLang()));

  constructor() {
    const view = inject(DOCUMENT).defaultView;

    afterNextRender(() => {
      // Reduced motion gets the finished rail, not a faster animation. Skipping
      // the frame as well avoids a visible jump from full width to the target.
      const reduced =
        typeof view?.matchMedia === 'function' &&
        view.matchMedia('(prefers-reduced-motion: reduce)').matches;

      if (reduced) {
        this.revealed.set(true);
        return;
      }

      view?.requestAnimationFrame(() => this.revealed.set(true));
    });
  }

  protected format(value: number): string {
    return this.formatter().format(value);
  }

  protected percent(value: number): string {
    return `${value.toFixed(1)} %`;
  }
}
