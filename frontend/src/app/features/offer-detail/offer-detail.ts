import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  ElementRef,
  inject,
  Injector,
  input,
  OnInit,
  signal,
  viewChild,
} from '@angular/core';
import {injectDispatch} from '@ngrx/signals/events';
import {TranslocoPipe, TranslocoService} from '@jsverse/transloco';
import {ApplicationUpdate} from '@core/model/application';
import {ContentBlock, Offer} from '@core/model/offer';
import {ScoreReason} from '@core/model/score';
import {ShortlistEntry} from '@core/model/shortlist-entry';
import {applicationEvents} from '@core/store/applications.events';
import {ApplicationsStore} from '@core/store/applications.store';
import {shortlistEvents} from '@core/store/shortlist.events';
import {ScoringModelStore} from '@core/store/scoring-model.store';
import {ShortlistStore} from '@core/store/shortlist.store';
import {ApplicationPanel} from './application-panel/application-panel';
import {AskPanel} from './ask-panel/ask-panel';
import {Badge} from '@shared/badge/badge';
import {EmptyState} from '@shared/empty-state/empty-state';
import {Icon} from '@shared/icon/icon';
import {PageHeader} from '@shared/page-header/page-header';
import {Markdown} from '@shared/markdown/markdown';
import {Score} from '@shared/score/score';
import {DayPipe} from '@shared/date/day.pipe';
import {Router, RouterLink} from '@angular/router';

/**
 * How much advert is worth showing before it is folded away.
 *
 * <p>A character count and not a measured height, deliberately. Measuring means
 * `scrollHeight` against a clamp or a `ResizeObserver`, and both are suspended in a
 * backgrounded tab — the toggle would then be absent exactly where a screenshot says it is
 * fine. This decides from the string the component already has, which is the same answer in
 * every tab and testable without a browser. The cost is that a short ad in a narrow column
 * can wrap past the clamp and not offer the toggle; the clamp is set well above the fold
 * for that reason.
 */
const AD_FOLD_CHARS = 1200;

/**
 * The kinds this screen has a name for. A lookup and never a decision: the server owns the
 * taxonomy, so a kind that is not in here is still hidden and still revealable — it is
 * labelled by its own name instead of a translated one. That is the same reason nothing in
 * this browser types a weight or a filter stage as a union.
 */
const NAMED_KINDS = ['CHROME', 'FORM', 'TAXONOMY', 'AGENCY', 'LEGAL'];

/**
 * A stretch of the advert that is shown, or a stretch that is not.
 *
 * <p>Consecutive blocks of the same visibility collapse into one run, so three hidden
 * paragraphs in a row are one line to click and not three.
 */
interface AdRun {
  readonly index: number;
  readonly hidden: boolean;
  readonly text: string;
  readonly count: number;
  /** Distinct kinds in this run, in the order they appeared. */
  readonly kinds: readonly string[];
  /** The server's own sentences about why, one per block that carried one. */
  readonly reasons: readonly string[];
}

interface Field {
    /** A catalog key, not a sentence. */
    readonly label: string;
    readonly value: string | null;
    /** Set when the value is a link; `value` is then what the link reads as. */
    readonly href?: string;
}

