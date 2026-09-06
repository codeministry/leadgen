/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.content;

import de.codeministry.leadgen.config.model.PipelineConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deterministic half of content segmentation: splitting, identity, and the rules.
 *
 * <p>Written against the shape of a real advert from the sample corpus rather than against
 * invented Markdown. Its header, its report dialog and its recruiter signature are the three
 * things this stage exists for, and a fixture that does not look like one proves nothing
 * about them.
 */
class ContentSegmentationTest {

    /**
     * An advert as the enrichment stage stores it: the portal's furniture, then the offer,
     * then the recruiter's standing footer. Trimmed, and structurally identical to the real
     * thing.
     */
    private static final String ADVERT =
        """
            # Angular Entwickler (m/w/d), remote

            [Contractor Consulting GmbH](https://example.invalid/company/471-contractor)
            Contact person: Magnus Strobel
            80% remoteFreelanceasapDuration 4 months
            Apply now
            Save to watchlist
            * Print
            * Report

            ## Report project

            After submitting, you will receive a confirmation email. We will review your
            information and take action if necessary.
            Reason for reporting this project: \\*
            The project is outdated

            ## Description

            Für unser Kundenprojekt suchen wir eine/n Angular Entwickler (m/w/d).

            Anforderungen:
            - sehr gute Kenntnisse in Angular 22+ und REST
            - Routine in CI/CD, Jenkins, Openshift

            Beste Grüße

            Magnus Strobel
            Contractor Consulting GmbH
            Datenschutzerklärung: https://example.invalid/datenschutz
            Geschäftsführer: Alexander Ulrich | Amtsgericht München, HRB 187777
            """;

    @Test
    void splitsAnAdvertAtBlankLinesAndAtStructureMarkdownAllowsWithoutOne() {
        List<String> blocks = MarkdownBlocks.split(ADVERT);

        // A heading always starts a block, so the two `##` sections are their own.
        assertThat(blocks).anyMatch(block -> block.equals("## Report project"));
        assertThat(blocks).anyMatch(block -> block.equals("## Description"));

        // And a list's first item does too. Without that rule the whole run from the company
        // link down to "* Report" is one paragraph, because flexmark writes those as hard
        // breaks inside one — measured on the corpus, not assumed.
        assertThat(blocks).anyMatch(block -> block.equals("* Print\n* Report"));
        assertThat(blocks).noneMatch(block -> block.contains("Apply now") && block.contains("* Print"));
    }

    @Test
    void keepsAFencedBlockWholeAcrossItsBlankLines() {
        List<String> blocks = MarkdownBlocks.split("""
            Stack:

            ```
            java

            spring
            ```

            Ende.
            """);

        assertThat(blocks).hasSize(3);
        assertThat(blocks.get(1)).isEqualTo("```\njava\n\nspring\n```");
    }

    @Test
    void yieldsNothingForNothing() {
        assertThat(MarkdownBlocks.split(null)).isEmpty();
        assertThat(MarkdownBlocks.split("   \n\n  ")).isEmpty();
    }

    @Test
    void givesTheSameBlockTheSameIdentityWhateverTheLinkTargetSays() {
        // This is what makes the cache work at all. Every portal appends a session or a
        // tracking parameter, so a digest that moved with the target would never hit twice —
        // the same dialog would be bought from a model once per advert.
        String first = "Read our [privacy policy](https://example.invalid/p?session=aaa).";
        String second = "Read our [privacy policy](https://example.invalid/p?session=bbb).";

        assertThat(BlockDigest.of(first)).isEqualTo(BlockDigest.of(second));
    }

    @Test
    void movesTheIdentityWhenTheWordsOnThePageChange() {
        assertThat(BlockDigest.of("Read our [privacy policy](https://example.invalid/p)."))
            .isNotEqualTo(BlockDigest.of("Read our [terms of use](https://example.invalid/p)."));
    }

    @Test
    void ignoresTheDifferenceBetweenTwoRenderingsOfOneSentence() {
        assertThat(BlockDigest.of("## Report project")).isEqualTo(BlockDigest.of("**Report   project**"));
    }

    @Test
    void isThirtyTwoHexCharacters() {
        assertThat(BlockDigest.of("anything")).hasSize(32).matches("[0-9a-f]{32}");
    }

