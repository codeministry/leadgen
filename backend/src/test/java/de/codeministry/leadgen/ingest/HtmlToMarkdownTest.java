package de.codeministry.leadgen.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import de.codeministry.leadgen.ingest.extract.HtmlToMarkdown;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

/**
 * The advert's own structure, kept.
 *
 * <p>What is guarded here is the difference between an ad a person can skim and a column of
 * equal-looking paragraphs: jsoup's `text()` joins every node with a space, and keeping only
 * the block boundaries recovers the paragraphs while losing the headings, the lists and the
 * emphasis that tell a reader where the requirements start.
 */
class HtmlToMarkdownTest {

    @Test
    void keepsHeadingsListsAndEmphasis() {
        String markdown = HtmlToMarkdown.of(Jsoup.parseBodyFragment("""
                <div>
                  <h2>Ihre Aufgaben</h2>
                  <p>Wir suchen einen <strong>Java-Entwickler</strong>.</p>
                  <ul><li>Spring Boot</li><li>Kubernetes</li></ul>
                </div>
                """).body());

        assertThat(markdown).contains("## Ihre Aufgaben");
        assertThat(markdown).contains("**Java-Entwickler**");
        // The bullet marker is flexmark's own `*`, and both markers are the same list to
        // any renderer — what is asserted is that the items are still items.
        assertThat(markdown).contains("* Spring Boot");
        assertThat(markdown).contains("* Kubernetes");
    }

    @Test
    void keepsAParagraphBreakAndALineBreak() {
        String markdown = HtmlToMarkdown.of(
                Jsoup.parseBodyFragment("<p>Erste</p><p>Start: sofort<br>Dauer: 6 Monate</p>").body());

        assertThat(markdown).contains("Erste\n\n");
        assertThat(markdown).contains("Start: sofort");
        assertThat(markdown).contains("Dauer: 6 Monate");
    }

    @Test
    void leavesInlineMarkupAsOneLine() {
        // A title or a location sits in one inline element, and reading it through this has
        // to give back the line itself — that is what makes it safe on every field.
        String markdown = HtmlToMarkdown.of(
                Jsoup.parseBodyFragment("<span>Senior <mark>DevOps</mark> Engineer</span>").body());

        assertThat(markdown).isEqualTo("Senior DevOps Engineer");
    }

    @Test
    void dropsMarkupItDoesNotUnderstandRatherThanPassingItThrough() {
        // Raw HTML is legal in Markdown, and an ad is not a document to embed markup from.
        String markdown = HtmlToMarkdown.of(
                Jsoup.parseBodyFragment("<p>Hallo <script>alert(1)</script> Welt</p>").body());

        assertThat(markdown).doesNotContain("<script");
    }
}
