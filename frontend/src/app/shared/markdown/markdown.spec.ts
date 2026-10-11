import {TestBed} from '@angular/core/testing';
import {Markdown} from './markdown';

describe('Markdown', () => {
    function render(text: string): string {
        const fixture = TestBed.createComponent(Markdown);
        fixture.componentRef.setInput('text', text);
        fixture.detectChanges();
        return (fixture.nativeElement as HTMLElement).innerHTML;
    }

    it('turns an ad into headings and a list, which is the whole point', () => {
        const html = render('## Ihre Aufgaben\n\n- Spring Boot\n- Kubernetes');

        expect(html).toContain('<h4');
        expect(html).toContain('<li>Spring Boot</li>');
    });

    it('pushes the advert\'s own headings below the panel that holds them', () => {
        // The text is somebody else's and `#` renders an `<h1>`, so an ad that opens with its
        // own title claimed to be the page's heading beside the screen's real one.
        expect(render('# Senior Java Entwickler')).toContain('<h3');
        expect(render('# Senior Java Entwickler')).not.toContain('<h1');
    });

    it('stops at the deepest level HTML has', () => {
        expect(render('##### Nice to have')).toContain('<h6');
        expect(render('###### Nice to have')).toContain('<h6');
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

    it('keeps a quote in a link address or title inside its attribute (CodeQL #10, #11)', () => {
        // The override drops what Marked's own link renderer does first, so the escaping is ours.
        const parse = (text: string) => new DOMParser().parseFromString(render(text), 'text/html').querySelector('a')!;

        const address = parse('[x](<https://example.invalid/a" onmouseover="alert(1)>)');
        expect(address.getAttributeNames().sort()).toEqual(['href', 'rel', 'target']);
        expect(address.getAttribute('href')).toBe('https://example.invalid/a%22%20onmouseover=%22alert(1)');

        const titled = parse(`[x](https://example.invalid 't" onclick="alert(2)')`);
        expect(titled.getAttributeNames().sort()).toEqual(['href', 'rel', 'target', 'title']);
        expect(titled.getAttribute('title')).toBe('t" onclick="alert(2)');
    });

    it('renders a link that would run code as its label alone', () => {
        const html = render('[klick](javascript:alert(1)) und [daten](data:text/html,x) und [datei](file:///etc/passwd)');

        expect(html).not.toContain('<a');
        expect(html).toContain('klick');
        expect(html).toContain('daten');
        expect(html).toContain('datei');
    });

    it('renders the label of a link as Markdown, not as raw markup', () => {
        const html = render('[**fett**](https://example.invalid)');

        expect(html).toContain('<strong>fett</strong>');
    });

    it('renders nothing at all for an offer with no text', () => {
        expect(render('')).not.toContain('<p');
    });
});
