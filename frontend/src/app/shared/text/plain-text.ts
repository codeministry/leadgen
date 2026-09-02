/**
 * A Markdown document reduced to the sentence it says.
 *
 * The description arrives as Markdown, because `HtmlToMarkdown` keeps the headings, the
 * bullet lists and the emphasis an ad is written with — and on a detail page that is
 * exactly right. In a two-line teaser it is not: the syntax survives the truncation and
 * the reader gets `**Ihre Aufgaben** * Betrieb der Services im Cluster`, which is neither
 * the prose nor the formatting.
 *
 * So this strips the marks and keeps the words. It is deliberately not a Markdown parser:
 * a teaser is cut mid-document, so the input is routinely unbalanced — a parser would
 * either fail on it or render half a list, and both are worse than a plain sentence.
 *
 * Block structure collapses to a space rather than disappearing, or the last word of a
 * heading runs into the first word of the paragraph under it.
 */
export function plainText(markdown: string | null | undefined): string {
  if (!markdown) {
    return '';
  }
  return (
    markdown
      // Fenced and inline code: the fence marks go, the code stays readable.
      .replace(/```+[^\n]*\n?/g, ' ')
      .replace(/`([^`]*)`/g, '$1')
      // A link keeps its text and loses its target — the card already links the title.
      .replace(/!?\[([^\]]*)]\([^)]*\)/g, '$1')
      // Leading block marks: heading hashes, quote arrows, list bullets, ordered numbers.
      .replace(/^[ \t]*(#{1,6}|>+|[-*+]|\d+\.)[ \t]+/gm, '')
      // Emphasis, strong and strikethrough, wherever they sit.
      .replace(/(\*{1,3}|_{1,3}|~~)(?=\S)([\s\S]*?\S)\1/g, '$2')
      // A horizontal rule is a line of marks with nothing to keep.
      .replace(/^[ \t]*([-*_])(?:[ \t]*\1){2,}[ \t]*$/gm, ' ')
      // Whatever emphasis marks are left were unbalanced by the truncation.
      .replace(/[*_`]/g, '')
      .replace(/\s+/g, ' ')
      .trim()
  );
}
