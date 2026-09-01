package de.codeministry.leadgen.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import de.codeministry.leadgen.ingest.extract.PlainText;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

/**
 * The advert's own paragraphs, kept.
 *
 * <p>What is guarded here is the difference between an ad a person can skim and one line of
 * six hundred words: jsoup's own `text()` joins every node with a space, and the detail page
 * has nothing left to lay out.
 */
class PlainTextTest {

    @Test
    void keepsBlockBoundariesAndLineBreaks() {
        String text = PlainText.of(Jsoup.parseBodyFragment("""
                <div>
                  <p>Wir suchen einen Java-Entwickler.</p>
                  <p>Start: sofort<br>Dauer: 6 Monate</p>
                  <ul><li>Spring Boot</li><li>Kubernetes</li></ul>
                </div>
                """).body());

        assertThat(text).isEqualTo("""
                Wir suchen einen Java-Entwickler.

                Start: sofort
                Dauer: 6 Monate

                Spring Boot
                Kubernetes""");
    }

    @Test
    void leavesInlineMarkupAsOneLine() {
        // A title or a location sits in one inline element, and reading it through this
        // has to give exactly what `text()` gave — that is what makes it safe everywhere.
        String text = PlainText.of(
                Jsoup.parseBodyFragment("<span>Senior <mark>DevOps</mark> Engineer</span>").body());

        assertThat(text).isEqualTo("Senior DevOps Engineer");
    }

    @Test
    void collapsesTheMarkupsOwnNesting() {
        // Three divs closing after one paragraph are three newlines and one paragraph
        // break. More than one blank line is only the markup showing through.
        String text = PlainText.of(Jsoup.parseBodyFragment(
                "<div><div><div><p>Erste</p></div></div></div><p>Zweite</p>").body());

        assertThat(text).isEqualTo("Erste\n\nZweite");
    }
}
