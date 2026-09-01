import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

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
}

/** Mirrors `de.codeministry.leadgen.score.ScoringReport`. */
export interface ScoringReport {
  readonly considered: number;
  readonly scored: number;
  /** Above zero means no language model was configured; the offers are there, unranked. */
  readonly unscored: number;
  readonly shortlisted: number;
  readonly review: number;
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

  run(): Observable<IngestReport> {
    return this.http.post<IngestReport>('/api/ingest', {});
  }
}
