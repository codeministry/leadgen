import { TestBed } from '@angular/core/testing';
import { Markdown } from './markdown';

describe('Markdown', () => {
  function render(text: string): string {
    const fixture = TestBed.createComponent(Markdown);
    fixture.componentRef.setInput('text', text);
    fixture.detectChanges();
    return (fixture.nativeElement as HTMLElement).innerHTML;
  }

  it('turns an ad into headings and a list, which is the whole point', () => {
    const html = render('## Ihre Aufgaben\n\n- Spring Boot\n- Kubernetes');

    expect(html).toContain('<h2');
    expect(html).toContain('<li>Spring Boot</li>');
  });

  it('keeps a single newline as a line break, because an ad writes one per requirement', () => {
    expect(render('Start: sofort\nDauer: 6 Monate')).toContain('<br');
  });

  it('highlights a fenced block in a language it knows', () => {
    const html = render('```java\nrecord Offer(String title) {}\n```');

    expect(html).toContain('hljs-keyword');
  });

  it('opens a link out of the ad in a new tab, with the opener closed off', () => {
    // The shortlist is a working list: following a link out of it must not cost the place
    // in it. And a page opened this way can reach back through window.opener unless rel
    // says otherwise — this markup came off a portal.
    const html = render('Siehe [die Anzeige](https://example.invalid/job).');

    expect(html).toContain('target="_blank"');
    expect(html).toContain('rel="noopener noreferrer"');
    expect(html).toContain('href="https://example.invalid/job"');
  });

  it('drops a script the ad brought with it', () => {
    // Angular's sanitizer on [innerHTML] is the security model here, and this is the test
    // that fails the day somebody reaches for bypassSecurityTrustHtml.
    const html = render('Hallo <script>alert(1)</script> Welt');

    expect(html).not.toContain('<script');
  });

  it('renders nothing at all for an offer with no text', () => {
    expect(render('')).not.toContain('<p');
  });
});
