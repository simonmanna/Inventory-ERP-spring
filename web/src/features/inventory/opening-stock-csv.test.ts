import { describe, expect, it } from 'vitest';
import {
  errorsByRow, OPENING_STOCK_TEMPLATE, parseCsvText, parseOpeningStockCsv, runStatusLabel,
} from './opening-stock-csv';

describe('parseCsvText', () => {
  it('handles quotes, escaped quotes and semicolons', () => {
    expect(parseCsvText('a;b\n"x;1";"say ""hi"""\n')).toEqual([['a', 'b'], ['x;1', 'say "hi"']]);
  });

  it('skips blank lines and a BOM', () => {
    expect(parseCsvText('﻿a,b\r\n\r\n1,2')).toEqual([['a', 'b'], ['1', '2']]);
  });
});

describe('parseOpeningStockCsv (IPR R5)', () => {
  it('maps the template columns to preview rows with spreadsheet line numbers', () => {
    const { rows, errors } = parseOpeningStockCsv(OPENING_STOCK_TEMPLATE);
    expect(errors).toEqual([]);
    expect(rows).toHaveLength(3);
    expect(rows[0]).toEqual({
      rowNo: 2, productCode: 'SKU-001', variantSku: null, locationCode: 'MAIN', quantity: '10', unitCost: '2.50',
      batchNumber: null, expiryDate: null, serialNumbers: [],
    });
    expect(rows[1].batchNumber).toBe('LOT-A');
    expect(rows[1].expiryDate).toBe('2027-12-31');
    expect(rows[2].serialNumbers).toEqual(['SN-1', 'SN-2']);
  });

  it('accepts header aliases in any order and case', () => {
    const { rows } = parseOpeningStockCsv('Location Code,QTY,Product Code,Unit Cost,Serial Numbers\nW1,2,P1,,a;b\n');
    expect(rows[0]).toMatchObject({ locationCode: 'W1', quantity: '2', productCode: 'P1', unitCost: null,
      serialNumbers: ['a', 'b'] });
  });

  it('reports missing required columns and empty files', () => {
    expect(parseOpeningStockCsv('product,quantity\nP,1').errors).toEqual(['Missing column(s): location']);
    expect(parseOpeningStockCsv('').errors).toEqual(['The file is empty']);
    expect(parseOpeningStockCsv('product,location,quantity\n').errors).toEqual(['The file has a header but no rows']);
  });

  it('refuses more than 20,000 rows', () => {
    const body = Array.from({ length: 20001 }, (_, i) => `P,L,${i + 1}`).join('\n');
    expect(parseOpeningStockCsv(`product,location,quantity\n${body}`).errors[0]).toContain('at most 20000 rows');
  });
});

describe('preview helpers', () => {
  it('groups row errors by row number', () => {
    const m = errorsByRow([{ rowNo: 2, message: 'a' }, { rowNo: 3, message: 'b' }, { rowNo: 2, message: 'c' }]);
    expect(m.get(2)).toEqual(['a', 'c']);
    expect(m.get(3)).toEqual(['b']);
  });

  it('labels run states', () => {
    expect(runStatusLabel('completed').tone).toBe('ok');
    expect(runStatusLabel('partial').tone).toBe('warn');
    expect(runStatusLabel('failed').tone).toBe('bad');
    expect(runStatusLabel('posting').tone).toBe('info');
  });
});
