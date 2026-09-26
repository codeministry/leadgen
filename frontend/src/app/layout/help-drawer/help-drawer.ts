import {httpResource} from '@angular/common/http';
import {
    afterNextRender,
    ChangeDetectionStrategy,
    Component,
    computed,
    DestroyRef,
    ElementRef,
    inject,
    Injector,
    signal,
    viewChild,
} from '@angular/core';
import {toSignal} from '@angular/core/rxjs-interop';
import {ActivatedRouteSnapshot, Router} from '@angular/router';
import {TranslocoPipe, TranslocoService} from '@jsverse/transloco';
import {
    CHAPTER_DIAGRAMS,
    CHAPTER_SHOTS,
    chapterForRoute,
    HELP_CHAPTERS,
    HELP_SHOTS,
    HelpChapter,
    HelpDiagram,
    HelpShot,
    HelpShotSize,
    isHelpDiagram,
    isHelpShot,
} from '@core/help/help-chapters';
import {ThemeStore} from '@core/theme/theme.store';
import {Icon} from '@shared/icon/icon';
import {LgIconName} from '@shared/icon/lucide-icons';
import {Markdown} from '@shared/markdown/markdown';
import {HelpDiagramView} from './help-diagram';

/** A chapter cut at its figure placeholders: runs of text, and the figures between them. */
export type HelpPart =
    | {kind: 'text'; text: string}
    | {kind: 'diagram'; id: HelpDiagram}
    | {kind: 'shot'; id: HelpShot};

/**
 * The icon each chapter wears in the contents. A screen's icon is the one its entry carries in the
 * main navigation (`layout/app-nav/app-nav.ts`), so a reader recognises the screen. The overview
 * gets a book because it is the one chapter that is not a screen.
 */
export const CHAPTER_ICONS: Record<HelpChapter, LgIconName> = {
    'how-it-works': 'book-open',
    dashboard: 'layout-dashboard',
    shortlist: 'list-checks',
    pipeline: 'columns-3',
    analytics: 'chart-line',
    workflow: 'sliders-horizontal',
    sources: 'database',
    'offer-detail': 'file-text',
    application: 'send',
    'filters-views': 'list-filter',
    'app-basics': 'keyboard',
};

const PLACEHOLDER = /<!--\s*(diagram|screenshot):\s*([\w-]+)\s*-->/g;

/**
 * Cuts a chapter at its `<!-- diagram: <id> -->` and `<!-- screenshot: <id> -->` placeholders.
 * A figure the chapter should carry but does not place is appended after the text, diagrams
 * first and each kind in its listed order, so a text that forgets a placeholder still shows
 * every figure; an unknown id is dropped, so a typo is a missing figure rather than a broken
 * image.
 */
export function splitChapter(
    text: string,
    diagrams: readonly HelpDiagram[],
    shots: readonly HelpShot[] = [],
): HelpPart[] {
    const parts: HelpPart[] = [];
    const placed = new Set<string>();
    let from = 0;
    for (const match of text.matchAll(PLACEHOLDER)) {
        const before = text.slice(from, match.index).trim();
        if (before !== '') parts.push({kind: 'text', text: before});
        const [, kind, id] = match;
        if (kind === 'diagram' && isHelpDiagram(id) && diagrams.includes(id) && !placed.has(`d:${id}`)) {
            parts.push({kind: 'diagram', id});
            placed.add(`d:${id}`);
        } else if (kind === 'screenshot' && isHelpShot(id) && shots.includes(id) && !placed.has(`s:${id}`)) {
            parts.push({kind: 'shot', id});
            placed.add(`s:${id}`);
        }
        from = match.index + match[0].length;
    }
    const rest = text.slice(from).trim();
    if (rest !== '') parts.push({kind: 'text', text: rest});
    for (const id of diagrams) {
        if (!placed.has(`d:${id}`)) parts.push({kind: 'diagram', id});
    }
    for (const id of shots) {
        if (!placed.has(`s:${id}`)) parts.push({kind: 'shot', id});
    }
    return parts;
}

/**
 * The help, as a drawer from the right (spec 010, ISC-311..313).
 *
 * <p>A native `<dialog>` opened with `showModal()`, for what only the top layer gives: the
 * backdrop, the inert page behind it and a focus trap, with no script of ours for any of them.
 * It is styled as a drawer and not with daisyUI's `drawer`, which is a page layout built around
 * a checkbox and would have to wrap the whole shell.
 *
 * <p>The chapter it opens at is read from the route when it opens, not tracked: the drawer is
 * modal, so the route cannot change under it, and the section is inherited down the chain the
 * same way the shell reads it. An offer open under the shortlist or the pipeline opens the
 * offer-detail chapter instead.
 *
 * <p>The texts are static files under `public/help/`, fetched when a chapter is shown: they
 * are read rarely, and in the bundle they would be paid for on every load.
 */
