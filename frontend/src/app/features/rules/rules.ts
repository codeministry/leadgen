import { ChangeDetectionStrategy, Component, OnInit, computed, inject } from '@angular/core';
import { injectDispatch } from '@ngrx/signals/events';
import { configEvents } from '@core/store/config.events';
import { ConfigStore } from '@core/store/config.store';
import { Badge } from '@shared/badge/badge';
import { Icon } from '@shared/icon/icon';
import { PageHeader } from '@shared/page-header/page-header';

@Component({
  selector: 'lg-rules',
  imports: [Badge, Icon, PageHeader],
  templateUrl: './rules.html',
  styleUrl: './rules.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Rules implements OnInit {
  private readonly dispatch = injectDispatch(configEvents);
  protected readonly store = inject(ConfigStore);

  ngOnInit(): void {
    this.dispatch.rulesOpened();
  }

  /**
   * A rule stating a single number reads as a line; a rule stating thirty cities does not.
   * The split is on what the rule carries, never on its key — a new list rule joins the
   * lists without a change here.
   */
  protected readonly scalarKnockouts = computed(() =>
    (this.store.rules()?.knockouts ?? []).filter((rule) => rule.values.length === 0),
  );

  protected readonly listKnockouts = computed(() =>
    (this.store.rules()?.knockouts ?? []).filter((rule) => rule.values.length > 0),
  );

  /** Weights are an open map in matching-rules.yaml, so the bar is relative to the largest. */
  protected readonly maxWeight = computed(() =>
    Math.max(...(this.store.rules()?.weights ?? []).map((weight) => weight.points), 1),
  );

  protected readonly maxPenalty = computed(() =>
    Math.max(...(this.store.rules()?.penalties ?? []).map((penalty) => Math.abs(penalty.points)), 1),
  );
}
