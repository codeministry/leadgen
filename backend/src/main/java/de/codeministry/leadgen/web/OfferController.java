package de.codeministry.leadgen.web;

import de.codeministry.leadgen.offer.FunnelView;
import de.codeministry.leadgen.offer.OfferQueryService;
import de.codeministry.leadgen.offer.ShortlistEntry;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The shortlist and one offer of it.
 *
 * <p>No filtering parameters. The browser already holds the whole list and filters it in
 * the query string, which is what makes a filtered shortlist survive a reload and be
 * shareable as a link — a server-side filter would be a second implementation of the same
 * rules, disagreeing the first time one of them changes.
 */
@RestController
@RequestMapping("/api/offers")
class OfferController {

    private final OfferQueryService offers;

    OfferController(OfferQueryService offers) {
        this.offers = offers;
    }

    @GetMapping
    List<ShortlistEntry> shortlist() {
        return offers.shortlist();
    }

    /**
     * Mapped before `/{id}` in specificity, not in order: Spring prefers the literal path,
     * so "funnel" never reaches the long parameter.
     */
    @GetMapping("/funnel")
    FunnelView funnel() {
        return offers.funnel();
    }

    @GetMapping("/{id}")
    ShortlistEntry one(@PathVariable long id) {
        return offers.find(id).orElseThrow(() -> new NotFound(id));
    }

    @ExceptionHandler(NotFound.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    String notFound(NotFound e) {
        return e.getMessage();
    }

    static class NotFound extends RuntimeException {
        NotFound(long id) {
            super("no offer with id " + id);
        }
    }
}
