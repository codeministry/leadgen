package de.codeministry.leadgen.web;

import de.codeministry.leadgen.analytics.AnalyticsQueryService;
import de.codeministry.leadgen.analytics.AnalyticsView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The analytics screen's one read.
 *
 * <p>No query parameters, like every other read endpoint here. Both time axes come down as
 * daily buckets and the browser switches between them and aggregates them into weeks — a
 * parameter would be a second implementation of an aggregation that already exists in SQL,
 * and the two would disagree the first time either changed.
 */
@RestController
@RequestMapping("/api/analytics")
class AnalyticsController {

    private final AnalyticsQueryService analytics;

    AnalyticsController(AnalyticsQueryService analytics) {
        this.analytics = analytics;
    }

    @GetMapping
    AnalyticsView analytics() {
        return analytics.analytics();
    }
}
