import {ChangeDetectionStrategy, Component, input} from '@angular/core';

@Component({
    selector: 'lg-page-header',
    templateUrl: './page-header.html',
    styleUrl: './page-header.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
    // A screen's own title row keeps the shell's measure whatever the content under it does
    // (operator, 2026-09-26) — see the `:host(.is-page-title)` rule. An `h2` or `h3` header sits
    // inside a screen and takes its container's width, as it always did.
    host: {'[class.is-page-title]': "heading() === 'h1'"},
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
     *
     * <p>`h3` is the third step, and it exists because two screens had already worked around
     * not having it: a panel that opens *inside* a screen sits under that screen's own `h2`,
     * so an `h2` there breaks the outline and an `h2`-sized title shouts over the table it
     * belongs to. Without it the sources panel set `type-h3` on a bare heading by hand, which
     * is this component's job done in a template.
     */
    readonly heading = input<'h1' | 'h2' | 'h3'>('h1');

    /**
     * The title takes the whole row and the actions start a row of their own under it. Off,
     * the title only gets what the actions leave, so where they wrap anyway — the offer detail
     * in a split column — a long title broke after a few words beside empty space.
     */
    readonly stacked = input(false);
}
