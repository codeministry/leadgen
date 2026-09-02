import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { hljs } from './highlight';

/**
 * A Markdown file shown as what it is: the source, with its markup highlighted.
 *
 * <p>The counterpart to `Markdown`, and deliberately not the same component. This screen
 * exists so a wrong reading is visible against the text it was read from, and rendering
 * the file would hide exactly the thing being checked — a `#` that was meant as a heading
 * and a `-` that was meant as a list are the difference between a field read and a field
 * silently dropped.
 */
@Component({
  selector: 'lg-markdown-source',
  templateUrl: './markdown-source.html',
  styleUrl: './markdown-source.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MarkdownSource {
  /** Null is a real state: an offer can reach the detail with neither text nor
      description, and an empty box says that better than a crash. */
  readonly text = input<string | null>('');

  /**
   * Escaped first, then highlighted — the other way round, highlight.js's own `<span>`s
   * would be escaped along with the content and the file would render as its own markup.
   */
  protected readonly html = computed(() => {
    const source = this.text() ?? '';
    return source === ''
      ? ''
      : hljs.highlight(source, { language: 'markdown', ignoreIllegals: true }).value;
  });

}
