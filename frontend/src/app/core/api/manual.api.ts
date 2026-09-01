import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ManualOfferFields, PendingDocument } from '@core/model/manual-document';

/** `/api/sources/manual` — the endpoint that puts a document on disk. */
@Injectable({ providedIn: 'root' })
export class ManualApi {
  private readonly http = inject(HttpClient);

  pending(): Observable<readonly PendingDocument[]> {
    return this.http.get<readonly PendingDocument[]>('/api/sources/manual/pending');
  }

  /**
   * Multipart, with the field named `file`. No `Content-Type` header is set: the browser
   * has to add the multipart boundary, and setting it by hand produces a request the
   * server cannot parse.
   */
  upload(file: File): Observable<PendingDocument> {
    const body = new FormData();
    body.append('file', file, file.name);
    return this.http.post<PendingDocument>('/api/sources/manual/documents', body);
  }

  confirm(name: string, fields: ManualOfferFields): Observable<PendingDocument> {
    return this.http.post<PendingDocument>(
      `/api/sources/manual/pending/${encodeURIComponent(name)}/confirm`,
      fields,
    );
  }

  reject(name: string): Observable<void> {
    return this.http.delete<void>(`/api/sources/manual/pending/${encodeURIComponent(name)}`);
  }
}
