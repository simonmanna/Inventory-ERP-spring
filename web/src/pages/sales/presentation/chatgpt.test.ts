import { describe, expect, it } from 'vitest';
import { deckPrompt, extractJson, parseChart, parseDeckReply, parseTable, sanitizeEdit, slidePrompt } from './chatgpt';
import { derive, mix, resolveLook, C } from './look';
import { mergeExtras, newCustomSlide, type ReportSlide } from './report';

const slide = (id: string, extra: Partial<ReportSlide> = {}): ReportSlide =>
  ({ id, kind: 'results', label: id, on: true, title: 'T', subtitle: '', heading: '', text: 'old', note: '', ...extra });

describe('extractJson', () => {
  it('reads a fenced block surrounded by prose, with smart quotes and trailing commas', () => {
    const reply = 'Sure! Here you go:\n```json\n{ “title”: “New”, "list": ["a", "b",], }\n```\nHope it helps.';
    expect(extractJson(reply)).toEqual({ title: 'New', list: ['a', 'b'] });
  });
  it('reads bare JSON', () => expect(extractJson('{"text":"x"}')).toEqual({ text: 'x' }));
  it('rejects replies without JSON', () => expect(() => extractJson('no idea')).toThrow('No JSON'));
});

describe('sanitizeEdit', () => {
  it('keeps only editable fields and normalises shapes', () => {
    const e = sanitizeEdit({
      id: 'x', kind: 'cover', images: ['data:evil'], title: 'Hello', on: false,
      table: { header: ['A', 'B'], rows: [['1'], ['2', '3', '4']], total: ['T'] },
      chart: { labels: ['a', 'b', 'c'], values: ['1,200.5', 2] },
      cards: [{ title: 'c', body: 5 }],
    }, slide('s', { chart: { title: 'keep', xTitle: 'x', yTitle: 'y', series: 'S', labels: [], values: [] } }));
    expect(e).toEqual({
      title: 'Hello',
      table: { header: ['A', 'B'], rows: [['1', ''], ['2', '3']], total: ['T', ''] },
      chart: { title: 'keep', xTitle: 'x', yTitle: 'y', series: 'S', labels: ['a', 'b'], values: [1200.5, 2] },
      cards: [{ title: 'c', body: '5' }],
    });
  });
  it('throws when nothing usable is present', () => expect(() => sanitizeEdit({ foo: 1 })).toThrow());
});

describe('prompts', () => {
  it('carry slide content but never images', () => {
    const p = slidePrompt(slide('a', { images: ['data:image/png;base64,AAAA'] }), 'shorter', 'ACME');
    expect(p).toContain('My request: shorter');
    expect(p).toContain('"text": "old"');
    expect(p).not.toContain('base64');
    expect(deckPrompt([slide('a'), slide('b')], '', 'ACME')).toContain('"id": "b"');
  });
});

describe('parseDeckReply', () => {
  it('maps edits by id, ignores unknown ids and chains new slides in order', () => {
    const slides = [slide('a'), slide('b')];
    const r = parseDeckReply(JSON.stringify({
      slides: [{ id: 'a', text: 'new' }, { id: 'zzz', text: 'x' }],
      newSlides: [{ after: 'a', layout: 'table', title: 'One' }, { layout: 'nope', title: 'Two' }],
    }), slides);
    expect(r.edits).toEqual({ a: { text: 'new' } });
    expect(r.skipped).toEqual(['zzz']);
    expect(r.added.map((s) => [s.title, s.layout, s.after])).toEqual([['One', 'table', 'a'], ['Two', 'bullets', r.added[0].id]]);
    expect(mergeExtras(slides, r.added).map((s) => s.title)).toEqual(['T', 'One', 'Two', 'T']);
  });
});

describe('pasted data', () => {
  it('parses Markdown tables from ChatGPT', () => {
    expect(parseTable('| Item | Value |\n|---|:---:|\n| **Tiles** | 10 |\n| Cement | 5 |'))
      .toEqual({ header: ['Item', 'Value'], rows: [['Tiles', '10'], ['Cement', '5']] });
  });
  it('parses Excel (tab) and CSV with quotes', () => {
    expect(parseTable('A\tB\n1\t2').rows).toEqual([['1', '2']]);
    expect(parseTable('Name,Note\n"Smith, J","said ""hi"""').rows).toEqual([['Smith, J', 'said "hi"']]);
  });
  it('reads chart bars with or without a header row', () => {
    expect(parseChart('Product\tRevenue\nTiles\t1,200\nCement\t800')).toEqual({ labels: ['Tiles', 'Cement'], values: [1200, 800] });
    expect(parseChart('Jan,5\nFeb,7')).toEqual({ labels: ['Jan', 'Feb'], values: [5, 7] });
  });
});

describe('mergeExtras', () => {
  it('places added slides after their anchor, at the end, and keeps orphans', () => {
    const base = [slide('a'), slide('b')];
    const x1 = { ...newCustomSlide('bullets', 'a'), title: 'x1' };
    const x2 = { ...newCustomSlide('bullets', ''), title: 'x2' };
    const x3 = { ...newCustomSlide('bullets', 'gone'), title: 'x3' };
    expect(mergeExtras(base, [x1, x2, x3]).map((s) => s.title === 'T' ? s.id : s.title)).toEqual(['a', 'x1', 'b', 'x2', 'x3']);
  });
});

describe('themes', () => {
  it('classic look is the reference palette, others derive every key', () => {
    expect(resolveLook().c).toBe(C);
    const p = derive('112233', '445566', 'AABBCC');
    expect(Object.keys(p).sort()).toEqual(Object.keys(C).sort());
    expect(p.navy).toBe('112233');
    expect(mix('000000', 'FFFFFF', 0.5)).toBe('808080');
    expect(resolveLook({ theme: 'brand', style: 'bold', font: 'Bogus', brand: ['zz', '445566', 'AABBCC'] }).font).toBe('Calibri');
  });
});