@Component({
    selector: 'lg-offer-detail',
    imports: [
        ApplicationPanel,
      AskPanel,
        Badge,
      DayPipe,
        EmptyState,
        Icon,
        Markdown,
        PageHeader,
      RouterLink,
        Score,
        TranslocoPipe,
    ],
    templateUrl: './offer-detail.html',
    styleUrl: './offer-detail.css',
  // Provided as well as imported, because the field rows are data: `fields()` builds
  // strings and a template pipe cannot reach into a computed. One implementation either
  // way, which is the point of having the pipe at all.
  providers: [DayPipe],
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OfferDetail implements OnInit {
    private readonly dispatch = injectDispatch(shortlistEvents);
    private readonly applicationDispatch = injectDispatch(applicationEvents);
  private readonly transloco = inject(TranslocoService);
  private readonly dayPipe = inject(DayPipe);
    private readonly router = inject(Router);
    protected readonly store = inject(ShortlistStore);
  private readonly scoringModels = inject(ScoringModelStore);
    protected readonly applications = inject(ApplicationsStore);

    /** Bound from the route parameter by `withComponentInputBinding()`. */
    readonly id = input.required<string>();

  /**
   * Where the close control navigates, or null when this detail has no way out of its own.
   *
   * Comes from the route's `data`, so the screen that owns the column decides. The board's
   * reading column exists only while something is being read and closing it hands the lanes
   * their width back; the shortlist opens its first entry by itself, so a close there would
   * be undone on the next tick.
   *
   * The transform is the one every routed input needs: an absent value binds as `undefined`
   * and would override the declared default.
   */
  readonly closeTo = input<string | null, string | undefined>(null, {
    transform: (value) => value ?? null,
  });

  /**
   * Whether this installation can find offers related to this one.
   *
   * <p>Read off the list response the store already holds: `relatedCoverage` is null when the
   * retrieval index does not exist here, and the button is then absent rather than disabled —
   * a control the server would refuse is worse than one that says it does not exist.
   *
   * <p>It does not know whether <em>this</em> advert has been indexed. The server refuses that
   * case by name, so the failure is a sentence and never a list of arbitrary offers; hiding
   * the button for it as well would need a flag on the offer itself.
   */
  protected readonly canFindRelated = computed(() => this.store.relatedCoverage() !== null);

  /**
   * Whether anything is configured to answer a question about this advert.
   *
   * <p>The scoring model list already crosses the wire for the header's select, and this reads
   * the same key the server does — so the capability needs no second field and cannot drift
   * from it. Empty means a fresh clone, where the panel is absent rather than disabled.
   */
  protected readonly canAsk = computed(() => this.scoringModels.available().length > 0);

  /**
   * Narrow the list to this offer's neighbourhood, without leaving the offer.
   *
   * <p>It navigates to this same offer's route and changes only the query string, so above the
   * split's breakpoint the detail stays open and the left column becomes the neighbourhood —
   * with the anchor still in it, because its distance to itself is zero. `semantic` is cleared
   * with it: the two are one filter with two spellings and the server refuses the pair.
   *
   * <p><b>Below the breakpoint the detail replaces the list</b>, so the change is real but not
   * visible until the reader goes back to it, where the chip is waiting. Making the click
   * navigate to the list there would need this component to know the breakpoint, and that
   * number lives on the shortlist page beside the `matchMedia` that reads it; a second copy of
   * a media query in a second component is a worse trade than a click whose effect is one
   * screen away.
   */
  protected findRelated(id: number): void {
    void this.router.navigate(['/shortlist', id], {
      queryParams: {similar: id, semantic: null},
      queryParamsHandling: 'merge',
    });
  }

    /**
     * Fetched by id rather than found in the shortlist. The detail has to work on a reload,
     * and it has to open an offer the hard filter rejected — neither of which is in the
     * list.
     */
    protected readonly entry = computed(() => this.store.selected() ?? undefined);

    /**
     * Which offer the reader has unfolded, rather than whether the current one is unfolded.
     *
     * <p>Holding a boolean would carry the state across a navigation: open a long ad, follow a
     * link to the next offer, and its ad is unfolded too, for a decision nobody made about it.
     * Holding the id makes the reset fall out of the comparison and needs no effect to undo.
     */
    private readonly unfolded = signal<number | null>(null);

    private readonly adText = computed(() => {
        const offer = this.entry()?.offer;
        return offer?.fullText ?? offer?.description ?? '';
    });

  /**
   * Which hidden runs the reader has opened, as `<offer id>:<run index>`.
   *
   * <p>The offer id is in the key for the same reason `unfolded` holds one: a decision
   * about one advert must not carry to the next, and putting the id in the key makes the
   * reset fall out of the comparison instead of needing an effect to undo it.
   */
  private readonly revealed = signal<readonly string[]>([]);

  /**
   * The advert as a sequence of shown and hidden stretches.
   *
   * <p>With no blocks — the list's own entry, or an advert nothing has read that way — this
   * is one visible run holding the whole text, which is exactly what the screen did before
   * any of this existed.
   */
  protected readonly adRuns = computed<readonly AdRun[]>(() => {
    const blocks = this.entry()?.content ?? [];
    if (blocks.length === 0) {
      const text = this.adText();
      return text === '' ? [] : [{index: 0, hidden: false, text, count: 1, kinds: [], reasons: []}];
    }
    return runs(blocks);
  });

  /**
   * What is actually on the screen. The fold has to measure this and not the whole advert:
   * measured against the raw text, an ad that is mostly portal furniture offers a "show the
   * whole ad" button for content that is no longer there.
   */
  private readonly adVisibleText = computed(() =>
    this.adRuns()
      .filter((run) => !run.hidden)
      .map((run) => run.text)
      .join('\n\n'),
  );

    private readonly ad = viewChild<ElementRef<HTMLElement>>('ad');
    private readonly injector = inject(Injector);

  protected readonly adIsLong = computed(() => this.adVisibleText().length > AD_FOLD_CHARS);

    protected readonly adFolded = computed(
        () => this.adIsLong() && this.unfolded() !== this.entry()?.offer.id,
    );

    protected toggleAd(offerId: number): void {
        this.unfolded.update((open) => (open === offerId ? null : offerId));
        this.relayoutAd();
    }

  protected isRevealed(offerId: number, run: number): boolean {
    return this.revealed().includes(key(offerId, run));
  }

  /** Show a hidden stretch where it stood, or put it back. */
  protected toggleRun(offerId: number, run: number): void {
    const id = key(offerId, run);
    this.revealed.update((open) => (open.includes(id) ? open.filter((k) => k !== id) : [...open, id]));
    this.relayoutAd();
  }

  /**
   * The kinds of a hidden run, ready to read: a translated name where this screen has one,
   * the server's own word where it does not.
   */
  protected kinds(run: AdRun): string {
    return run.kinds.map((kind) => (NAMED_KINDS.includes(kind) ? this.transloco.translate(`detail.kind.${kind}`) : kind)).join(', ');
  }

    /**
     * Forces the advert's box to be measured again after the fold has been toggled.
     *
     * <p>A workaround, and it is worth saying plainly that it stands on an observation
     * rather than on a reproduced cause. In Safari the panel intermittently keeps its folded
     * height after the class comes off — measured on the page: `max-height: none`,
     * `overflow: visible`, no mask, and the box still exactly 390px, the clamp's own value,
     * with the advert's text running on behind the panels below it. Six isolated variants of
     * the same structure (scroll pane, grid, spanning panel, mask, the whole height chain)
     * were all correct in that Safari, and the same page measured correctly a minute later,
     * so this is a relayout WebKit sometimes leaves lying rather than a rule that holds the
     * height. Detaching the box and reading a metric off it removes the opportunity.
     *
     * <p>Chrome pays two forced layouts on a click it already relayouts for, which is
     * nothing. Delete this the day the fold survives a Safari release untouched.
     */
    private relayoutAd(): void {
        afterNextRender(
            () => {
                const element = this.ad()?.nativeElement;
                if (element === undefined) {
                    return;
                }
                const display = element.style.display;
                element.style.display = 'none';
                void element.offsetHeight;
                element.style.display = display;
            },
            {injector: this.injector},
        );
    }

    /**
     * What a factor contributed, and what it could have. Assembled here rather than in the
     * template because the two halves are one token — split across an `@if` the template's
     * own whitespace lands inside it, which is the same trap the review count fell into.
     *
     * <p>No denominator on an absolute bonus or penalty: those are points off the finished
     * share rather than a part of it, so "-30 of 0" would be a sentence that is not true.
     */
    protected points(reason: ScoreReason): string {
        const signed = `${reason.points > 0 ? '+' : ''}${reason.points}`;
        return reason.maxPoints > 0 ? `${signed}/${reason.maxPoints}` : signed;
    }

    /**
     * Judge this one offer again. Costs a language-model call, so it is a click and never
     * something the page does on its own.
     */
    protected rescore(id: number): void {
        this.dispatch.rescoreRequested(id);
    }

    /**
     * Whether the original ad can be asked for again from here: the same four conditions the
     * server checks, so the button never offers a request that would come back refused. An
     * offer the filter rejected or the archive holds is one no stage fetches for, and one that
     * has its ad already is not missing anything.
     */
    protected canFetch(entry: ShortlistEntry): boolean {
        return entry.score.hardPass && !entry.offer.archivedAt && !!entry.offer.url && !entry.offer.fullText;
    }

    /**
     * Ask the portal for this offer's ad again, past the failure the run remembered. A click,
     * because it leaves the machine and spends one of the minute's fetches.
     */
    protected refetch(id: number): void {
        this.dispatch.fetchRequested(id);
    }

    /**
     * Take this offer off the working list, or put it back.
     *
     * <p>Reachable from here rather than from the card, because it is the screen where
     * somebody has read enough of the advert to decide — and it is the only screen that
     * shows an offer whichever side of the archive it is on.
     */
    protected setArchived(id: number, archived: boolean): void {
        this.dispatch.archiveRequested({id, archived});
    }

    /**
     * An application exists only once a package has been built for the offer, so most
     * offers have none. That absence is the honest state, not an error.
     */
    protected readonly application = computed(() =>
        this.applications.applications().find((candidate) => String(candidate.offerId) === this.id()),
    );

    protected readonly history = computed(() => {
        const application = this.application();
        return application === undefined ? [] : (this.applications.history()[application.id] ?? []);
    });

    constructor() {
        // The id comes from the URL, so it changes without the component being recreated.
        effect(() => {
            const id = Number(this.id());
            if (Number.isFinite(id)) {
                this.dispatch.offerRequested(id);
            }
        });

        // The store replaces the row after every save, so this re-reads the log exactly when
        // there is something new in it — and never while the board is merely being scrolled.
        effect(() => {
            const application = this.application();
            if (application !== undefined) {
                this.applicationDispatch.historyRequested(application.id);
            }
        });
    }

    /**
     * The package as one file. A link and not a request: the server sets the filename in
     * `Content-Disposition`, so nothing here has to hold the bytes or name the archive.
     *
     * Downloading is not sending. The folder stays on the machine that ran the pipeline;
     * this only fetches a copy of it into a browser that is not on that machine.
     */
    protected packageUrl(id: number): string {
        return `/api/v1/offers/${id}/package`;
    }

    protected record(update: ApplicationUpdate): void {
        const application = this.application();
        if (application !== undefined) {
            this.applicationDispatch.changed({id: application.id, update});
        }
    }

  /** A stored day as the chosen language writes one, or null when there is none. */
  private day(value: string | null): string | null {
    return this.dayPipe.transform(value);
  }

  /**
   * Every extracted field, including the ones that came back empty. A missing
   * rate is information — it means enrichment did not reach the original ad —
   * and hiding the row would make the gap invisible.
   */
    protected readonly fields = computed<readonly Field[]>(() => {
        const offer = this.entry()?.offer;
        if (offer === undefined) {
            return [];
        }
        return [
          // Four blocks, in the order a person reads the panel: where this came from,
          // what the work is, when it runs, and what this tool wrote about the row.
          // Nothing separates them in the markup — the grid is one list — so the order is
          // the only thing carrying the grouping, which is why a new row belongs inside a
          // block and never simply at the end, and why every block holds an even number
          // of fields: the grid is two columns wide, and an odd block folds the next
          // one's first row up beside its own last.

          // Where it came from. First, because these are the only rows that say that
          // rather than what the advert says: `portal` is who advertises the project,
          // `source` is the configured input that delivered it, named as the sources
          // screen names it, and the external id is the ad itself, one click away.
          {label: 'field.source', value: offer.sourceName},
            {label: 'field.portal', value: offer.portal},
            {label: 'field.agency', value: offer.agency},
          this.externalId(offer),

          // What the work is. The four terms that decide whether the rest is worth
          // reading; `workload` sits with the remote share rather than after the
          // deadline, because both answer "how much of the week", not "when".
            {label: 'field.location', value: offer.location},
            {
                label: 'field.remoteShare',
                value: offer.remotePercent === null ? null : `${offer.remotePercent} %`,
            },
          {label: 'field.workload', value: offer.workload},
            {label: 'field.rate', value: offer.rateEur === null ? null : `${offer.rateEur} €/h`},

          // When it runs. Four rows rather than three, because the panel is a
          // two-column grid: a block with an odd number of fields pushes the next one's
          // first row up beside its own last, and the grouping the order carries is then
          // broken exactly where it is supposed to be read. `published` closes this block
          // for that reason and because it belongs to the advert's own timeline.
          //
          // The phrase first, the resolved value only when there is no phrase. "ab sofort"
          // is what the advert said and is often the whole truth; a day nobody can quote it
          // for would be the one line on this panel that cannot be checked against the ad
          // beside it. The server already refuses such a value, so this is the reading order
          // and not a second guard.
          {label: 'field.start', value: offer.startText ?? this.day(offer.startsOn)},
            {label: 'field.duration', value: offer.duration},
          {label: 'field.deadline', value: offer.applyByText ?? this.day(offer.applyBy)},
          {label: 'field.published', value: this.day(offer.publishedOn)},

          // What this tool wrote about the row rather than what the advert says. The
          // ingest day opens the last block and so sits diagonally under `published`,
          // which is as close as a two-column grid gets: the two are only useful read
          // against each other, an advert published in July and first seen here in
          // September being a stale listing that neither row states alone.
          {label: 'field.ingested', value: this.day(offer.ingestedAt)},
          // The language of the ad, the one thing that picks a CV. It ends the panel
          // because it is read once, not compared with anything.
            {label: 'field.language', value: offer.language},
        ];
    });

    /**
     * The identity a source gave the offer, which for every portal in the corpus is the
     * ad's own URL — 60 characters of unbroken path that pushed the panel past its border,
     * because a URL has nothing to wrap at.
     *
     * So it reads as the portal it points at and carries the URL in its title. The name
     * rather than the address: the address is what broke the layout, and it is one click
     * away in the same place it was before. An id that is not a URL — a content hash, the
     * fallback for a document that carries no link — stays text.
     */
    private externalId(offer: Offer): Field {
        const url = this.httpUrl(offer.externalId);
        if (url === null) {
            return {label: 'field.externalId', value: offer.externalId};
        }
        return {
            label: 'field.externalId',
            value: offer.portal ?? url.host,
            href: url.href,
        };
    }

    /**
     * `URL` accepts a mail scheme and a `javascript:` one alike, and neither belongs in an
     * `href` here. The mail scheme is spelled out rather than written as a literal because
     * `NothingIsSentTest` greps this tree for it — a comment explaining why the tool does
     * not send is not the thing that guard is looking for, but it cannot tell the two apart
     * and a guard that has to be argued with is a guard that gets switched off.
     */
    private httpUrl(value: string | null): URL | null {
        if (value === null) {
            return null;
        }
        try {
            const url = new URL(value);
            return url.protocol === 'https:' || url.protocol === 'http:' ? url : null;
        } catch {
            return null;
        }
    }

    ngOnInit(): void {
        this.applicationDispatch.opened();
    }
}

function key(offerId: number, run: number): string {
  return `${offerId}:${run}`;
}

/**
 * Consecutive blocks of the same visibility, collapsed into runs.
 *
 * <p>A block is only hidden when something positively decided it is not the advert. That is
 * the whole safety property of this feature, and it is one comparison: everything else —
 * a kind nobody recognised, a block no rule matched and no model answered about — is shown.
 */
function runs(blocks: readonly ContentBlock[]): readonly AdRun[] {
  const out: AdRun[] = [];
  for (const block of blocks) {
    const hidden = block.kind !== 'CONTENT';
    const last = out.at(-1);
    if (last === undefined || last.hidden !== hidden) {
      out.push({
        index: out.length,
        hidden,
        text: block.text,
        count: 1,
        kinds: hidden ? [block.kind] : [],
        reasons: block.reason === null ? [] : [block.reason],
      });
      continue;
    }
    out[out.length - 1] = {
      ...last,
      text: `${last.text}\n\n${block.text}`,
      count: last.count + 1,
      kinds: hidden && !last.kinds.includes(block.kind) ? [...last.kinds, block.kind] : last.kinds,
      reasons: block.reason === null || last.reasons.includes(block.reason) ? last.reasons : [...last.reasons, block.reason],
    };
  }
  return out;
}
