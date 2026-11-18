import { describe, it, expect } from 'vitest';
import { normQuery, findSnippets } from '../src/searchText';

describe('search text', () => {
  it('finds latin text ignoring case and accents', () => {
    const r = findSnippets('Die Lungenfunktion wird mit der Spirometrie gemessen. Spirometrie ist einfach.', normQuery('SPIROMETRIE'));
    expect(r.count).toBe(2);
    expect(r.snippets[0].match).toBe('Spirometrie');
    expect(findSnippets('Über die Atemwege', normQuery('uber')).count).toBe(1);
  });

  it('matches Arabic without diacritics and with any alef form', () => {
    const text = 'يُعَدُّ الربو مرضًا مزمنًا، وأعراض الربو تشمل السعال';
    expect(findSnippets(text, normQuery('الرَّبو')).count).toBe(2);
    expect(findSnippets(text, normQuery('اعراض')).snippets[0].match).toBe('أعراض');
    expect(findSnippets('المدرسة', normQuery('المدرسه')).count).toBe(1);
  });

  it('keeps context around a hit and marks cuts', () => {
    const long = 'word '.repeat(40) + 'COPD exacerbation' + ' tail'.repeat(40);
    const s = findSnippets(long, normQuery('copd'), 1, 20).snippets[0];
    expect(s.before.startsWith('…')).toBe(true);
    expect(s.after.endsWith('…')).toBe(true);
    expect(s.match).toBe('COPD');
  });

  it('collapses whitespace so line breaks do not hide phrases', () => {
    expect(findSnippets('chronic\n  obstructive', normQuery('chronic obstructive')).count).toBe(1);
  });
});
