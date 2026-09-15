import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {provideRouter} from '@angular/router';
import {SourceDetail} from '@core/model/source-detail';
import {SourcePanel} from './source-panel';

function detail(over: Partial<SourceDetail> = {}): SourceDetail {
  return {
    id: 'demo-newsletter',
    kind: 'file',
    enabled: true,
    file: {name: 'sources.yaml', layer: 'config-dir', origin: '/config/sources.yaml'},
    block: {text: '- id: demo-newsletter\n  type: file\n', firstLine: 21, lastLine: 22},
    connection: null,
    runs: [
      {ranOn: '2026-09-15', documents: 5, extracted: 169, written: 169, announced: 169, missing: 0},
      {ranOn: '2026-09-14', documents: 5, extracted: 157, written: 157, announced: 169, missing: 12},
      {ranOn: '2026-09-13', documents: 5, extracted: 157, written: 157, announced: 157, missing: 0},
    ],
    trend: {
      extractedChangedOn: '2026-09-15',
      extractedBefore: 157,
      extractedNow: 169,
      divergedOn: '2026-09-14',
      missing: 12,
      announcedStated: true,
    },
    ...over,
  };
}

describe('SourcePanel', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  /** Renders the panel for one source and hands it the answer the store would have. */
  function open(id: string, view: SourceDetail): ComponentFixture<SourcePanel> {
    const fixture = TestBed.createComponent(SourcePanel);
    fixture.componentRef.setInput('id', id);
    fixture.detectChanges();
    // The panel's own request, which the store makes on `sourceOpened`.
    http.expectOne((request) => request.url === `/api/sources/${id}`).flush(view);
    fixture.detectChanges();
    return fixture;
  }

  it('prints where the block is, so the excerpt can be checked against the file', () => {
    const fixture = open('demo-newsletter', detail());
    const text: string = fixture.nativeElement.textContent;

    expect(text).toContain('21');
    expect(text).toContain('22');
    expect(text).toContain('/config/sources.yaml');
  });

  it('folds a long block and unfolds it, and the unfolding does not follow to the next source', () => {
    // The unfolded state is an id and not a boolean: reused across sources, a boolean survives
    // the navigation and the next block opens unfolded for a decision nobody made about it.
    const long = detail({
      block: {text: Array.from({length: 40}, (_, line) => `  line ${line}`).join('\n'), firstLine: 1, lastLine: 40},
    });
    const fixture = open('demo-newsletter', long);
    const block = (): HTMLElement => fixture.nativeElement.querySelector('.yaml-text');

    expect(block().classList).toContain('folded');

    fixture.nativeElement.querySelector('button').click();
    fixture.detectChanges();
    expect(block().classList).not.toContain('folded');

    fixture.componentRef.setInput('id', 'other-source');
    fixture.detectChanges();
    http.expectOne((request) => request.url === '/api/sources/other-source')
      .flush(detail({...long, id: 'other-source'}));
    fixture.detectChanges();

    expect(block().classList).toContain('folded');
  });

  it('gives a run that moved a signed delta and leaves the rest muted', () => {
    // The sparkline's message, delivered by typography: a count identical to the run before it
    // is grey, one that changed carries ink and a sign — so the difference is never colour
    // alone. The judgement about *when* it changed is the server's; this is the arithmetic
    // between two adjacent rows of a list the server ordered.
    const fixture = open('demo-newsletter', detail());
    const deltas: HTMLElement[] = Array.from(fixture.nativeElement.querySelectorAll('.run-delta'));

    expect(deltas.map((delta) => delta.textContent?.trim())).toContain('+12');
    expect(fixture.nativeElement.querySelectorAll('.runs tbody tr')).toHaveLength(3);
  });

  it('marks a run where the announced count disagreed, with the badge the table already uses', () => {
    const fixture = open('demo-newsletter', detail());

    expect(fixture.nativeElement.querySelectorAll('.runs .diverged')).toHaveLength(1);
    expect(fixture.nativeElement.querySelector('.runs lg-badge')).not.toBeNull();
  });

  it('says a source is gone rather than showing an empty box', () => {
    // A real state and not an error: the file is hot-reloadable, so between the table load and
    // this click the source may have been taken out of it.
    const fixture = open('demo-newsletter', detail({block: null}));

    expect(fixture.nativeElement.querySelector('.yaml-text')).toBeNull();
    // The real catalog is provided in the specs, so this is the sentence and not the key —
    // which is also what would catch a key nobody translated.
    expect(fixture.nativeElement.textContent).toContain('no longer in sources.yaml');
  });

  afterEach(() => http.verify());
});
