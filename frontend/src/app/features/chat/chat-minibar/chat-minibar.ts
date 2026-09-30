import {afterNextRender, ChangeDetectionStrategy, Component, DestroyRef, inject, input, output, signal} from '@angular/core';
import {TranslocoPipe} from '@jsverse/transloco';
import {Icon} from '@shared/icon/icon';
import {LivingMark} from '@shared/living-mark/living-mark';

/** The navigation's fixed bottom bar below 48rem, the edge this bar stands on. */
const BOTTOM_NAV = '.topnav';

/**
 * The chat sheet folded into a bar (ISC-441): below 48rem, following a source opens the page it
 * names and leaves the conversation one tap away, directly above the bottom navigation.
 *
 * <p>**It names the conversation and marks a turn still being written** with the living mark in
 * its working frame, the header button's own while the drawer is shut: the same ring, the same
 * `--lg-ai` on its flow dots, the same meaning. The whole bar is the one button that expands the
 * sheet again.
 *
 * <p>**It stands on the navigation's measured height**, not on a copied constant: that bar's
 * height comes from its labels, its padding and the safe-area inset, and a guessed offset either
 * floats above it or hides under it on a notched phone.
 *
 * <p>Presentational only: the panel decides when it shows and what expanding means, and `?chat`
 * is never touched here.
 */
@Component({
    selector: 'lg-chat-minibar',
    imports: [Icon, LivingMark, TranslocoPipe],
    templateUrl: './chat-minibar.html',
    styleUrl: './chat-minibar.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ChatMinibar {
    /** The conversation's title; empty for one not created yet, which reads as "New chat". */
    readonly name = input('');
    readonly streaming = input(false);
    readonly expanded = output<void>();

    protected readonly offset = signal(0);

    constructor() {
        const destroyRef = inject(DestroyRef);
        afterNextRender(() => {
            const nav = document.querySelector<HTMLElement>(BOTTOM_NAV);
            if (nav === null) return;
            const measure = () => this.offset.set(nav.getBoundingClientRect().height);
            measure();
            if (typeof ResizeObserver !== 'function') return;
            const observer = new ResizeObserver(measure);
            observer.observe(nav);
            destroyRef.onDestroy(() => observer.disconnect());
        });
    }
}
