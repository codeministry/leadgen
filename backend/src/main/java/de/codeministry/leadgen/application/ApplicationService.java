package de.codeministry.leadgen.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The half of the loop the system cannot observe.
 *
 * <p>It does not send, so it cannot know that a mail went out, that someone replied, or
 * that the project went to a cheaper bid. Every value here is entered by hand, and the
 * board is the only place that state exists — which is why keeping it comfortable to
 * update matters more than keeping it strict. A tool that argues about a correction is a
 * tool nobody corrects.
 */
@Slf4j
@Service
public class ApplicationService {

    private static final String BOARD =
            """
            SELECT a.id, a.offer_id, a.status, a.sent_on, a.follow_up_on, a.outcome, a.note,
                   a.updated_at, o.title, o.agency, o.portal, o.url, o.score_value, o.rate_eur,
                   o.package_dir
            FROM application a
            JOIN offer o ON o.id = a.offer_id
            ORDER BY o.score_value DESC NULLS LAST, a.updated_at DESC
            """;

    private final JdbcClient jdbc;

    ApplicationService(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    public List<ApplicationView> board() {
        LocalDate today = LocalDate.now();
        return jdbc.sql(BOARD).query((rs, row) -> {
            LocalDate followUp = rs.getObject("follow_up_on", LocalDate.class);
            var status = ApplicationStatus.valueOf(rs.getString("status"));
            return new ApplicationView(
                    rs.getLong("id"),
                    rs.getLong("offer_id"),
                    status,
                    rs.getString("title"),
                    rs.getString("agency"),
                    rs.getString("portal"),
                    rs.getString("url"),
                    rs.getObject("score_value", Integer.class),
                    rs.getObject("rate_eur", java.math.BigDecimal.class),
                    rs.getString("package_dir"),
                    rs.getObject("sent_on", LocalDate.class),
                    followUp,
                    // Closed applications never chase: a lost project with a stale reminder
                    // is how a follow-up list stops being read.
                    followUp != null && !status.isClosed() && !followUp.isAfter(today),
                    rs.getString("outcome"),
                    rs.getString("note"),
                    instant(rs, "updated_at"));
        }).list();
    }

    public Optional<ApplicationView> find(long id) {
        return board().stream().filter(view -> view.id() == id).findFirst();
    }

    /**
     * Creates the application row for an offer, or returns the one already there.
     *
     * <p>Called when a package is built, because that is the first moment there is
     * anything for a person to act on. Idempotent, so a second packaging run does not
     * reset a status the operator has already moved on.
     */
    @Transactional
    public long open(long offerId, ApplicationStatus initial) {
        Optional<Long> existing = jdbc.sql("SELECT id FROM application WHERE offer_id = ?")
                .param(offerId)
                .query(Long.class)
                .optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        long id = jdbc.sql("INSERT INTO application (offer_id, status) VALUES (?, ?) RETURNING id")
                .params(offerId, initial.name())
                .query(Long.class)
                .single();
        record(id, null, initial, "opened");
        return id;
    }

    /**
     * Records what the operator says happened.
     *
     * <p><b>Any transition is accepted.</b> The states describe the usual path, not a
     * rule: a project can be lost before it was ever answered, and a mistyped status has
     * to be correctable without an argument. What is checked is that the values make sense
     * together — a sent application needs a date, because "sent, at some point" is not a
     * fact anyone can act on, and a follow-up in the past is a reminder that has already
     * failed.
     */
    @Transactional
    public ApplicationView update(long id, ApplicationUpdate update) {
        ApplicationView before = find(id).orElseThrow(() -> new ApplicationNotFound(id));

        LocalDate sentOn = update.sentOn() != null ? update.sentOn() : before.sentOn();
        if (update.status().isOut() && sentOn == null) {
            // Defaulting rather than refusing: the operator is recording a fact that
            // already happened, and today is right far more often than it is wrong.
            sentOn = LocalDate.now();
        }
        LocalDate followUp = update.clearsFollowUp()
                ? null
                : (update.followUpOn() != null ? update.followUpOn() : before.followUpOn());
        if (update.status().isClosed()) {
            followUp = null;
        }

        jdbc.sql(
                        """
                        UPDATE application
                        SET status = ?, sent_on = ?, follow_up_on = ?, outcome = ?, note = ?, updated_at = now()
                        WHERE id = ?
                        """)
                .params(
                        update.status().name(),
                        sentOn,
                        followUp,
                        update.outcome() != null ? update.outcome() : before.outcome(),
                        update.note() != null ? update.note() : before.note(),
                        id)
                .update();

        if (before.status() != update.status()) {
            record(id, before.status(), update.status(), update.note());
        }
        log.info("Application {} moved from {} to {}", id, before.status(), update.status());
        return find(id).orElseThrow(() -> new ApplicationNotFound(id));
    }

    public List<ApplicationEvent> history(long id) {
        return jdbc.sql(
                        """
                        SELECT from_status, to_status, note, recorded_at
                        FROM application_event WHERE application_id = ? ORDER BY recorded_at DESC
                        """)
                .param(id)
                .query((rs, row) -> new ApplicationEvent(
                        rs.getString("from_status") == null
                                ? null
                                : ApplicationStatus.valueOf(rs.getString("from_status")),
                        ApplicationStatus.valueOf(rs.getString("to_status")),
                        rs.getString("note"),
                        instant(rs, "recorded_at")))
                .list();
    }

    /** How many applications are waiting on a follow-up that is already due. */
    public int followUpsDue() {
        return (int) board().stream().filter(ApplicationView::followUpDue).count();
    }

    /**
     * The Postgres driver does not convert `timestamptz` straight to an `Instant`, and
     * the failure is a runtime `DataIntegrityViolationException` naming the whole query
     * rather than the column. One helper, so the next timestamp does not rediscover it.
     */
    private static java.time.Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private void record(long applicationId, ApplicationStatus from, ApplicationStatus to, String note) {
        jdbc.sql(
                        """
                        INSERT INTO application_event (application_id, from_status, to_status, note)
                        VALUES (?, ?, ?, ?)
                        """)
                .params(applicationId, from == null ? null : from.name(), to.name(), note)
                .update();
    }

    /** Thrown when an id names nothing. The controller turns it into a 404. */
    public static class ApplicationNotFound extends RuntimeException {
        public ApplicationNotFound(long id) {
            super("no application with id " + id);
        }
    }
}
