package de.codeministry.leadgen.application;

import java.time.Instant;

/** One recorded change. The history a single mutable row cannot answer for. */
public record ApplicationEvent(
        ApplicationStatus fromStatus, ApplicationStatus toStatus, String note, Instant recordedAt) {}
