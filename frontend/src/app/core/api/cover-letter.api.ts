import {HttpClient} from '@angular/common/http';
import {inject, Injectable} from '@angular/core';
import {Observable} from 'rxjs';
import {CoverLetter} from '@core/model/cover-letter';

/**
 * `/api/v1/offers/{id}/cover-letter` — the letter in an offer's package.
 *
 * Three calls and one answer shape. The read is 404 while there is no package; the save and
 * the draft are 409 once the application is sent, and the draft is 429 when the model budget
 * is spent. The store turns those into sentences; this seam only carries them.
 */
@Injectable({providedIn: 'root'})
export class CoverLetterApi {
    private readonly http = inject(HttpClient);

    read(offerId: number): Observable<CoverLetter> {
        return this.http.get<CoverLetter>(`/api/v1/offers/${offerId}/cover-letter`);
    }

    /** Rewrites `cover_letter.txt` and the stored copy; the answer's author is `edited`. */
    save(offerId: number, text: string): Observable<CoverLetter> {
        return this.http.put<CoverLetter>(`/api/v1/offers/${offerId}/cover-letter`, {text});
    }

    /**
     * A fresh draft at one budget call, synchronous like `POST /offers/{id}/fetch`. The answer's
     * author is `model`, or `template` when the model's draft was refused or there is no model.
     */
    draft(offerId: number): Observable<CoverLetter> {
        return this.http.post<CoverLetter>(`/api/v1/offers/${offerId}/cover-letter/draft`, null);
    }
}
