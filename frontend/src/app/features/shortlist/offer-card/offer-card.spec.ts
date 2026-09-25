import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {TranslocoService} from '@jsverse/transloco';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {ScoreReason} from '@core/model/score';
import {provideRouter} from '@angular/router';
import {signal} from '@angular/core';
import {ShortlistEntry} from '@core/model/shortlist-entry';
import {SCORE_THRESHOLDS} from '@shared/shared.ports';
import {ListDensity} from '@core/density/density.model';
import {By} from '@angular/platform-browser';
import {Icon} from '@shared/icon/icon';
import {LG_ICONS} from '@shared/icon/lucide-icons';
import {OfferCard, STATUS_ICONS} from './offer-card';

/** jsdom lays nothing out, so a rule the card depends on is read from its source. */
const CARD_CSS = readFileSync(
  resolve(process.cwd(), 'src/app/features/shortlist/offer-card/offer-card.css'),
  'utf8',
);

function entry(id: number): ShortlistEntry {
    return {
        offer: {
            id,
            sourceName: 'sample-newsletter',
            externalId: `https://example.invalid/${id}`,
            title: 'Senior Java Entwickler',
            description: 'Ablösung eines Monolithen.',
            url: `https://example.invalid/${id}`,
            location: 'Köln',
            portal: 'portal-a',
            agency: null,
            publishedOn: '2026-09-01',
            tags: ['Java'],
            rateEur: null,
            remotePercent: null,
            startsOn: null,
          startText: null,
          durationMonths: null,
          applyBy: null,
          applyByText: null,
            duration: null,
            workload: null,
            language: 'de',
            fullText: null,
            packageDir: null,
          ingestedAt: '2026-09-02T05:12:00Z',
            archivedAt: null,
            archiveSource: null,
            enrichmentNote: null,
        },
        score: {value: 88, hardPass: true, reasons: [], model: null, rulesetVersion: '1'},
      flags: {incomplete: false, remoteUnknown: true, possibleDuplicate: false},
        sources: [{portal: 'portal-a', agency: null, url: `https://example.invalid/${id}`}],
      content: [],
    };
}

