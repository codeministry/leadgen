package de.codeministry.leadgen.config;

import de.codeministry.leadgen.config.model.PipelineConfig;
import de.codeministry.leadgen.config.model.MatchingRules;
import de.codeministry.leadgen.config.model.SourcesConfig;
import java.time.Instant;

/**
 * The three files as one immutable unit. They are read together and swapped
 * together: a reload that applied to one of them and not the others would leave the
 * pipeline running against a picture that never existed on disk.
 */
public record ConfigSnapshot(
        PipelineConfig application, MatchingRules rules, SourcesConfig sources, Instant loadedAt) {}
