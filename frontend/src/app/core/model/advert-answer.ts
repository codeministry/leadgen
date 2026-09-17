/**
 * What one advert says about one bounded question.
 *
 * Mirrors `de.codeministry.leadgen.ask.AdvertAnswer`.
 *
 * @param stated whether the advert addresses the question at all. **The distinction this type
 *     exists for**, and the screen has to render the two differently: "the advert is silent on
 *     the rate" and "the advert offers 95 EUR" are different answers, and a shrug drawn like a
 *     fact invites the reader to fill the gap themselves.
 * @param answer one or two sentences in the advert's language, or null when it is silent.
 * @param quote the sentence from the advert the answer rests on, verbatim. The server drops any
 *     claim whose quote it could not find in the advert, so a rendered answer always has one —
 *     which is what makes it checkable by looking up.
 * @param model which model answered, so two answers that disagree can be told apart.
 */
export interface AdvertAnswer {
    readonly question: string;
    readonly stated: boolean;
    readonly answer: string | null;
    readonly quote: string | null;
    readonly model: string;
}

/**
 * The questions an advert answers. The server holds the same list as an enum and refuses
 * anything else, so this is a mirror and never the source.
 */
export const ADVERT_QUESTIONS = ['rate', 'client', 'onsite', 'onboarding', 'extension'] as const;

export type AdvertQuestion = (typeof ADVERT_QUESTIONS)[number];
