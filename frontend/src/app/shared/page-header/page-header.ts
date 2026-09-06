import {ChangeDetectionStrategy, Component, input} from '@angular/core';

@Component({
    selector: 'lg-page-header',
    templateUrl: './page-header.html',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PageHeader {
    readonly title = input.required<string>();
    /** One line saying what the screen is for. Omitted when the title is enough. */
    readonly subtitle = input<string | null>(null);

    /**
     * Which heading level the title is. `h1` is the screen's own title and the default; `h2`
     * is for a header rendered inside another screen — the shortlist's detail column, where a
     * second `h1` would claim to be the page while "Shortlist" is standing beside it, and
     * would shout at 2rem next to a 26rem scan column.
     */
    readonly heading = input<'h1' | 'h2'>('h1');
}