    @Test
    void labelsThePortalsOwnFurnitureWithNoModelAtAll() {
        ContentRules rules = shippedRules();
        List<String> blocks = MarkdownBlocks.split(ADVERT);

        // The whole header block, which is where both buttons live: on this corpus the
        // company link, the contact, the meta row and the two buttons arrive as one block.
        // The company name and the contact person are not lost with it — they are columns of
        // their own, and the fields panel beside the advert is where a reader reads them.
        assertThat(kindOf(rules, blocks, "Apply now")).contains(ContentKind.CHROME);
        assertThat(kindOf(rules, blocks, "Reason for reporting this project")).contains(ContentKind.FORM);
        assertThat(kindOf(rules, blocks, "Amtsgericht München")).contains(ContentKind.AGENCY);
    }

    @Test
    void leavesTheAdvertItselfAlone() {
        ContentRules rules = shippedRules();
        List<String> blocks = MarkdownBlocks.split(ADVERT);

        assertThat(kindOf(rules, blocks, "Für unser Kundenprojekt")).isEmpty();
        assertThat(kindOf(rules, blocks, "Angular 22+ und REST")).isEmpty();
        // The greeting and the name are the advert too: a person offered this project, and a
        // rule that swallows the signature swallows the only human in it.
        assertThat(kindOf(rules, blocks, "Beste Grüße")).isEmpty();
    }

    @Test
    void doesNotDeleteAnAdvertThatHappensToBeAboutDataProtection() {
        // The pattern that hides a privacy link is anchored on the link. Bare 'Datenschutz'
        // would take out exactly the offers this tool is looking for.
        ContentRules rules = shippedRules();

        assertThat(rules.kindOf(BlockDigest.normalise(
            "Wir suchen Unterstützung im Datenschutz und bei der Datenschutzerklärung des Portals.")))
            .isEmpty();
    }

    @Test
    void dropsARuleItCannotUseRatherThanFailing() {
        // A rule is an optimisation. A typo in one must cost the optimisation and nothing
        // else — not the stage, and not the run.
        ContentRules rules = new ContentRules(List.of(
            new PipelineConfig.Content.Rule("CHROME", "(unclosed"),
            new PipelineConfig.Content.Rule("NOT_A_KIND", "apply now"),
            new PipelineConfig.Content.Rule("FORM", "apply now")));

        assertThat(rules.kindOf("apply now")).contains(ContentKind.FORM);
    }

    @Test
    void readsBackWhatTheStageWroteAndFallsBackWhenItWroteNothing() {
        String json =
            """
                [{"index":0,"text":"Apply now","kind":"CHROME","reason":"A button.","by":"RULE"},
                 {"index":1,"text":"Wir suchen Angular.","kind":"CONTENT","reason":null,"by":"MODEL"}]
                """;

        assertThat(ContentText.of(json, "the whole page")).isEqualTo("Wir suchen Angular.");
        // Not yet read this way, so nothing changes for it. This is what lets the stage be
        // switched on against a full table with no migration and no day where half the
        // shortlist is scored against a different text than the other half.
        assertThat(ContentText.of(null, "the whole page")).isEqualTo("the whole page");
        assertThat(ContentText.of("not json", "the whole page")).isEqualTo("the whole page");
    }

    @Test
    void keepsTheWholeTextWhenEverythingWasCalledFurniture() {
        String json = """
            [{"index":0,"text":"Apply now","kind":"CHROME","reason":null,"by":"RULE"}]
            """;

        // A reading that leaves nothing is a reading nobody should be scored on.
        assertThat(ContentText.of(json, "the whole page")).isEqualTo("the whole page");
    }

    /**
     * The rules as the shipped `pipeline.yaml` states them.
     */
    private static ContentRules shippedRules() {
        return new ContentRules(List.of(
            new PipelineConfig.Content.Rule("CHROME", "apply now\\s+save to watchlist|jetzt bewerben\\s+zur merkliste"),
            new PipelineConfig.Content.Rule("FORM", "reason for reporting this project"),
            new PipelineConfig.Content.Rule("AGENCY", "amtsgericht\\s+\\S+,\\s*hrb\\s*\\d"),
            new PipelineConfig.Content.Rule("LEGAL", "datenschutzerkl(?:ä|a)rung:\\s*https?://")));
    }

    private static java.util.Optional<ContentKind> kindOf(ContentRules rules, List<String> blocks, String containing) {
        String block = blocks.stream()
            .filter(candidate -> candidate.contains(containing))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no block contains " + containing));
        return rules.kindOf(BlockDigest.normalise(block));
    }
}