@Component({
    selector: 'lg-help-drawer',
    imports: [HelpDiagramView, Icon, Markdown, TranslocoPipe],
    templateUrl: './help-drawer.html',
    styleUrl: './help-drawer.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HelpDrawer {
    private readonly router = inject(Router);
    private readonly transloco = inject(TranslocoService);
    private readonly destroyRef = inject(DestroyRef);

    private readonly injector = inject(Injector);

    protected readonly chapters = HELP_CHAPTERS;
    protected readonly icons = CHAPTER_ICONS;
    protected readonly isOpen = signal(false);
    protected readonly chapter = signal<HelpChapter>('how-it-works');
    /** The chapter of the screen the drawer was opened on; the contents mark it as current. */
    protected readonly here = signal<HelpChapter>('how-it-works');
    /** A chapter, or the contents list that replaced the old row of chapter buttons. */
    protected readonly view = signal<'chapter' | 'contents'>('chapter');

    private readonly lang = toSignal(this.transloco.langChanges$, {initialValue: this.transloco.getActiveLang()});

    private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');
    private readonly closeButton = viewChild.required<ElementRef<HTMLButtonElement>>('closeButton');
    private trigger: HTMLElement | null = null;

    /** Nothing is fetched while the drawer is shut, or while it shows the contents. */
    protected readonly text = httpResource.text(() =>
        this.isOpen() && this.view() === 'chapter' ? `/help/${this.lang()}/${this.chapter()}.md` : undefined,
    );

    protected readonly parts = computed<HelpPart[]>(() =>
        this.text.hasValue()
            ? splitChapter(this.text.value(), CHAPTER_DIAGRAMS[this.chapter()], CHAPTER_SHOTS[this.chapter()])
            : [],
    );

    private readonly theme = inject(ThemeStore);

    /**
     * The file that shows the app as the reader sees it now: their language, and the theme the
     * page resolved to, so "system" follows the operating system like the page does.
     */
    protected shotSrc(id: HelpShot): string {
        const theme = this.theme.theme() === 'lg-dark' ? 'dark' : 'light';
        return `/help/shots/${this.lang()}/${id}-${theme}.webp`;
    }

    protected shotSize(id: HelpShot): HelpShotSize {
        return (HELP_SHOTS as Record<HelpShot, HelpShotSize>)[id];
    }

    /** Opens at the current screen's chapter; `trigger` gets focus back when it closes. */
    open(trigger: HTMLElement): void {
        this.trigger = trigger;
        const leaf = this.leaf();
        const chapter = chapterForRoute(leaf.data['section'], leaf.paramMap.has('id'));
        this.chapter.set(chapter);
        this.here.set(chapter);
        this.view.set('chapter');
        this.isOpen.set(true);
        this.dialog().nativeElement.showModal?.();
        // The browser would focus the first focusable itself; said here so it is also true in
        // an environment that only knows the attribute.
        this.closeButton().nativeElement.focus();
    }

    protected close(): void {
        this.dialog().nativeElement.close?.();
    }

    /** Escape arrives as `cancel` and then `close`; one path, the same in a browser and in jsdom. */
    protected onCancel(event: Event): void {
        event.preventDefault();
        this.close();
    }

    /**
     * The backdrop closes the drawer. The panel fills the dialog, so the only click that lands on
     * the dialog element itself is the backdrop's.
     *
     * <p>Listened for here rather than bound in the template: the template linter reads a
     * `(click)` on a `<dialog>` as a control without a key handler, and the keyboard's way to the
     * same place is Escape, which the dialog already turns into `cancel`.
     */
    constructor() {
        afterNextRender(() => {
            const dialog = this.dialog().nativeElement;
            const onClick = (event: MouseEvent) => {
                if (event.target === dialog) this.close();
            };
            dialog.addEventListener('click', onClick);
            this.destroyRef.onDestroy(() => dialog.removeEventListener('click', onClick));
        });
    }

    protected onClose(): void {
        this.isOpen.set(false);
        this.trigger?.focus();
        this.trigger = null;
    }

    /** A row of the contents: that chapter, with focus on its heading so a reader starts there. */
    protected show(chapter: HelpChapter): void {
        this.chapter.set(chapter);
        this.view.set('chapter');
        this.focusAfterRender('.lg-help-chapter-title');
    }

    /** "All chapters": the list, with focus on the row of the chapter the reader came from. */
    protected showContents(): void {
        this.view.set('contents');
        this.focusAfterRender(`.lg-help-toc [data-chapter="${this.chapter()}"]`);
    }

    private focusAfterRender(selector: string): void {
        afterNextRender(() => this.dialog().nativeElement.querySelector<HTMLElement>(selector)?.focus(), {
            injector: this.injector,
        });
    }

    private leaf(): ActivatedRouteSnapshot {
        let leaf = this.router.routerState.snapshot.root;
        while (leaf.firstChild !== null) leaf = leaf.firstChild;
        return leaf;
    }
}
