import { api } from '@/lib/api';

/** Editable handover (delivery & fulfilment) fields, shared by the live tab and the new-document draft. */
export type HandoverForm = {
  method: 'pickup' | 'delivery' | 'transfer' | 'courier'; locationId: string; address: string; contactName: string; contactPhone: string;
  scheduledDate: string; driver: string; vehicle: string; receivedBy: string; notes: string;
  /** Site details of a delivery: GPS pin / landmark, loading site, full or partial delivery, offloading equipment. */
  landmark: string; loadingSite: string; deliveryScope: '' | 'full' | 'partial'; offloadingEquipment: '' | 'yes' | 'no';
  /** Sales orders only: GPS pin, offloading point, destination warehouse (transfer) and courier company. */
  gpsCoordinates?: string; offloadingSite?: string; destinationLocationId?: string; courierName?: string;
  /** Date the goods must reach the customer by (yyyy-MM-dd). */
  deadline?: string;
};

/** Delivery-only fields: dropped when the customer collects. */
export const DELIVERY_ONLY: Array<keyof HandoverForm> = ['driver', 'vehicle', 'address', 'landmark', 'offloadingEquipment'];

export const emptyHandover = (method: HandoverForm['method'] = 'pickup'): HandoverForm => ({
  method, locationId: '', address: '', contactName: '', contactPhone: '', scheduledDate: '', driver: '', vehicle: '',
  receivedBy: '', notes: '', landmark: '', loadingSite: '', deliveryScope: '', offloadingEquipment: '',
});

/**
 * Save the delivery details captured on a new order / invoice once the document exists. Only filled fields are sent,
 * so the server keeps its defaults (customer name / phone) for the rest. Status stays pending.
 */
export async function saveHandoverDraft(path: string, f: HandoverForm) {
  const body: Record<string, unknown> = { method: f.method };
  (Object.keys(f) as Array<keyof HandoverForm>).forEach((k) => {
    if (k === 'method' || !f[k]) return;
    if (f.method === 'pickup' && DELIVERY_ONLY.includes(k)) return;
    body[k] = f[k];
  });
  await api.patch(path, body);
}
