import {httpResource} from '@angular/common/http';
import {ChangeDetectionStrategy, Component, effect, ElementRef, inject, input} from '@angular/core';
import {HelpDiagram} from '@core/help/help-chapters';

/**
 * Turns the text of a committed diagram into an `<svg>` element that is safe to put on the page,
 * or `null` when the text is not an SVG. Exported for the spec.
 *
 * <p>The file is our own static asset, rendered by `tools/build-help-diagrams.ts`; the cleaning
 * is defence in depth, so that a file which ever did carry a script or a handler would still be
 * inert: every `<script>` and `<foreignObject>` goes, and so does every `on…` attribute and any
 * link that is not a same-document `#` reference.
 */
export function inlineSvg(text: string, label: string): SVGSVGElement | null {
    const parsed = new DOMParser().parseFromString(text, 'image/svg+xml').documentElement;
    if (!(parsed instanceof SVGSVGElement)) return null;

    for (const node of parsed.querySelectorAll('script, foreignObject')) node.remove();
    for (const element of [parsed, ...parsed.querySelectorAll('*')]) {
        for (const {name, value} of [...element.attributes]) {
            const link = name === 'href' || name === 'xlink:href';
            if (name.toLowerCase().startsWith('on') || (link && !value.startsWith('#'))) {
                element.removeAttribute(name);
            }
        }
    }
    // One accessible image named by the caption; Mermaid's own role and description go, or a
    // screen reader would announce "flowchart-v2" and walk every box.
    parsed.removeAttribute('aria-roledescription');
    parsed.setAttribute('role', 'img');
    parsed.setAttribute('aria-label', label);
    return document.importNode(parsed, true);
}

/**
 * One help diagram, inlined into the page (spec 010, ISC-314).
 *
 * <p>Inlined rather than an `<img>`: an image is a separate document, and neither the app's CSS
 * variables nor its font reach inside it. Inlined, the colours in the SVG are `var(--…)` tokens
 * that follow the theme, and the labels take `--font-sans` — one file for both themes.
 *
 * <p>Built as a node through `DOMParser` and appended, not bound through `[innerHTML]` with
 * `bypassSecurityTrustHtml`: the trust bypass would switch Angular's sanitiser off for the whole
 * string, where this keeps the cleaning in one reviewed function and never parses markup as
 * HTML. The file is fetched only while the drawer shows a chapter that places it.
 */
@Component({
    selector: 'lg-help-diagram',
    template: '',
    styleUrl: './help-diagram.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HelpDiagramView {
    readonly id = input.required<HelpDiagram>();
    readonly label = input.required<string>();

    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

    protected readonly svg = httpResource.text(() => `/help/diagrams/${this.id()}.svg`);

    constructor() {
        effect(() => {
            const text = this.svg.hasValue() ? this.svg.value() : undefined;
            const svg = text === undefined ? null : inlineSvg(text, this.label());
            this.host.nativeElement.replaceChildren(...(svg === null ? [] : [svg]));
        });
    }
}
