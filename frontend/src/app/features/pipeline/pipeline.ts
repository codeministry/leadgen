import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  ElementRef,
  inject,
  OnInit,
  viewChild,
} from '@angular/core';
import {toSignal} from '@angular/core/rxjs-interop';
import {
  CdkDrag,
  CdkDragDrop,
  CdkDragHandle,
  CdkDropList,
  CdkDropListGroup,
} from '@angular/cdk/drag-drop';
import {CdkScrollable} from '@angular/cdk/scrolling';
import {ActivatedRoute, NavigationEnd, Router, RouterLink, RouterOutlet} from '@angular/router';
import {filter, map} from 'rxjs';
import {injectDispatch} from '@ngrx/signals/events';
import {TranslocoPipe} from '@jsverse/transloco';
import {ApplicationStatus, ApplicationView} from '@core/model/application';
import {applicationEvents} from '@core/store/applications.events';
import {ApplicationsStore} from '@core/store/applications.store';
import {EmptyState} from '@shared/empty-state/empty-state';
import {Icon} from '@shared/icon/icon';
import {PageHeader} from '@shared/page-header/page-header';
import {Score} from '@shared/score/score';
import {StatusPicker} from '@shared/status-picker/status-picker';

@Component({
    selector: 'lg-pipeline',
    imports: [
        CdkDrag,
        CdkDragHandle,
        CdkDropList,
        CdkDropListGroup,
        CdkScrollable,
        EmptyState,
        Icon,
        PageHeader,
        RouterLink,
        RouterOutlet,
        Score,
        StatusPicker,
        TranslocoPipe,
    ],
    templateUrl: './pipeline.html',
    styleUrl: './pipeline.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
    // Whether the right column is showing an offer, the same flag the shortlist carries: the
    // page header sits outside the split and has to disappear with the board on a narrow
    // screen, so one class is read by every rule that needs it.
  host: {
    '[class.detail-open]': 'selectedId() !== null',
    // On the screen and not on the reading column: a reader who has just clicked a card
    // still has the focus in the board, which is exactly the moment Escape is wanted.
    // The shortlist binds `j`/`k` to its list pane for the opposite reason — those are
    // letters somebody types into the search field, and Escape is nobody's text.
    '(keydown.escape)': 'closeDetail($event)',
  },
})
export class Pipeline implements OnInit {
    private readonly dispatch = injectDispatch(applicationEvents);
    private readonly router = inject(Router);
    private readonly route = inject(ActivatedRoute);
    protected readonly store = inject(ApplicationsStore);

    private readonly detailPane = viewChild<ElementRef<HTMLElement>>('detailPane');

    /** The URL, only as a reason to look at the snapshot again — as in the shell. */
    private readonly navigated = toSignal(
        this.router.events.pipe(
            filter((event) => event instanceof NavigationEnd),
            map(() => this.router.url),
        ),
        {initialValue: this.router.url},
    );

    /**
     * The offer the child route is showing. Read from the route rather than held here, so a
     * deep link, the back button and a card click cannot disagree about what is open.
     */
  /**
   * Escape deselects the offer, which is the same navigation the close control makes.
   *
   * Ignored while nothing is open, so the key stays free for whatever else might want it,
   * and ignored inside a native `select` — every card carries the status picker, and a
   * browser that is showing its dropdown has its own answer for Escape. A closed select
   * does not bubble anything worth keeping either way; the guard is there so the two
   * meanings can never be confused by a reader of this file.
   */
  protected closeDetail(event: Event): void {
    // `Event` and not `KeyboardEvent`: Angular types `$event` on a keyed host binding as
    // the base type, and `strictTemplates` refuses the narrower signature at build time
    // only — `check:static` does not run the template compiler and says nothing.

    if (this.selectedId() === null) {
      return;
    }
    if ((event.target as HTMLElement | null)?.closest('select') !== null) {
      return;
    }
    event.preventDefault();
    void this.router.navigate(['/pipeline']);
  }

    protected readonly selectedId = computed<number | null>(() => {
        this.navigated();
        const raw = this.route.snapshot.firstChild?.paramMap.get('id') ?? null;
        const id = Number(raw);
        return raw !== null && Number.isFinite(id) ? id : null;
    });

    constructor() {
        // A new offer starts at its own top; the column survives the navigation and would
        // otherwise open the second one wherever the first was left.
        effect(() => {
            this.selectedId();
            const pane = this.detailPane()?.nativeElement;
            if (pane !== undefined) {
                pane.scrollTop = 0;
            }
        });
    }

    ngOnInit(): void {
        this.dispatch.opened();
    }

    /**
     * The picker sends a string because `shared/` does not know the eleven states. The
     * cast is safe for exactly that reason: the options it was given came from here.
     */
    protected move(application: ApplicationView, status: string): void {
        this.dispatch.changed({
            id: application.id,
            update: {status: status as ApplicationStatus},
        });
    }

    /**
     * A card let go of in another state's zone. The same one-line dispatch `move` makes —
     * drag is a second gesture for the one write, not a second write path.
     *
     * A drop in the zone it came from is not a request: the server records no event row for
     * a status that did not change, so the round trip would buy a card greying out and
     * nothing else.
     */
    protected dropped(event: CdkDragDrop<ApplicationStatus>): void {
        if (event.previousContainer === event.container) {
            return;
        }
        const application: ApplicationView = event.item.data;
        this.dispatch.changed({
            id: application.id,
            update: {status: event.container.data},
        });
    }

    /**
     * Shows the state zones, on the press rather than on the drag.
     *
     * At rest a lane is one list in the states' own order, which is what a reader scans; a
     * target to aim at is only worth its height while there is something to aim. The moment
     * it appears is measured, not chosen: the CDK caches every container's rectangle at the
     * first move past the threshold and never asks again, so a reveal on `cdkDragStarted`
     * lands after the measurement and the lanes are measured collapsed. A drop over a zone
     * revealed that way produced no request at all; the same drop with the zones already
     * open wrote `{"status":"LOST"}`. `pointerdown` is unconditionally before the first
     * move, which is what makes the geometry the CDK reads the geometry on the screen.
     *
     * Bound to the grip rather than to the card, so pressing a card to read it never opens
     * them: only a hand already on the drag affordance does.
     *
     * The class is written straight onto the element instead of through a signal binding,
     * and that is the whole point: change detection runs a frame later, and a frame later is
     * exactly the race this method exists to avoid.
     */
    protected showTargets(event: PointerEvent): void {
        const grip = event.currentTarget as HTMLElement;
        this.board = grip.closest('.board');
        this.board?.classList.add('picking');
        // A press that never becomes a drag ends here; a drag ends in `cdkDragEnded`. Both
        // close the zones, and `once` means neither leaves a listener behind.
        const close = (): void => this.hideTargets();
        window.addEventListener('pointerup', close, {once: true});
        window.addEventListener('pointercancel', close, {once: true});
    }

    protected hideTargets(): void {
        this.board?.classList.remove('picking');
    }

    private board: HTMLElement | null = null;
}
