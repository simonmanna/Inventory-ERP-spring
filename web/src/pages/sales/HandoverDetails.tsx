import { tx } from '@/lib/i18n/tx';
import type { PlanMethod } from '@/features/sales/delivery-api';
import type { HandoverForm } from './handover-draft';
import { HandoverDeliveryView } from './delivery/HandoverDeliveryView';

/**
 * Delivery & fulfilment on a new (unsaved) invoice or branch cash sale: plan the handover with the same layout as a
 * saved document (method cards, address / site, location & site details, transport, notes); it is saved with the
 * document. {@code methods} / {@code hide} limit it to what the document stores; {@code hideLocation} hides the stock
 * location picker when the document's own location is used (cash sale).
 */
export function HandoverDraftPanel({ value, onChange, hint, hideLocation, methods, hide = [] }: {
  value: HandoverForm; onChange: (f: HandoverForm) => void; hint: string; hideLocation?: boolean;
  methods?: PlanMethod[]; hide?: Array<keyof HandoverForm>;
}) {
  return (
    <HandoverDeliveryView draft form={value} onChange={onChange} status="pending" methods={methods}
      hide={hideLocation ? [...hide, 'locationId'] : hide} planLocked={false} receiptLocked={false} notice={hint}
      stockLocationName={hideLocation ? tx('Stock location of the sale') : undefined} />
  );
}