describe('OfferCard', () => {
  async function render(
    selected: boolean,
    inputs: Record<string, unknown> = {},
    lang = 'en',
  ): Promise<ComponentFixture<OfferCard>> {
        await TestBed.configureTestingModule({
            providers: [
                provideRouter([]),
                {provide: SCORE_THRESHOLDS, useValue: signal({shortlistAt: 70, reviewAt: 50})},
            ],
        }).compileComponents();
    TestBed.inject(TranslocoService).setActiveLang(lang);
        const fixture = TestBed.createComponent(OfferCard);
        fixture.componentRef.setInput('entry', entry(2));
        fixture.componentRef.setInput('selected', selected);
    for (const [name, value] of Object.entries(inputs)) {
      fixture.componentRef.setInput(name, value);
    }
        fixture.detectChanges();
        return fixture;
    }

  function checkbox(fixture: ComponentFixture<OfferCard>): HTMLInputElement {
    return fixture.nativeElement.querySelector('input[type="checkbox"]') as HTMLInputElement;
  }

  describe.each<ListDensity>(['comfortable', 'compact'])('in the %s density (ISC-382)', (density) => {
      it('links into the split view and carries the filters with it', async () => {
          // Without `queryParamsHandling`, opening an offer drops the query string and the list
          // reloads unfiltered — which looks like a store bug and is a missing attribute.
          const fixture = await render(false, {density});

          const link = fixture.nativeElement.querySelector('.title a') as HTMLAnchorElement;
          expect(link.getAttribute('href')).toBe('/shortlist/2');
          // The attribute is the whole mechanism; that the resulting href really carries `?q=…`
          // needs a router with a query string behind it and is measured in the browser instead.
          expect(link.getAttribute('queryParamsHandling')).toBe('preserve');
        expect(fixture.nativeElement.querySelector('.offer.is-selected')).toBeNull();
      });

      it('says which card the detail column is showing', async () => {
          const fixture = await render(true, {density});

        expect(fixture.nativeElement.querySelector('.offer.is-selected')).not.toBeNull();
          expect(fixture.nativeElement.querySelector('.title a').getAttribute('aria-current')).toBe(
              'true',
          );
      });

    it('offers no selection where there is no bulk restore', async () => {
      // The archive side passes `pickable: false`. The card is told; it never learns what an
      // archive is.
      const fixture = await render(false, {density});

      expect(checkbox(fixture)).toBeNull();
    });

    it('keeps the checkbox out of the stretched link', async () => {
      // The behavioural half — that a click on the box does not navigate — needs a real
      // browser, because it is decided by `z-index` against `.title a::after`. What jsdom can
      // answer is the structure that rule depends on: the input is not inside the anchor.
      const fixture = await render(false, {density, pickable: true});

      const box = checkbox(fixture);
      expect(box).not.toBeNull();
      expect(fixture.nativeElement.querySelector('.title a').contains(box)).toBe(false);
      expect(box.closest('.pick')).not.toBeNull();
    });

    it('is a column of the card, beside the score and the title', async () => {
      // The structural half of the placement decision, which the column width was widened
      // for: score, title and checkbox read as one row only while the box is a sibling of
      // `.main` rather than something inside it. Moved into the body it drops below the
      // score ring, and `--lg-list-w` is then two rem wider for nothing.
      const fixture = await render(false, {density, pickable: true});

      const card = fixture.nativeElement.querySelector('.offer') as HTMLElement;
      const pick = card.querySelector('.pick')!;
      expect(pick.parentElement).toBe(card);
      expect(card.querySelector('.main')!.contains(pick)).toBe(false);
    });

    it('does not let the browser write its own checked state', async () => {
      // Without the `preventDefault`, a Shift-click on an already-ticked card leaves the box
      // showing the opposite of the truth: the browser unticks it, the range keeps it ticked,
      // and `[checked]` sees no change to reconcile.
      const fixture = await render(false, {density, pickable: true, picked: true});

      const event = new MouseEvent('click', {bubbles: true, cancelable: true});
      checkbox(fixture).dispatchEvent(event);

      expect(event.defaultPrevented).toBe(true);
    });

    it('says a Shift-click was a Shift-click', async () => {
      const fixture = await render(false, {density, pickable: true});
      const emitted: { id: number; range: boolean }[] = [];
      fixture.componentInstance.pickToggled.subscribe((event) => emitted.push(event));

      checkbox(fixture).dispatchEvent(new MouseEvent('click', {bubbles: true, cancelable: true}));
      checkbox(fixture).dispatchEvent(
        new MouseEvent('click', {bubbles: true, cancelable: true, shiftKey: true}),
      );

      expect(emitted).toEqual([
        {id: 2, range: false},
        {id: 2, range: true},
      ]);
    });

    it('names the offer its checkbox selects', async () => {
      // Fifty identical checkboxes are fifty unlabelled controls.
      const fixture = await render(false, {density, pickable: true});

      expect(checkbox(fixture).getAttribute('aria-label')).toContain('Senior Java Entwickler');
    });

    it('keeps the focus ring on the card, drawn by the title link', async () => {
      // The ring is `.offer:has(.title a:focus-visible)`: it holds only while the anchor stays
      // inside `.title` inside `.offer` in this density too. jsdom draws no outline, so the
      // structure the rule depends on is what can be asserted here; the ring itself is live.
      const fixture = await render(false, {density});

      const link = fixture.nativeElement.querySelector('.offer .title a') as HTMLAnchorElement;
      expect(link).not.toBeNull();
      expect(link.getAttribute('tabindex')).toBeNull();
      expect(CARD_CSS).toMatch(/\.offer:has\(\.title a:focus-visible\)\s*\{[^}]*outline:\s*2px solid/);
      expect(CARD_CSS).toMatch(/\.title a:focus-visible\s*\{[^}]*outline:\s*none/);
    });
  });

  function text(fixture: ComponentFixture<OfferCard>, selector: string): string {
    return (fixture.nativeElement.querySelector(selector) as HTMLElement | null)?.textContent ?? '';
  }

  /**
   * The icons inside `root`, by name and in document order. Read from the component rather
   * than the DOM, because a bound `[name]` leaves no attribute behind to query.
   */
  function icons(fixture: ComponentFixture<OfferCard>, root: Element): string[] {
    return fixture.debugElement
      .queryAll(By.directive(Icon))
      .filter((node) => root.contains(node.nativeElement as Node))
      .map((node) => (node.componentInstance as Icon).name());
  }

  /** Every icon's svg is hidden from assistive technology: none of them carries a label. */
  function allHidden(root: Element): boolean {
    const svgs = Array.from(root.querySelectorAll('lg-icon svg'));
    return svgs.length > 0 && svgs.every((svg) => svg.getAttribute('aria-hidden') === 'true');
  }

  function withReasons(reasons: ScoreReason[], value: number | null = 88): ShortlistEntry {
    const base = entry(2);
    return {...base, score: {...base.score, value, reasons}};
  }

  describe('why it scored (ISC-376)', () => {
    const reasons: ScoreReason[] = [
      {factor: 'core_skill_overlap', label: 'skills', points: 45, maxPoints: 45},
      {factor: 'role_fit', label: 'role', points: 15, maxPoints: 15},
      {factor: 'industry_fit', label: 'industry', points: 14, maxPoints: 15},
      {factor: 'seniority_fit', label: 'senior', points: 13, maxPoints: 15},
      {factor: 'interest_fit', label: 'interest: Wanted topic', points: 12, maxPoints: 0, topic: 'Wanted topic'},
      {factor: 'onsite_penalty', label: 'onsite', points: -5, maxPoints: 0},
      {factor: 'agency_penalty', label: 'agency chain', points: -20, maxPoints: 0},
    ];

    it('shows the strongest lift, the strongest penalty and every topic, and nothing else', async () => {
      const fixture = await render(false, {entry: withReasons(reasons)});

      const why = fixture.nativeElement.querySelector('.offer-why') as HTMLElement;
      expect(why.querySelectorAll('.offer-lift')).toHaveLength(1);
      expect(why.querySelectorAll('.offer-penalty')).toHaveLength(1);
      expect(text(fixture, '.offer-lift')).toContain('skills');
      expect(text(fixture, '.offer-penalty')).toContain('agency chain');
      // Four positives outrank the topic, and its name shows all the same.
      expect(text(fixture, '.offer-topics')).toContain('Wanted topic');
      for (const hidden of ['role', 'industry', 'senior', 'onsite']) {
        expect(why.textContent).not.toContain(hidden);
      }
    });

    it('carries labels only, never points', async () => {
      const fixture = await render(false, {entry: withReasons(reasons)});

      expect(text(fixture, '.offer-why')).not.toMatch(/\d/);
    });

    it('is one truncated line marked with icons a screen reader never reads', async () => {
      const fixture = await render(false, {entry: withReasons(reasons)});

      const why = fixture.nativeElement.querySelector('.offer-why') as HTMLElement;
      expect(why.classList).toContain('min-w-0');
      for (const item of Array.from(why.children)) {
        expect(item.classList).toContain('truncate');
      }
      // Topic, penalty, lift — in that order, one icon each, and no Unicode glyph left over.
      expect(icons(fixture, why)).toEqual(['tag', 'trending-down', 'trending-up']);
      expect(why.textContent).not.toMatch(/[▲▼◆]/);
      expect(allHidden(why)).toBe(true);
      // Each icon has a text beside it that says what it means.
      expect(why.querySelectorAll('.sr-only')).toHaveLength(3);
    });

    it('keeps saying "not scored" for an offer without reasons, on a line that is never clipped', async () => {
      const fixture = await render(false, {entry: withReasons([], null)});

      const notScored = fixture.nativeElement.querySelector('.offer-not-scored') as HTMLElement;
      expect(notScored.textContent).toContain('Not scored');
      expect(notScored.closest('.truncate')).toBeNull();
      expect(notScored.classList).not.toContain('truncate');
      expect(fixture.nativeElement.querySelector('.offer-why')).toBeNull();
      expect(fixture.nativeElement.querySelector('.offer-lift')).toBeNull();
    });

    it('shortens a production-shaped skill label and puts it last, so ▼ and ◆ are never clipped', async () => {
      const skills =
        'Java, Spring Boot, Kafka, Kubernetes, Angular, PostgreSQL, Docker, AWS and 3 more (skill weight 3, a full match is 30)';
      const fixture = await render(false, {
        entry: withReasons([
          {factor: 'core_skill_overlap', label: skills, points: 30, maxPoints: 45},
          {factor: 'interest_fit', label: 'interest: Wanted topic', points: 12, maxPoints: 0, topic: 'Wanted topic'},
          {factor: 'agency_penalty', label: 'agency chain', points: -20, maxPoints: 0},
        ]),
      });

      const why = text(fixture, '.offer-why');
      expect(why).not.toMatch(/\d/);
      expect(why).not.toContain('(');
      expect(text(fixture, '.offer-lift')).toContain('Java, Spring Boot');
      const order = Array.from(
        (fixture.nativeElement.querySelector('.offer-why') as HTMLElement).children,
      ).map((node) => node.className);
      expect(order.findIndex((name) => name.includes('offer-topics'))).toBeLessThan(
        order.findIndex((name) => name.includes('offer-lift')),
      );
      expect(order.findIndex((name) => name.includes('offer-penalty'))).toBeLessThan(
        order.findIndex((name) => name.includes('offer-lift')),
      );
      // The lift takes what is left and truncates; the other two keep their width up to a cap.
      expect((fixture.nativeElement.querySelector('.offer-lift') as HTMLElement).classList).toContain('truncate');
      expect((fixture.nativeElement.querySelector('.offer-why') as HTMLElement).classList).toContain('min-w-0');
    });

    it('counts a disinterest topic as a penalty, not as a matched topic', async () => {
      const fixture = await render(false, {
        entry: withReasons([
          {factor: 'core_skill_overlap', label: 'skills', points: 30, maxPoints: 45},
          {factor: 'onsite_penalty', label: 'onsite', points: -5, maxPoints: 0},
          {factor: 'disinterest_fit', label: 'disinterest (judged): Unwanted topic', points: -15, maxPoints: 0, topic: 'Unwanted topic'},
        ]),
      });

      expect(text(fixture, '.offer-penalty')).toContain('Unwanted topic');
      // Named by its topic, not by the scorer's label: the glyph already says it held the score back.
      expect(text(fixture, '.offer-penalty')).not.toContain('disinterest');
      expect(fixture.nativeElement.querySelector('.offer-topics')).toBeNull();
      expect(text(fixture, '.offer-why')).not.toContain('onsite');
    });
  });

  describe('known facts only (ISC-375)', () => {
    function bare(): ShortlistEntry {
      const base = entry(2);
      return {
        ...base,
        offer: {...base.offer, rateEur: null, location: null, duration: null, remotePercent: null, agency: null},
        flags: {...base.flags, remoteUnknown: true},
      };
    }

    it('writes no placeholder for a value the advert never carried', async () => {
      const fixture = await render(false, {entry: bare()});

      const card = fixture.nativeElement.textContent as string;
      for (const placeholder of [
        'rate unknown',
        'location unknown',
        'duration unknown',
        'remote share unknown',
        'no agency stated',
      ]) {
        expect(card).not.toContain(placeholder);
      }
    });

    it('leaves no separator and no empty item behind', async () => {
      const fixture = await render(false, {entry: bare()});

      const facts = fixture.nativeElement.querySelector('.offer-facts') as HTMLElement;
      // Every item is a fact of its own; the gap between them is the separator.
      const items = Array.from(facts.children);
      expect(items.every((node) => node.classList.contains('offer-fact'))).toBe(true);
      expect(facts.textContent).not.toContain('·');
      // Only the came-in date is left.
      expect(items).toHaveLength(1);
      expect(items[0]!.querySelector('time')).not.toBeNull();
    });

    it('opens each known fact with its icon, and the rate with none', async () => {
      const base = entry(2);
      const fixture = await render(false, {
        entry: {
          ...base,
          offer: {
            ...base.offer,
            rateEur: 95,
            location: 'Köln',
            duration: '6 months',
            remotePercent: 80,
            startText: 'ab sofort',
            applyByText: 'bis Freitag',
          },
          flags: {...base.flags, remoteUnknown: false},
        },
      });

      const facts = fixture.nativeElement.querySelector('.offer-facts') as HTMLElement;
      const items = Array.from(facts.children) as HTMLElement[];
      // The came-in date first: it is one of the four questions a card answers, and the line
      // is one line, so it is the fact that must never be the one cut off at the end.
      expect(items.map((item) => icons(fixture, item))).toEqual([
        ['inbox'],
        [],
        ['map-pin'],
        ['clock'],
        ['house'],
        ['calendar-arrow-up'],
        ['calendar-clock'],
      ]);
      expect(allHidden(facts)).toBe(true);
    });

    it('shows a bare value where a word would be redundant, and keeps the word for screen readers', async () => {
      const base = entry(2);
      const fixture = await render(false, {
        entry: {
          ...base,
          offer: {...base.offer, remotePercent: 80, startText: 'ab sofort', applyByText: 'bis Freitag'},
          flags: {...base.flags, remoteUnknown: false},
        },
      });

      const facts = fixture.nativeElement.querySelector('.offer-facts') as HTMLElement;
      const visible = (item: Element): string =>
        Array.from(item.querySelectorAll('[aria-hidden="true"]:not(svg)'))
          .map((node) => node.textContent?.trim())
          .join(' ');
      const spoken = (item: Element): string => item.querySelector('.sr-only')?.textContent?.trim() ?? '';
      const [cameIn, , remote, start, deadline] = Array.from(facts.children);
      expect(visible(remote!)).toBe('80 %');
      expect(spoken(remote!)).toBe('80 % remote');
      expect(visible(start!)).toBe('ab sofort');
      expect(spoken(start!)).toBe('starts ab sofort');
      expect(visible(deadline!)).toBe('bis Freitag');
      expect(spoken(deadline!)).toBe('apply by bis Freitag');
      expect(spoken(cameIn!)).toBe('Came in');
    });

    it('treats a rate of 0 as no rate', async () => {
      const base = bare();
      const fixture = await render(false, {entry: {...base, offer: {...base.offer, rateEur: 0}}});

      expect(text(fixture, '.offer-facts')).not.toContain('€/h');
    });

    it('writes the known ones in order: rate, location, duration, remote share', async () => {
      const base = entry(2);
      const fixture = await render(false, {
        entry: {
          ...base,
          offer: {...base.offer, rateEur: 95, location: 'Köln', duration: '6 months', remotePercent: 80},
          flags: {...base.flags, remoteUnknown: false},
        },
      });

      const facts = text(fixture, '.offer-facts');
      const order = ['95 €/h', 'Köln', '6 months', '80 % remote'].map((part) => facts.indexOf(part));
      expect(order.every((position) => position >= 0)).toBe(true);
      expect([...order].sort((a, b) => a - b)).toEqual(order);
    });
  });

  describe('no badge, the status as text (ISC-374)', () => {
    function busy(): ShortlistEntry {
      const base = entry(2);
      return {
        ...base,
        offer: {...base.offer, remotePercent: 60},
        flags: {incomplete: true, remoteUnknown: false, possibleDuplicate: true},
        sources: [
          {portal: 'portal-a', agency: null, url: 'https://example.invalid/a'},
          {portal: 'portal-b', agency: 'Agency B', url: 'https://example.invalid/b'},
        ],
      };
    }

    it('renders no lg-badge at all', async () => {
      const fixture = await render(false, {entry: busy(), status: 'PACKAGED', pickable: true});

      expect(fixture.nativeElement.querySelector('lg-badge')).toBeNull();
    });

    it('puts the status in the right column of the title row, beside the checkbox', async () => {
      const fixture = await render(false, {entry: busy(), status: 'PACKAGED', pickable: true});

      const card = fixture.nativeElement.querySelector('.offer') as HTMLElement;
      const state = card.querySelector('.offer-state') as HTMLElement;
      expect(state.textContent?.trim()).toBe('Packaged');
      expect(state.parentElement).toBe(card);
      expect(state.nextElementSibling?.classList).toContain('pick');
    });

    it('opens the status with its icon, and keeps the word beside it', async () => {
      const fixture = await render(false, {entry: busy(), status: 'PACKAGED'});

      const state = fixture.nativeElement.querySelector('.offer-state') as HTMLElement;
      expect(icons(fixture, state)).toEqual(['package']);
      expect(allHidden(state)).toBe(true);
      expect(state.textContent?.trim()).toBe('Packaged');
    });

    it('has a registered icon for every status', () => {
      const statuses = Object.keys(STATUS_ICONS);
      expect(statuses).toHaveLength(11);
      for (const name of Object.values(STATUS_ICONS)) {
        expect(LG_ICONS).toHaveProperty([name]);
      }
    });

    it('writes no status column when there is no application', async () => {
      const fixture = await render(false, {entry: busy()});

      expect(fixture.nativeElement.querySelector('.offer-state')).toBeNull();
    });

    it('keeps the remote share, the source and the count as plain text', async () => {
      const fixture = await render(false, {entry: busy()});

      expect(text(fixture, '.offer-facts')).toContain('60 % remote');
      expect(text(fixture, '.offer-source')).toContain('portal-a');
      expect(text(fixture, '.offer-dupes')).toContain('1');
    });
  });

  describe('when it came in (ISC-377)', () => {
    beforeEach(() => {
      vi.useFakeTimers();
      vi.setSystemTime(new Date(2026, 8, 25, 12, 0, 0));
    });

    afterEach(() => {
      vi.useRealTimers();
    });

    const cameIn = new Date(2026, 8, 22, 9, 30).toISOString();

    function arrived(): ShortlistEntry {
      const base = entry(2);
      return {...base, offer: {...base.offer, ingestedAt: cameIn, publishedOn: '2026-08-14'}};
    }

    it('says it relative to today, in English', async () => {
      const fixture = await render(false, {entry: arrived()});

      const time = fixture.nativeElement.querySelector('.offer-facts time') as HTMLTimeElement;
      expect(time.textContent?.trim()).toBe('3 days ago');
      expect(time.getAttribute('datetime')).toBe(cameIn);
    });

    it('says it relative to today, in German', async () => {
      const fixture = await render(false, {entry: arrived()}, 'de');

      const time = fixture.nativeElement.querySelector('.offer-facts time') as HTMLTimeElement;
      expect(time.textContent?.trim()).toBe('vor 3 Tagen');
      expect(time.getAttribute('datetime')).toBe(cameIn);
    });

    it('leaves the published date to the detail', async () => {
      const fixture = await render(false, {entry: arrived()});

      expect(fixture.nativeElement.textContent).not.toContain('2026-08-14');
      expect(fixture.nativeElement.textContent).not.toContain('Aug 14');
    });
  });

  describe('where it is advertised (ISC-378)', () => {
    function threeWays(): ShortlistEntry {
      const base = entry(2);
      return {
        ...base,
        sources: [
          {portal: 'portal-a', agency: null, url: 'https://example.invalid/a'},
          {portal: 'portal-b', agency: 'Agency B', url: 'https://example.invalid/b'},
          {portal: 'portal-c', agency: 'Agency C', url: 'https://example.invalid/c'},
        ],
      };
    }

    it('shows the other sources as a count beside an icon, and the portal as the one word', async () => {
      const fixture = await render(false, {entry: threeWays()});

      const dupes = fixture.nativeElement.querySelector('.offer-dupes') as HTMLElement;
      expect(icons(fixture, dupes)).toEqual(['copy']);
      expect(allHidden(dupes)).toBe(true);
      expect(dupes.querySelector('span[aria-hidden="true"]')?.textContent?.trim()).toBe('2');
      expect(dupes.textContent).not.toContain('⧉');
      expect(dupes.querySelector('.sr-only')?.textContent).toContain('2');
      const word = fixture.nativeElement.querySelector('.offer-source-word') as HTMLElement;
      expect(word.textContent).toBe('portal-a');
      expect(icons(fixture, word)).toEqual(['building-2']);
    });

    it('names no other source on the card', async () => {
      const fixture = await render(false, {entry: threeWays()});

      const card = fixture.nativeElement.textContent as string;
      for (const name of ['portal-b', 'portal-c', 'Agency B', 'Agency C']) {
        expect(card).not.toContain(name);
      }
    });

    it('prefers the agency when there is one', async () => {
      const base = entry(2);
      const fixture = await render(false, {entry: {...base, offer: {...base.offer, agency: 'Agency A'}}});

      expect(text(fixture, '.offer-source-word')).toBe('Agency A');
      expect(fixture.nativeElement.querySelector('.offer-dupes')).toBeNull();
    });
  });

  describe('flags (ISC-379)', () => {
    function flagged(incomplete: boolean, possibleDuplicate: boolean): ShortlistEntry {
      const base = entry(2);
      return {...base, flags: {...base.flags, incomplete, possibleDuplicate}};
    }

    function count(haystack: string, needle: string): number {
      return haystack.split(needle).length - 1;
    }

    it('writes each set flag once, warning-toned, with a hidden icon', async () => {
      const fixture = await render(false, {entry: flagged(true, true)});

      const source = text(fixture, '.offer-source');
      expect(count(source, 'possible duplicate')).toBe(1);
      expect(count(source, 'incomplete')).toBe(1);
      const flags = fixture.nativeElement.querySelectorAll('.offer-flag') as NodeListOf<HTMLElement>;
      expect(flags).toHaveLength(2);
      flags.forEach((flag) => {
        expect(icons(fixture, flag)).toEqual(['triangle-alert']);
        expect(allHidden(flag)).toBe(true);
        expect(flag.textContent).not.toContain('⚠');
        expect(flag.querySelector('.sr-only')).not.toBeNull();
      });
      expect(fixture.nativeElement.querySelector('lg-badge')).toBeNull();
    });

    it('writes only the flag that is set', async () => {
      const fixture = await render(false, {entry: flagged(true, false)});

      expect(fixture.nativeElement.textContent).toContain('incomplete');
      expect(fixture.nativeElement.textContent).not.toContain('possible duplicate');
    });

    it('writes nothing when neither is set', async () => {
      const fixture = await render(false, {entry: flagged(false, false)});

      expect(fixture.nativeElement.querySelector('.offer-flag')).toBeNull();
      expect(fixture.nativeElement.textContent).not.toContain('possible duplicate');
      expect(fixture.nativeElement.textContent).not.toContain('incomplete');
    });
  });
  describe('compact (ISC-380)', () => {
    const reasons: ScoreReason[] = [
      {factor: 'core_skill_overlap', label: 'skills', points: 45, maxPoints: 45},
      {factor: 'interest_fit', label: 'interest: Wanted topic', points: 12, maxPoints: 0, topic: 'Wanted topic'},
      {factor: 'agency_penalty', label: 'agency chain', points: -20, maxPoints: 0},
    ];

    function busy(flags: Partial<ShortlistEntry['flags']> = {}): ShortlistEntry {
      const base = withReasons(reasons);
      return {
        ...base,
        offer: {...base.offer, remotePercent: 60},
        flags: {...base.flags, remoteUnknown: false, ...flags},
        sources: [
          {portal: 'portal-a', agency: null, url: 'https://example.invalid/a'},
          {portal: 'portal-b', agency: null, url: 'https://example.invalid/b'},
        ],
      };
    }

    it('is comfortable unless told otherwise', async () => {
      const fixture = await render(false, {entry: busy()});

      expect(fixture.nativeElement.querySelector('.offer.is-compact')).toBeNull();
      expect(fixture.nativeElement.querySelector('.offer-why')).not.toBeNull();
      expect(fixture.nativeElement.querySelector('.offer-source')).not.toBeNull();
    });

    it('renders two lines: the title row, then the facts with the lifting reason', async () => {
      const fixture = await render(false, {entry: busy(), density: 'compact', status: 'PACKAGED', pickable: true});

      const card = fixture.nativeElement.querySelector('.offer.is-compact') as HTMLElement;
      expect(card).not.toBeNull();
      const lines = Array.from(card.querySelector('.main')!.children) as HTMLElement[];
      expect(lines).toHaveLength(2);
      expect(lines[0]!.classList).toContain('title');
      expect(lines[1]!.classList).toContain('offer-brief');
      // The status and the checkbox stay on the title row, as columns of the card.
      expect(card.querySelector('.offer-state')?.parentElement).toBe(card);
      expect(card.querySelector('.pick')?.parentElement).toBe(card);

      const brief = lines[1]!.textContent ?? '';
      expect(brief).toContain('60 % remote');
      expect(brief).toContain('skills');
      expect(lines[1]!.querySelector('time[datetime]')).not.toBeNull();
    });

    it('keeps the title to one line', async () => {
      const fixture = await render(false, {entry: busy(), density: 'compact'});

      expect(fixture.nativeElement.querySelector('.title')!.classList).toContain('truncate');
    });

    it('drops the topics, the penalty and the source line', async () => {
      const fixture = await render(false, {entry: busy(), density: 'compact'});

      for (const gone of ['.offer-why', '.offer-topics', '.offer-penalty', '.offer-source']) {
        expect(fixture.nativeElement.querySelector(gone)).toBeNull();
      }
      expect(fixture.nativeElement.textContent).not.toContain('agency chain');
      expect(fixture.nativeElement.textContent).not.toContain('Wanted topic');
    });

    it('drops the "not scored" sentence too', async () => {
      const fixture = await render(false, {entry: withReasons([], null), density: 'compact'});

      expect(fixture.nativeElement.querySelector('.offer-not-scored')).toBeNull();
      expect(fixture.nativeElement.querySelector('.offer-brief')).not.toBeNull();
    });

    it('keeps the warning icon of a flagged card, hidden from assistive technology with its text beside it', async () => {
      const fixture = await render(false, {entry: busy({incomplete: true}), density: 'compact'});

      const flag = fixture.nativeElement.querySelector('.offer-brief .offer-flag') as HTMLElement;
      expect(flag).not.toBeNull();
      expect(icons(fixture, flag)).toEqual(['triangle-alert']);
      expect(allHidden(flag)).toBe(true);
      expect(flag.querySelector('.sr-only')?.textContent).toContain('incomplete');
    });

    it('marks the lift with the same icon as the comfortable card', async () => {
      const fixture = await render(false, {entry: busy(), density: 'compact'});

      const lift = fixture.nativeElement.querySelector('.offer-brief .offer-lift') as HTMLElement;
      expect(icons(fixture, lift)).toEqual(['trending-up']);
      expect(allHidden(lift)).toBe(true);
      expect(lift.textContent).not.toContain('▲');
    });

    it('writes no warning icon for an unflagged card', async () => {
      const fixture = await render(false, {entry: busy(), density: 'compact'});

      expect(fixture.nativeElement.querySelector('.offer-flag')).toBeNull();
    });

    it('shrinks the ring', async () => {
      const fixture = await render(false, {entry: busy()});
      const width = (): string =>
        (fixture.nativeElement.querySelector('lg-score .score') as HTMLElement).style.width;
      expect(width()).toBe('44px');

      fixture.componentRef.setInput('density', 'compact');
      fixture.detectChanges();
      expect(width()).toBe('36px');
    });
  });
});
