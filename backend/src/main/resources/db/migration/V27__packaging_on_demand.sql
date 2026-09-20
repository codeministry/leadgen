-- Packages are built when a person asks for one, not when a run ends.
--
-- Until now the packaging stage built a folder for every offer above the
-- shortlist threshold and opened its application directly at PACKAGED. Measured
-- on the deployed instance on 2026-09-17: 93 applications at PACKAGED against 2
-- ever sent, and 76 folders on disk that nothing ever deleted. Reaching the
-- shortlist now opens an application at NEW and costs one row; moving it to
-- PACKAGED is what asks for the folder.
--
-- So the existing corpus has to be brought to the same meaning, and the only
-- honest reading of a PACKAGED application that was never sent is "the tool
-- decided this, nobody did".
--
-- The dividing line is mostly the event log rather than the current status. A
-- LOST application may have been answered and lost, or written off before
-- anybody wrote a line, and those two have opposite answers here. The current
-- status is asked as well, because this statement is not reversible and a row
-- standing at SENT with a hole in its log must not be reset over it.
--
-- So: anything that stands at, or was ever moved to, SENT, REPLIED, INTERVIEW
-- or OFFER keeps its status, its folder and its `package_dir`; everything else
-- goes back to NEW.

-- The event first, while the old status is still readable. Note nobody wrote
-- this by hand, so it carries its own reason rather than an operator's note.
INSERT INTO application_event (application_id, from_status, to_status, note)
SELECT a.id, a.status, 'NEW', 'reset by V27: packages are built on demand'
FROM application a
WHERE a.status NOT IN ('NEW', 'SENT', 'REPLIED', 'INTERVIEW', 'OFFER')
  AND NOT EXISTS (SELECT 1
                  FROM application_event e
                  WHERE e.application_id = a.id
                    AND e.to_status IN ('SENT', 'REPLIED', 'INTERVIEW', 'OFFER'));

-- Then the row. The dates described an attempt that, by the clause above, never
-- left the machine.
UPDATE application a
SET status       = 'NEW',
    sent_on      = NULL,
    follow_up_on = NULL,
    outcome      = NULL,
    updated_at   = now()
WHERE a.status NOT IN ('NEW', 'SENT', 'REPLIED', 'INTERVIEW', 'OFFER')
  AND NOT EXISTS (SELECT 1
                  FROM application_event e
                  WHERE e.application_id = a.id
                    AND e.to_status IN ('SENT', 'REPLIED', 'INTERVIEW', 'OFFER'));

-- And the folder those rows point at. Nulling `packaged_at` is what re-arms the
-- build: moving one of them back to PACKAGED renders the same folder again from
-- the same advert, which is why dropping it costs nothing.
--
-- This cannot delete the directories themselves -- a migration has no disk. They
-- are collected by `OrphanSweep` at the next start, which removes every folder
-- under the packaging output directory that no `package_dir` names.
UPDATE offer o
SET package_dir = NULL,
    packaged_at = NULL,
    language    = NULL
WHERE o.package_dir IS NOT NULL
  AND NOT EXISTS (SELECT 1
                  FROM application a
                  WHERE a.offer_id = o.id
                    AND a.status IN ('SENT', 'REPLIED', 'INTERVIEW', 'OFFER'))
  AND NOT EXISTS (SELECT 1
                  FROM application a
                           JOIN application_event e ON e.application_id = a.id
                  WHERE a.offer_id = o.id
                    AND e.to_status IN ('SENT', 'REPLIED', 'INTERVIEW', 'OFFER'));
