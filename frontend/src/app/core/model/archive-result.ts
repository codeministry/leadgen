/**
 * What one archive-by-hand wrote, as `POST /api/offers/archive` answers it.
 *
 * Mirrors `de.codeministry.leadgen.archive.ArchiveResult`. Two counts and not the rows: the
 * list drops an archived offer rather than replacing it, so entries would be fetched only to
 * be discarded — and a page of them is measured in megabytes here.
 *
 * `archived` below `requested` means an id named no offer. That costs a number and nothing
 * else, which is why the endpoint answers 200 rather than refusing the whole set.
 */
export interface ArchiveResult {
  readonly requested: number;
  readonly archived: number;
  readonly unscored: number;
}
