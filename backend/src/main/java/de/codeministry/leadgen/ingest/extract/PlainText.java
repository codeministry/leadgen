package de.codeministry.leadgen.ingest.extract;

import java.util.Set;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

/**
 * An element's text with its paragraphs still in it.
 *
 * <p>jsoup's {@code text()} joins every node with a single space, so an advert written as
 * twenty paragraphs and a bullet list arrives as one line of six hundred words. Nothing is
 * lost that a filter reads — the words are all there — but the detail page shows the ad as
 * a wall, and the one thing a reader does with an ad is skim it for the shape of the
 * engagement. This keeps the block boundaries and the {@code <br>}s and nothing else.
 *
 * <p><b>It is deliberately not used where a regex runs.</b> A pattern in `sources.yaml` or
 * `enrichment.extract` is written against a single line, and {@code .} does not match a
 * newline: the same pattern that matched across a joined block boundary yesterday would
 * silently stop matching. So a field with a pattern still reads the collapsed text, and a
 * field without one reads this. A pattern reads a line; a field reads a document.
 */
public final class PlainText {

    /**
     * Blocks that are one line rather than one paragraph. A bullet list read with a blank
     * line between every item is longer than the ad it came from, and the requirements are
     * exactly what a reader skims.
     */
    private static final Set<String> LINE_BLOCKS = Set.of("li", "tr", "dt", "dd", "option");

    private PlainText() {}

    /** The element's text with a newline at every block boundary and every {@code <br>}. */
    public static String of(Element element) {
        StringBuilder out = new StringBuilder();

        NodeTraversor.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof TextNode text) {
                    // TextNode.text() already normalises the whitespace inside one node,
                    // which is what keeps the source's own indentation out of the result.
                    append(out, text.text());
                } else if (node instanceof Element el && "br".equals(el.tagName())) {
                    out.append('\n');
                }
            }

            @Override
            public void tail(Node node, int depth) {
                // A block ends a paragraph, so it is worth a blank line — `tidy` collapses
                // the run that nested blocks produce back down to one. `br` is excluded
                // because jsoup counts it as a block and it already wrote its newline on
                // the way in; without this, every line break became a paragraph break.
                if (node instanceof Element el && el.isBlock() && !"br".equals(el.tagName())) {
                    out.append(LINE_BLOCKS.contains(el.tagName()) ? "\n" : "\n\n");
                }
            }
        }, element);

        return tidy(out.toString());
    }

    /**
     * A space between two words is worth keeping, a space after a newline is not: block
     * boundaries otherwise leave the next line indented by however many inline nodes ended
     * on the previous one.
     */
    private static void append(StringBuilder out, String text) {
        if (text.isBlank() && (out.isEmpty() || out.charAt(out.length() - 1) == '\n')) {
            return;
        }
        out.append(text);
    }

    /**
     * Nested blocks close one after another, so a single paragraph inside three divs ends
     * with three newlines. One blank line is a paragraph break and any more of them is
     * only the markup showing through.
     */
    private static String tidy(String text) {
        return text.replaceAll("[ \t]+(\n)", "$1")
                .replaceAll("\n{3,}", "\n\n")
                .strip();
    }
}
