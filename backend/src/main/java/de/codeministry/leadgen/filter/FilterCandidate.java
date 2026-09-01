package de.codeministry.leadgen.filter;

import java.time.LocalDate;
import java.util.List;

/** The fields the hard filter reads. Everything else about an offer is irrelevant here. */
public record FilterCandidate(
        long id,
        String title,
        String description,
        String location,
        List<String> tags,
        LocalDate publishedOn) {}
