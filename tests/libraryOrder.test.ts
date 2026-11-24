import { describe, it, expect } from 'vitest';
import { sortDocs, allTags, cleanTags } from '../src/libraryOrder';
import type { DocMeta } from '../src/types';

const doc = (id: string, o: Partial<DocMeta> = {}): DocMeta => ({
  id, title: id, kind: 'notebook', createdAt: 0, updatedAt: 0, pageIds: [], template: 'blank', paper: 'white', ...o,
});

describe('library order', () => {
  const docs = [
    doc('Kapitel 10', { updatedAt: 5, createdAt: 1, color: 'blue' }),
    doc('Kapitel 2', { updatedAt: 9, createdAt: 3 }),
    doc('Asthma', { updatedAt: 1, createdAt: 9, pinned: true, color: 'red' }),
  ];

  it('keeps pinned documents first in every order', () => {
    for (const m of ['edited', 'created', 'name', 'color'] as const) expect(sortDocs(docs, m)[0].id).toBe('Asthma');
  });

  it('sorts names naturally (2 before 10)', () => {
    expect(sortDocs(docs, 'name').map((d) => d.id)).toEqual(['Asthma', 'Kapitel 2', 'Kapitel 10']);
  });

  it('sorts by last edit, creation and colour', () => {
    expect(sortDocs(docs, 'edited').map((d) => d.id)).toEqual(['Asthma', 'Kapitel 2', 'Kapitel 10']);
    expect(sortDocs(docs, 'created').map((d) => d.id)).toEqual(['Asthma', 'Kapitel 2', 'Kapitel 10']);
    expect(sortDocs(docs, 'color').map((d) => d.id)).toEqual(['Asthma', 'Kapitel 10', 'Kapitel 2']);
  });

  it('lists tags by use and cleans typed tags', () => {
    expect(allTags([doc('a', { tags: ['Exam', 'COPD'] }), doc('b', { tags: ['COPD'] })])).toEqual(['COPD', 'Exam']);
    expect(cleanTags([' #Asthma ', 'asthma', '', 'الربو  المزمن'])).toEqual(['Asthma', 'الربو المزمن']);
  });
});
