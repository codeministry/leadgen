import {ChangeDetectionStrategy, Component, ElementRef, signal, viewChild} from '@angular/core';
import {TranslocoPipe} from '@jsverse/transloco';
import {DayPipe} from '@shared/date/day.pipe';
import {Icon} from '@shared/icon/icon';

/** What the card says: the cited row, or why an id is not a link. */
export type CitePreview =
    | {readonly kind: 'cite'; readonly title: string; readonly source: string | null; readonly date: string | null}
    | {readonly kind: 'unverified'; readonly id: string};

let nextId = 0;

/**
 * The preview a citation shows on hover and focus (ISC-442): the cited row's title, source and
 * date without navigating, or, for an unverified id, the reason it is not a link.
 *
 * <p>A manual popover, so it sits in the top layer above the drawer and the page and needs no
 * z-index, and so the page — not the platform's light dismiss — decides when it goes: on leave, on
 * blur and on Escape. `role="tooltip"`, and the trigger points at it with `aria-describedby`
 * while it is open, because it describes the pill rather than replacing it.
 *
 * <p>Placed with the same two custom properties as `shared/popover/anchor-for`, written from the
 * trigger's rectangle at the moment it opens.
 */
@Component({
    selector: 'lg-citation-card',
    imports: [DayPipe, Icon, TranslocoPipe],
    templateUrl: './citation-card.html',
    styleUrl: './citation-card.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CitationCard {
    readonly id = `lg-chat-cite-card-${nextId++}`;
    protected readonly preview = signal<CitePreview | null>(null);
    private readonly panel = viewChild.required<ElementRef<HTMLElement>>('panel');
    private anchor: HTMLElement | null = null;

    open(anchor: HTMLElement, preview: CitePreview): void {
        const panel = this.panel().nativeElement;
        this.release();
        this.preview.set(preview);
        this.anchor = anchor;
        anchor.setAttribute('aria-describedby', this.id);
        const rect = anchor.getBoundingClientRect();
        panel.style.setProperty('--lg-anchor-x', `${Math.round(rect.left)}px`);
        panel.style.setProperty('--lg-anchor-y', `${Math.round(rect.bottom + 4)}px`);
        if (typeof panel.showPopover === 'function' && !panel.matches(':popover-open')) panel.showPopover();
    }

    close(): void {
        const panel = this.panel().nativeElement;
        this.release();
        this.preview.set(null);
        if (typeof panel.hidePopover === 'function' && panel.matches(':popover-open')) panel.hidePopover();
    }

    isOpen(): boolean {
        return this.preview() !== null;
    }

    private release(): void {
        this.anchor?.removeAttribute('aria-describedby');
        this.anchor = null;
    }
}
