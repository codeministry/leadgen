import {ChangeDetectionStrategy, Component, computed, inject, OnInit} from '@angular/core';
import {injectDispatch} from '@ngrx/signals/events';
import {TranslocoPipe} from '@jsverse/transloco';
import {configEvents} from '@core/store/config.events';
import {ConfigStore} from '@core/store/config.store';
import {AnchorRail, AnchorSection} from '@shared/anchor-rail/anchor-rail';
import {Badge} from '@shared/badge/badge';
import {Icon} from '@shared/icon/icon';
import {PageHeader} from '@shared/page-header/page-header';

/** The rail's sections; the prompts join them only when a model's prompts are configured. */
export const RULES_SECTIONS: readonly AnchorSection[] = [
    {id: 'knockouts', key: 'rules.knockouts'},
    {id: 'thresholds', key: 'rules.thresholds'},
    {id: 'weights', key: 'rules.weights'},
];

export const RULES_PROMPTS_SECTION: AnchorSection = {id: 'prompts', key: 'rules.prompts'};

@Component({
    selector: 'lg-rules',
    imports: [AnchorRail, Badge, Icon, PageHeader, TranslocoPipe],
    templateUrl: './rules.html',
    styleUrl: './rules.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Rules implements OnInit {
    private readonly dispatch = injectDispatch(configEvents);
    protected readonly store = inject(ConfigStore);

    protected readonly sections = computed((): readonly AnchorSection[] => {
        if (!this.store.rules()) {
            return [];
        }
        return this.store.prompts().length > 0 ? [...RULES_SECTIONS, RULES_PROMPTS_SECTION] : RULES_SECTIONS;
    });

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
        Math.max(
            ...(this.store.rules()?.penalties ?? []).map((penalty) => Math.abs(penalty.points)),
            1,
        ),
    );
}
