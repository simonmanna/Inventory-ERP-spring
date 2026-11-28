import { describe, expect, it } from 'vitest';
import { csvCell } from './export-csv';

describe('csvCell (IPR S-8)', () => {
  it('keeps negative and signed numbers as numbers', () => {
    expect(csvCell('-3')).toBe('"-3"');
    expect(csvCell('-12.50')).toBe('"-12.50"');
    expect(csvCell(-4)).toBe('"-4"');
    expect(csvCell('+7')).toBe('"+7"');
    expect(csvCell('-1.5e3')).toBe('"-1.5e3"');
  });

  it('still neutralises formula injection in text', () => {
    expect(csvCell('=SUM(A1:A9)')).toBe(`"'=SUM(A1:A9)"`);
    expect(csvCell('-2+3')).toBe(`"'-2+3"`);
    expect(csvCell('@cmd')).toBe(`"'@cmd"`);
    expect(csvCell('+1 555 0100')).toBe(`"'+1 555 0100"`);
    expect(csvCell('\tx')).toBe(`"'\tx"`);
  });

  it('quotes embedded double quotes and treats null as empty', () => {
    expect(csvCell('a "b"')).toBe('"a ""b"""');
    expect(csvCell(null)).toBe('""');
  });
});
