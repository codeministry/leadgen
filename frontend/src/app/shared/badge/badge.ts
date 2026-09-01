import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

export type BadgeTone = 'neutral' | 'primary' | 'accent' | 'success' | 'warning' | 'error' | 'ghost';

/**
 * The class names are spelled out rather than built as `'badge-' + tone`.
 * Tailwind 4 scans source text for class names, so a class assembled at runtime
 * is never emitted — the badge then renders with no colour at all and nothing
 * anywhere reports a problem. Measured: seven of eight tones were missing from
 * the built stylesheet.
 */
const TONE_CLASS: Record<BadgeTone, string> = {
  neutral: 'badge-neutral',
  primary: 'badge-primary',
  accent: 'badge-accent',
  success: 'badge-success',
  warning: 'badge-warning',
  error: 'badge-error',
  ghost: 'badge-ghost',
};

/**
 * Ochre (`accent`) means one thing across the whole app: this cleared the
 * shortlist threshold. Anything else that wants attention takes `warning`.
 */
@Component({
  selector: 'lg-badge',
  templateUrl: './badge.html',
  styleUrl: './badge.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Badge {
  readonly tone = input<BadgeTone>('neutral');
  readonly outline = input(false);

  protected readonly classes = computed(() => {
    const classes = ['badge', 'badge-sm', 'lg-badge-text', TONE_CLASS[this.tone()]];
    if (this.outline()) {
      classes.push('badge-outline');
    }
    return classes.join(' ');
  });
}
