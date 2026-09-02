import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { Marked } from 'marked';
import { escapeHtml, hljs } from './highlight';

/**
 * Its own parser instance rather than the module-level `marked`, whose options are global:
 * a second component setting a different option would change this one's output.
 *
 * <p>`breaks: true` because this renders adverts, not documents. An ad written with one
 * line per requirement means those lines, and Markdown's "a single newline is a space"
 * rule would run the whole list together — which is exactly the wall this exists to undo.
 */
const parser = new Marked({
  breaks: true,
  gfm: true,
});

/**
 * Text as Markdown, with the fenced code in it highlighted.
 *
 * <p><b>The text is not trusted.</b> It is scraped from a portal or pasted out of a mail,
 * so it may contain anything at all — and Markdown allows raw HTML by design. What makes
 * this safe is Angular's own sanitizer on `[innerHTML]`, which drops scripts, event
 * handlers and `javascript:` URLs before the browser sees them. Nothing here may ever
 * reach for `bypassSecurityTrustHtml`; that is precisely the call that would turn an
 * advert into an injection.
 */
@Component({
  selector: 'lg-markdown',
  templateUrl: './markdown.html',
  styleUrl: './markdown.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Markdown {
  /** Null is a real state: an offer can reach the detail with neither text nor
      description, and an empty box says that better than a crash. */
  readonly text = input<string | null>('');

  protected readonly html = computed(() => {
    const source = (this.text() ?? '').trim();
    return source === '' ? '' : (parser.parse(source, { async: false }) as string);
  });
}

/**
 * Highlighting runs as a Marked extension rather than after the fact, so the parser hands
 * over the code and its language and nothing has to be found again in the output.
 */
parser.use({
  renderer: {
    /**
     * Every link in an advert leaves this application, so every one of them opens in a new
     * tab: the shortlist is a working list, and following a link out of it should not cost
     * the place in it. `rel` goes with the target and is not optional — `noopener` because
     * a page opened with `target="_blank"` can otherwise reach back through
     * `window.opener`, and this markup came off a portal.
     */
    link({ href, title, text }) {
      const label = title ? ` title="${escapeHtml(title)}"` : '';
      return `<a href="${escapeHtml(href)}"${label} target="_blank" rel="noopener noreferrer">${text}</a>`;
    },

    code({ text, lang }) {
      const language = lang && hljs.getLanguage(lang) ? lang : null;
      const body = language ? hljs.highlight(text, { language }).value : escapeHtml(text);
      return `<pre class="code"><code class="hljs">${body}</code></pre>`;
    },
  },
});
