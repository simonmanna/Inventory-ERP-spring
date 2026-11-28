import { describe, expect, it } from 'vitest';
import type { SalesOrderLine } from './api';
import {
  changeDue, eventLabel, goodsLines, handoverBody, reverseBody, reverseLineProblem, serialList, serialsExpected, tenderBody,
} from './sale-cash-api';

// The field sets the API accepts (anything else is refused with "property … should not exist"):
// SalesCashSaleService.PAYMENT_FIELDS / HANDOVER_FIELDS and SalesCashReversalService.FIELDS / LINE_FIELDS.
const PAYMENT_FIELDS = ['method', 'provider', 'amount', 'amountTendered', 'reference', 'paymentModeId', 'paymentDate', 'payerName',
  'payerAccount', 'notes', 'attachmentIds'];
const HANDOVER_FIELDS = ['affirmed', 'receivedByName', 'receivedByPhone', 'signature', 'notes', 'lines'];
const HANDOVER_LINE_FIELDS = ['lineId', 'quantity', 'serialNumbers'];
const REVERSE_FIELDS = ['reason', 'returnReason', 'settlement', 'locationId', 'lines'];
const REVERSE_LINE_FIELDS = ['orderLineId', 'quantity', 'serialNumbers', 'condition', 'disposition', 'locationId', 'notes'];

const keysWithin = (o: object, allowed: string[]) => expect(Object.keys(o).filter((k) => !allowed.includes(k))).toEqual([]);

const line = (p: Partial<SalesOrderLine>): SalesOrderLine => ({
  id: 'l1', productId: 'p1', description: 'Cement', quantity: 2, unitPrice: 10, discountPercent: 0, taxId: null, ...p,
});

describe('cash sale request bodies (ADR-S094 C6)', () => {
  it('tender: a payment mode, the amount, and cash handed over only for cash', () => {
    const cash = tenderBody({ modeId: 'm1', method: 'cash', amount: 100, tendered: 120, reference: '  ', paymentDate: '2026-11-28',
      payerName: ' Ann ', payerAccount: '', notes: '' });
    expect(cash).toEqual({ paymentModeId: 'm1', amount: 100, amountTendered: 120, paymentDate: '2026-11-28', payerName: 'Ann' });
    keysWithin(cash, PAYMENT_FIELDS);

    const card = tenderBody({ modeId: 'm2', method: 'card', amount: 50.004, tendered: 80, reference: 'AUTH-1' });
    expect(card).toEqual({ paymentModeId: 'm2', amount: 50, reference: 'AUTH-1' });

    const momo = tenderBody({ method: 'mobile_money', provider: 'mtn_momo', amount: 30, reference: 'TX9', attachmentIds: ['d1'] });
    expect(momo).toEqual({ method: 'mobile_money', provider: 'mtn_momo', amount: 30, reference: 'TX9', attachmentIds: ['d1'] });
    keysWithin(momo, PAYMENT_FIELDS);
  });

  it('change is what was handed over above the amount, never negative', () => {
    expect(changeDue(100, 120)).toBe(20);
    expect(changeDue(100, 100)).toBe(0);
    expect(changeDue(100, 80)).toBe(0);
    expect(changeDue(99.99, 100)).toBe(0.01);
    expect(changeDue(100, null)).toBe(0);
  });

  it('handover: no lines unless one needs serial numbers; then every goods line, in full', () => {
    const plain = [line({}), line({ id: 's1', productId: null, description: 'Transport' })];
    const a = handoverBody({ affirmed: true, receivedByName: ' Bob ', receivedByPhone: '', notes: '', serials: {} }, plain);
    expect(a).toEqual({ affirmed: true, receivedByName: 'Bob' });
    keysWithin(a, HANDOVER_FIELDS);

    const tracked = [line({}), line({ id: 'l2', productId: 'p2', serialTracking: true }), line({ id: 's1', productId: null })];
    const b = handoverBody({ affirmed: true, receivedByName: 'Bob', receivedByPhone: '0700', notes: 'gate 2', serials: { l2: 'A1, A2;\nA3' } }, tracked);
    expect(b).toEqual({ affirmed: true, receivedByName: 'Bob', receivedByPhone: '0700', notes: 'gate 2',
      lines: [{ lineId: 'l1', serialNumbers: [] }, { lineId: 'l2', serialNumbers: ['A1', 'A2', 'A3'] }] });
    keysWithin(b, HANDOVER_FIELDS);
    b.lines!.forEach((l) => keysWithin(l, HANDOVER_LINE_FIELDS));
  });

  it('reverse: every goods line at its full quantity, with what was found', () => {
    const lines = [
      line({ id: 'l1', quantity: 5, qtyCancelled: 1 }),
      line({ id: 'l2', productId: 'p2', quantity: 1, serialTracking: true }),
      line({ id: 's1', productId: null, description: 'Transport' }),
    ];
    const body = reverseBody({
      reason: ' wrong product ', settlement: 'credit', returnReason: 'wrong_grade', locationId: '',
      lines: {
        l1: { condition: 'damaged', disposition: 'quarantine', locationId: 'q1', notes: ' cracked ' },
        l2: { serials: 'SN-9', disposition: 'restock', locationId: 'ignored' },
      },
    }, lines);
    expect(body).toEqual({
      reason: 'wrong product', settlement: 'credit', returnReason: 'wrong_grade',
      lines: [
        { orderLineId: 'l1', quantity: 4, condition: 'damaged', disposition: 'quarantine', locationId: 'q1', notes: 'cracked' },
        { orderLineId: 'l2', quantity: 1, serialNumbers: ['SN-9'], disposition: 'restock' },
      ],
    });
    keysWithin(body, REVERSE_FIELDS);
    body.lines.forEach((l) => keysWithin(l, REVERSE_LINE_FIELDS));
  });

  it('damaged or defective goods are not restocked', () => {
    expect(reverseLineProblem({ condition: 'damaged' })).not.toBeNull();
    expect(reverseLineProblem({ condition: 'defective', disposition: 'restock' })).not.toBeNull();
    expect(reverseLineProblem({ condition: 'damaged', disposition: 'scrap' })).toBeNull();
    expect(reverseLineProblem({ condition: 'usable' })).toBeNull();
    expect(reverseLineProblem({})).toBeNull();
  });

  it('serial numbers: parsed from any separator, one per base unit', () => {
    expect(serialList(' a,b ; c\nd ')).toEqual(['a', 'b', 'c', 'd']);
    expect(serialsExpected(line({ quantity: 2, uomFactor: 12 }))).toBe(24);
    expect(serialsExpected(line({ quantity: 1.5 }))).toBeNull();
    expect(goodsLines([line({ id: 'x', quantity: 1, qtyCancelled: 1 }), line({})]).map((l) => l.id)).toEqual(['l1']);
  });

  it('timeline actions read as words', () => {
    expect(eventLabel('payment_verified')).toBe('Payment verified');
    expect(eventLabel('fiscal_issue_succeeded')).toBe('Fiscal issue · registered');
    expect(eventLabel('fiscal_credit_note_failed')).toBe('Fiscal credit note · failed');
    expect(eventLabel('something_new')).toBe('something new');
  });
});
