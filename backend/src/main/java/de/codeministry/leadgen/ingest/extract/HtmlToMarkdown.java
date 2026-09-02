package de.codeministry.leadgen.ingest.extract;

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.jsoup.nodes.Element;

/**
 * An element's HTML as Markdown.
 *
 * <p>jsoup's {@code text()} joins every node with a single space, so an advert written as
 * a heading, three paragraphs and a bullet list arrives as one line of six hundred words.
 * Keeping only the block boundaries — which is what this class did first — recovers the
 * paragraphs and loses everything else: the headings an ad structures itself with, the
 * list its requirements are in, the emphasis on the stack. The screen then shows a column
 * of equal-looking paragraphs, which is nearly the wall it replaced.
 *
 * <p>Markdown keeps all of it and stays plain text, so nothing downstream has to change:
 * the hard filter still matches words, the fingerprint still normalises a title, and a
 * reader with no renderer still sees the ad. The detail screen renders it.
 *
 * <p><b>It is deliberately not used where a regex runs.</b> A pattern in `sources.yaml` or
 * `enrichment.extract` is written against a single line, and `.` does not match a newline;
 * a pattern that matched across a joined boundary yesterday would silently stop matching,
 * and `**` around a word would break it outright. So a field with a pattern still reads the
 * collapsed text, and a field without one reads this. A pattern reads a line; a field reads
 * a document.
 */
public final class HtmlToMarkdown {

    /**
     * ATX headings (`## Aufgaben`) rather than Setext, because Setext only exists for two
     * levels and an ad that uses `<h3>` would silently lose its rank. Unknown tags are
     * dropped rather than passed through: raw HTML is legal in Markdown, and an ad is not
     * a document this application should be embedding markup from.
     */
    private static final FlexmarkHtmlConverter CONVERTER = FlexmarkHtmlConverter.builder(
                    new MutableDataSet()
                            .set(FlexmarkHtmlConverter.SETEXT_HEADINGS, false)
                            .set(FlexmarkHtmlConverter.OUTPUT_UNKNOWN_TAGS, false)
                            .set(FlexmarkHtmlConverter.BR_AS_EXTRA_BLANK_LINES, false))
            .build();

    private HtmlToMarkdown() {}

    /** The element's content as Markdown, or an empty string when there is nothing in it. */
    public static String of(Element element) {
        return CONVERTER.convert(absolute(element).outerHtml()).strip();
    }

    /**
     * Links resolved against the page they came from, on a copy. A portal writes
     * `/projects/argo-cd`, and a relative link surviving into the Markdown is a link into
     * *this* application's router — which answers it with the shortlist. Resolving needs
     * the document's base URI; without one `absUrl` returns empty, and the link then keeps
     * the text and loses only the target, which is the right way round.
     */
    private static Element absolute(Element element) {
        Element copy = element.clone();
        for (Element link : copy.select("a[href]")) {
            String absolute = link.absUrl("href");
            if (absolute.isEmpty()) {
                link.removeAttr("href");
            } else {
                link.attr("href", absolute);
            }
        }
        copy.select("img").remove();
        return copy;
    }
}
