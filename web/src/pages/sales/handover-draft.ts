import { api } from '@/lib/api';

/** Editable handover (delivery & fulfilment) fields, shared by the live tab and the new-document draft. */
export type HandoverForm = {
  method: 'pickup' | 'delivery'; locationId: string; address: string; contactName: string; contactPhone: string;
  scheduledDate: string; driver: string; vehicle: string; receivedBy: string; notes: string;
};

export const emptyHandover = (method: HandoverForm['method'] = 'pickup'): HandoverForm => ({
  method, locationId: '', address: '', contactName: '', contactPhone: '', scheduledDate: '', driver: '', vehicle: '',
  receivedBy: '', notes: '',
});

/**
 * Save the delivery details captured on a new order / invoice once the document exists. Only filled fields are sent,
 * so the server keeps its defaults (customer name / phone) for the rest. Status stays pending.
 */
export async function saveHandoverDraft(path: string, f: HandoverForm) {
  const body: Record<string, unknown> = { method: f.method };
  (Object.keys(f) as Array<keyof HandoverForm>).forEach((k) => {
    if (k === 'method' || !f[k]) return;
    if (f.method === 'pickup' && (k === 'driver' || k === 'vehicle' || k === 'address')) return;
    body[k] = f[k];
  });
  await api.patch(path, body);
}
