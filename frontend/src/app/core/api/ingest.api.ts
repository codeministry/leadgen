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
}

@Injectable({ providedIn: 'root' })
export class IngestApi {
  private readonly http = inject(HttpClient);

  run(): Observable<IngestReport> {
    return this.http.post<IngestReport>('/api/ingest', {});
  }
}
