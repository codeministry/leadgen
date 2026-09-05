import {HttpClient} from '@angular/common/http';
import {inject, Injectable} from '@angular/core';
import {Observable} from 'rxjs';
import {LastRunView} from '@core/model/last-run';
import {scoringModelParams} from './scoring-model-param';

/** Mirrors `de.codeministry.leadgen.ingest.DocumentIngestResult`. */
export interface DocumentIngestResult {
  readonly documentId: string;
  readonly extracted: number;
  /** What the document said it contained. Null when the source cannot state a count. */
  readonly announced: number | null;
  readonly complete: boolean;
}

export interface SourceIngestResult {
  readonly sourceId: string;
  readonly documents: number;
  readonly extracted: number;
  readonly written: number;
  readonly details: readonly DocumentIngestResult[];
}

/** Mirrors `de.codeministry.leadgen.filter.FilterReport`. */
export interface FilterReport {
  /** Offers rejected per stage, keyed by the stage name. Only stages that rejected appear. */
  readonly removed: Readonly<Record<string, number>>;
  readonly passed: number;
  readonly considered: number;
}

/** Mirrors `de.codeministry.leadgen.enrich.EnrichmentReport`. */
export interface EnrichmentReport {
  readonly considered: number;
  readonly enriched: number;
  /** Left in the pipeline with a note. An unreadable ad never discards an offer. */
  readonly incomplete: number;
  /** Answered without a request. Equals `considered` on a second run inside the TTL. */
  readonly fromCache: number;
  /** Actual HTTP requests for ads, robots.txt excluded. */
  readonly requests: number;
    /**
     * Turned away by the rate limiter. Nothing was written for these, so they are due again
     * on the next pass — which is the whole difference between this and `incomplete`.
     */
    readonly deferred: number;
}

/** Mirrors `de.codeministry.leadgen.score.ScoringReport`. */
export interface ScoringReport {
  /**
   * Everything on the shortlist's own terms, not what this run looked at. Only `scored`
   * and `submitted` count this run: a run judges what is stale, so finding nothing new is
   * the normal case, and per-run counts would report an empty shortlist for an idle pass.
   */
  readonly considered: number;
  readonly scored: number;
  /** Standing, not this run: offers whose total was withheld because nothing judged them. */
  readonly unscored: number;
  readonly shortlisted: number;
  readonly review: number;
  /**
   * Handed to a batch instead of judged in the run. Their scores arrive minutes later,
   * and so do the packages and the digest. Never non-zero together with `scored`.
   */
  readonly submitted: number;
}

/** Mirrors `de.codeministry.leadgen.packaging.PackageReport`. */
export interface PackageReport {
  readonly due: number;
  readonly built: number;
  readonly failed: number;
  /** Folders on disk. The tool has no send path at all. */
  readonly folders: readonly string[];
}

export interface IngestReport {
  readonly sources: readonly SourceIngestResult[];
    /**
     * When the run ended, as an ISO instant in UTC. The panel needs it for the same reason
     * `LastRunView` carries it: without a time, a pass that ran for three hours and a click
     * from a minute ago read identically.
     */
    readonly finishedAt: string;
  readonly extracted: number;
  /** Rows touched, insert or update alike. Lower than `extracted` when a listing repeats. */
  readonly written: number;
  /**
   * Offers attached to a primary after the run. Not the same collapse as
   * `extracted - written`: that one is a listing seen twice, this one is a project
   * several portals advertise at once.
   */
  readonly merged: number;
  /**
   * What the hard filter did. The share that survives is the daily language-model
   * budget, so this is the number the whole economics rests on.
   */
  readonly filtered: FilterReport;
  /**
   * What fetching the original ads did. The only stage that leaves the machine, and
   * the only one that can fail for reasons unrelated to the offer.
   */
  readonly enriched: EnrichmentReport;
  /** What the shortlist looks like afterwards. */
  readonly scored: ScoringReport;
  /** The digest file the run wrote, or null when it is switched off. A file, never a message. */
  readonly digest: string | null;
  /** The application packages built for everything above the shortlist threshold. */
  readonly packaged: PackageReport;
}

@Injectable({ providedIn: 'root' })
export class IngestApi {
  private readonly http = inject(HttpClient);

  /**
   * @param model which judge scores this pass, or null for the one the server prefers.
   *     Omitted rather than sent empty: an empty parameter would have to mean the default
   *     on the other side as well, and that is a second way to say the same thing.
   */
  run(model: string | null): Observable<IngestReport> {
    return this.http.post<IngestReport>('/api/ingest', {}, { params: scoringModelParams(model) });
  }

  /**
   * What the last run did, whoever started it. Null when nothing ever has — the server
   * answers 204, and Angular hands a 204 over as a null body, which is the same
   * distinction the status code was chosen for: "no run" is not "a run with zero counts".
   */
  last(): Observable<LastRunView | null> {
    return this.http.get<LastRunView | null>('/api/ingest/last');
  }
}
