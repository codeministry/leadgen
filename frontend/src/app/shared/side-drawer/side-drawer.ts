import {NgTemplateOutlet} from '@angular/common';
import {
    afterNextRender,
    ChangeDetectionStrategy,
    Component,
    computed,
    DestroyRef,
    effect,
    ElementRef,
    inject,
    Injector,
    input,
    output,
    signal,
    viewChild,
} from '@angular/core';

const WIDE = '(width >= 48rem)';

/**
 * A drawer from the inline end, shared by the help and the chat (DS-APP-26/27/34).
 *
 * <p>Two modalities behind one frame. Below 48rem, and for a drawer that never docks, it is a
 * native `<dialog>` opened with `showModal()`: the top layer gives the backdrop, the inert page
 * and the focus trap with no script of ours. With `dock` set, from 48rem it is a plain
 * complementary panel instead — no backdrop, no trap — which the shell lays out as a second
 * column so the page reflows beside it and stays usable (ISC-432). Placement, the slide-in and
 * the focus handover are the same in both, and that sameness is what this primitive is for.
 *
 * <p>The consumer's content is projected once and stamped into whichever frame is current, so a
 * resize across 48rem while it is open moves the content rather than rebuilding it.
 */
@Component({
    selector: 'lg-side-drawer',
    imports: [NgTemplateOutlet],
    templateUrl: './side-drawer.html',
    styleUrl: './side-drawer.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SideDrawer {
    /** From 48rem a docked, non-modal panel; below it, and when false, a modal dialog. */
    readonly dock = input(false);
    /** The id of the heading that names the drawer. */
    readonly labelledBy = input.required<string>();
    /**
     * The consumer's width, a length or a `var()`; capped at the window either way. It may change
     * while the drawer is open — the chat's folds with its conversation rail (ISC-445) — and the
     * frame follows at once, with no transition: a consumer whose content leaves at once, as the
     * rail does, would be squeezed for the length of one.
     */
    readonly width = input.required<string>();
    /** A class of the consumer's own on the frame, for its specs and its stylesheet's reach. */
    readonly frameClass = input('');

    protected readonly frameWidth = computed(() => `min(${this.width()}, 100vw)`);

    /** Escape, the backdrop, or Escape inside the docked panel: the consumer decides to close. */
    readonly dismissed = output<void>();
    /** After it closed and focus went back. */
    readonly closed = output<void>();

    private readonly injector = inject(Injector);
    private readonly wide = signal(typeof matchMedia === 'function' && matchMedia(WIDE).matches);
    protected readonly docked = computed(() => this.dock() && this.wide());
    readonly isOpen = signal(false);

    private readonly dialog = viewChild<ElementRef<HTMLDialogElement>>('dialog');
    private trigger: HTMLElement | null = null;

    constructor() {
        if (typeof matchMedia === 'function') {
            const query = matchMedia(WIDE);
            const onChange = (event: MediaQueryListEvent) => {
                this.wide.set(event.matches);
                // A resize across the breakpoint while open: the other frame now holds the content.
                if (this.isOpen() && !this.docked()) {
                    afterNextRender(() => this.dialog()?.nativeElement.showModal?.(), {injector: this.injector});
                }
            };
            query.addEventListener('change', onChange);
            this.destroyRef.onDestroy(() => query.removeEventListener('change', onChange));
        }

        // The panel fills the dialog, so a click on the dialog element itself is the backdrop's.
        // Attached to every dialog the template stamps (fix 5F-2): with `dock` set, the `@if` makes a
        // new `<dialog>` on each breakpoint change, and a listener attached once reached the first
        // one only. A script listener rather than a template `(click)`: the backdrop is a pointer
        // shortcut, Escape (`cancel`) is its keyboard equivalent, and the dialog is no control.
        effect((onCleanup) => {
            const dialog = this.dialog()?.nativeElement;
            if (!dialog) return;
            const onClick = (event: MouseEvent) => {
                if (event.target === dialog) this.dismissed.emit();
            };
            dialog.addEventListener('click', onClick);
            onCleanup(() => dialog.removeEventListener('click', onClick));
        });
    }

    private readonly destroyRef = inject(DestroyRef);

    /**
     * Opens and moves focus inside: to what `focus` names once the frame is rendered, else to the
     * frame itself. `trigger` gets focus back on close, when it is still in the document.
     */
    open(trigger: HTMLElement | null, focus?: () => HTMLElement | null): void {
        this.trigger = trigger;
        if (this.isOpen()) return;
        this.isOpen.set(true);
        if (!this.docked()) this.dialog()?.nativeElement.showModal?.();
        if (focus) {
            // `preventScroll`: the frame is still sliding in from beyond the inline end, and a plain
            // focus scrolls the whole document sideways to reveal it — and leaves it there.
            afterNextRender(() => (focus() ?? this.frame())?.focus({preventScroll: true}), {injector: this.injector});
        }
    }

    close(): void {
        if (!this.isOpen()) return;
        const dialog = this.dialog()?.nativeElement;
        // Finished here and not on the dialog's `close` event, which Chrome queues for a later task:
        // waiting for it left focus on a field inside a closed dialog. The event still finishes a
        // dialog something else closed, and a second finish does nothing.
        if (!this.docked() && dialog?.hasAttribute('open')) dialog.close?.();
        this.finish();
    }

    /** Escape arrives as `cancel` and then `close`; the consumer decides, the same in a browser and in jsdom. */
    protected onCancel(event: Event): void {
        event.preventDefault();
        this.dismissed.emit();
    }

    protected onEscape(event: Event): void {
        event.preventDefault();
        this.dismissed.emit();
    }

    protected finish(): void {
        if (!this.isOpen()) return;
        this.isOpen.set(false);
        if (this.trigger?.isConnected) this.trigger.focus();
        this.trigger = null;
        this.closed.emit();
    }

    private frame(): HTMLElement | null {
        return this.dialog()?.nativeElement ?? null;
    }
}
