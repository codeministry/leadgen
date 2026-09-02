import { describe, expect, it } from 'vitest';
import { plainText } from './plain-text';

describe('plainText', () => {
  it('keeps the words of a heading and the list under it, and nothing else', () => {
    // What the shortlist card was actually printing: the whole ad on one line, syntax
    // included, cut off mid-list.
    const markdown =
      '### Ihre Aufgaben\n\n- Betrieb der Services\n- **Entwurf** der REST-Schnittstellen';

    expect(plainText(markdown)).toBe(
      'Ihre Aufgaben Betrieb der Services Entwurf der REST-Schnittstellen',
    );
  });

  it('drops a link target and keeps its text', () => {
    expect(plainText('Details im [Projektportal](https://portal-a.example/x).')).toBe(
      'Details im Projektportal.',
    );
  });

  it('leaves an unbalanced mark from a truncated document behind', () => {
    // A teaser is cut mid-document, so the input is routinely unbalanced. A parser would
    // render half a list; this has to degrade to a readable sentence.
    expect(plainText('Gesucht wird ein **Senior Entwickler mit Erfahrung')).toBe(
      'Gesucht wird ein Senior Entwickler mit Erfahrung',
    );
  });

  it('answers empty for nothing at all', () => {
    expect(plainText(null)).toBe('');
    expect(plainText(undefined)).toBe('');
    expect(plainText('   ')).toBe('');
  });
});
