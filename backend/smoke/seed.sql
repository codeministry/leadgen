-- One offer and one application, so the packaging step does not depend on whether the
-- corpus happened to produce something above the threshold today. Seeded rather than
-- ingested: what step 4 of the suite is about is Freemarker and the async listener, and
-- making that wait on the scoring stage would test two things and report one.
--
-- Idempotent: the smoke stack is torn down with its volume, but a second run against a
-- surviving one must not fail on the unique index.
INSERT INTO source (name, kind)
SELECT 'smoke-seed', 'file'
WHERE NOT EXISTS (SELECT 1 FROM source WHERE name = 'smoke-seed');

INSERT INTO offer (source_id, external_id, title, description, full_text, url, fingerprint,
                   status, score_value, score_band, location, portal, agency,
                   published_on, starts_on, language)
SELECT s.id,
       'smoke-1',
       'Senior Java Entwickler (m/w/d) Spring Boot',
       'Wir suchen für unseren Kunden einen Entwickler mit Erfahrung in Spring Boot und Kubernetes.',
       'Wir suchen für unseren Kunden einen Entwickler mit Erfahrung in Spring Boot und Kubernetes. Remote möglich.',
       'https://example.invalid/projekt/smoke-1',
       'smoke-fingerprint-1',
       'PASSED', 88, 'SHORTLISTED',
       'Köln', 'smoke-portal', 'Smoke Consulting GmbH',
       DATE '2026-09-01', DATE '2026-10-01', 'de'
  FROM source s
 WHERE s.name = 'smoke-seed'
   AND NOT EXISTS (SELECT 1 FROM offer WHERE external_id = 'smoke-1');

-- At NEW, which is where the shortlist opens one. The suite moves it to PACKAGED through
-- the endpoint, because that transition is what triggers the build.
INSERT INTO application (offer_id, status)
SELECT o.id, 'NEW'
  FROM offer o
 WHERE o.external_id = 'smoke-1'
   AND NOT EXISTS (SELECT 1 FROM application a WHERE a.offer_id = o.id);
