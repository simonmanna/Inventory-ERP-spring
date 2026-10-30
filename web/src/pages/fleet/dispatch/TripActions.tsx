import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Ban, ClipboardCheck, Flag, Gauge, PackageOpen, Printer, Scale, Send, Truck, Undo2, UserCheck } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import {
  REASON_LABEL, openDeliveryNote, useCancelTrip, useCloseTrip, useDepartTrip, useDispatchTrip, useLoadedQuantity,
  toNum, useReturnTrip, useStartLoading, type TripDetail,
} from '@/features/fleet/dispatch-api';
import { Field } from '../fleet-ui';
import { num, toOptions } from '../fleet-format';
import { AssignDialog } from './AssignDialog';
import { OdometerDialog, ReasonDialog } from './dispatch-ui';

type Open = 'loading' | 'loaded' | 'dispatch' | 'close' | 'cancel' | 'return' | 'assign' | null;

/**
 * State actions of a trip (fleet 1.3), shown by status and permission: assign, start loading, loaded quantity, dispatch
 * (stock out), depart, proof of delivery, return, close, cancel, delivery-note print. `big` = phone-sized buttons.
 */
export function TripActions({ trip, big = false, podPath }: { trip: TripDetail; big?: boolean; podPath: string }) {
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const [open, setOpen] = useState<Open>(null);
  const startLoading = useStartLoading();
  const dispatch = useDispatchTrip();
  const depart = useDepartTrip();
  const close = useCloseTrip();
  const cancel = useCancelTrip();
  const ret = useReturnTrip();
  const s = trip.status;
  const isOpen = ['planned', 'loading', 'weighed'].includes(s);
  const onRoad = ['dispatched', 'in_transit'].includes(s);
  const wb = trip.weighbridge;
  const weighRequired = !!wb?.required;
  const size = big ? 'lg' : 'default';
  const cls = big ? 'h-12 w-full text-base sm:w-auto' : '';

  const run = async (fn: () => Promise<unknown>, ok: string, fail: string) => {
    try {
      await fn();
      notify.success(ok);
      setOpen(null);
    } catch (err) {
      notify.error(apiMessage(err, fail));
    }
  };

  const print = async () => {
    try {
      await openDeliveryNote(trip.id);
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not open the delivery note')));
    }
  };

  return (
    <>
      <div className={big ? 'grid grid-cols-1 gap-2 sm:flex sm:flex-wrap' : 'flex flex-wrap gap-2'}>
        {isOpen && has(PERMISSIONS.fleetDispatch.plan) && (
          <Button size={size} className={cls} variant="outline" onClick={() => setOpen('assign')}><UserCheck className="mr-1.5 h-4 w-4" /> {tx('Assign')}</Button>
        )}
        {s === 'planned' && has(PERMISSIONS.fleetTrip.dispatch) && (
          <Button size={size} className={cls} variant="outline" onClick={() => setOpen('loading')}><PackageOpen className="mr-1.5 h-4 w-4" /> {tx('Start loading')}</Button>
        )}
        {isOpen && has(PERMISSIONS.weighbridge.weigh) && (
          wb?.liveTicket ? (
            <Button size={size} className={cls} variant="outline" onClick={() => navigate(`/fleet/weighbridge/tickets/${wb.liveTicket!.id}`)}>
              <Gauge className="mr-1.5 h-4 w-4" /> {tx('Continue weighing {{number}}', { number: wb.liveTicket.ticketNumber })}
            </Button>
          ) : (
            <Button size={size} className={cls} variant={weighRequired ? 'default' : 'outline'} onClick={() => navigate(`/fleet/weighbridge/tickets/new?tripId=${trip.id}`)}>
              <Gauge className="mr-1.5 h-4 w-4" /> {tx('Weigh')}
            </Button>
          )
        )}
        {isOpen && !weighRequired && has(PERMISSIONS.fleetTrip.dispatch) && (
          <Button size={size} className={cls} variant="outline" onClick={() => setOpen('loaded')}><Scale className="mr-1.5 h-4 w-4" /> {tx('Loaded quantity')}</Button>
        )}
        {isOpen && has(PERMISSIONS.fleetTrip.dispatch) && (
          <Button size={size} className={cls} onClick={() => setOpen('dispatch')}><Send className="mr-1.5 h-4 w-4" /> {tx('Dispatch')}</Button>
        )}
        {s === 'dispatched' && has(PERMISSIONS.fleetTrip.dispatch) && (
          <Button size={size} className={cls} variant="outline" disabled={depart.isPending}
            onClick={() => run(() => depart.mutateAsync(trip.id), tx('Trip is in transit'), tx('Could not update the trip'))}>
            <Truck className="mr-1.5 h-4 w-4" /> {tx('Left the yard')}
          </Button>
        )}
        {onRoad && has(PERMISSIONS.fleetTrip.deliver) && (
          <Button size={size} className={cls} onClick={() => navigate(podPath)}><ClipboardCheck className="mr-1.5 h-4 w-4" /> {tx('Proof of delivery')}</Button>
        )}
        {onRoad && has(PERMISSIONS.fleetTrip.close) && (
          <Button size={size} className={cls} variant="outline" onClick={() => setOpen('return')}><Undo2 className="mr-1.5 h-4 w-4" /> {tx('Load returned')}</Button>
        )}
        {s === 'delivered' && has(PERMISSIONS.fleetTrip.close) && (
          <Button size={size} className={cls} onClick={() => setOpen('close')}><Flag className="mr-1.5 h-4 w-4" /> {tx('Close trip')}</Button>
        )}
        {trip.deliveryNoteNumber && (
          <Button size={size} className={cls} variant="outline" onClick={print}><Printer className="mr-1.5 h-4 w-4" /> {tx('Delivery note')}</Button>
        )}
        {isOpen && has(PERMISSIONS.fleetTrip.cancel) && (
          <Button size={size} className={cls} variant="ghost" onClick={() => setOpen('cancel')}><Ban className="mr-1.5 h-4 w-4" /> {tx('Cancel trip')}</Button>
        )}
      </div>

      <AssignDialog trip={open === 'assign' ? trip : null} onClose={() => setOpen(null)} />
      <OdometerDialog open={open === 'loading'} title={tx('Start loading {{code}}', { code: trip.code })} confirmLabel={tx('Start loading')}
        busy={startLoading.isPending} onClose={() => setOpen(null)}
        onConfirm={(odometer) => run(() => startLoading.mutateAsync({ id: trip.id, odometer: odometer || undefined }), tx('Loading started'), tx('Could not update the trip'))} />
      <OdometerDialog open={open === 'dispatch'} title={tx('Dispatch {{code}}: the goods leave the yard', { code: trip.code })} confirmLabel={tx('Dispatch')}
        busy={dispatch.isPending} onClose={() => setOpen(null)}
        onConfirm={(odometer) => run(() => dispatch.mutateAsync({ id: trip.id, odometer: odometer || undefined }), tx('Trip dispatched'), tx('Could not dispatch the trip'))} />
      <OdometerDialog open={open === 'close'} withDistance title={tx('Close trip {{code}}', { code: trip.code })} confirmLabel={tx('Close trip')}
        busy={close.isPending} onClose={() => setOpen(null)}
        onConfirm={(odometer, km) => run(() => close.mutateAsync({ id: trip.id, body: { endOdometer: toNum(odometer), distanceKm: toNum(km) } }), tx('Trip closed'), tx('Could not close the trip'))} />
      <ReasonDialog open={open === 'cancel'} title={tx('Cancel trip {{code}}', { code: trip.code })} confirmLabel={tx('Cancel trip')}
        busy={cancel.isPending} onClose={() => setOpen(null)}
        onConfirm={(reason) => run(() => cancel.mutateAsync({ id: trip.id, reason }), tx('Trip cancelled'), tx('Could not cancel the trip'))} />
      <ReasonDialog open={open === 'return'} title={tx('Whole load returned to the yard')} confirmLabel={tx('Record return')}
        description={tx('All goods go back into stock and a discrepancy is raised for approval.')}
        options={toOptions(REASON_LABEL)} busy={ret.isPending} onClose={() => setOpen(null)}
        onConfirm={(reason, code) => run(() => ret.mutateAsync({ id: trip.id, reason, reasonCode: code || undefined }), tx('Return recorded'), tx('Could not record the return'))} />
      {open === 'loaded' && <LoadedQuantityDialog trip={trip} onClose={() => setOpen(null)} />}
    </>
  );
}

/** Manual loaded quantity per product (when no weighbridge ticket is used). */
function LoadedQuantityDialog({ trip, onClose }: { trip: TripDetail; onClose: () => void }) {
  const save = useLoadedQuantity();
  const [qty, setQty] = useState<Record<string, string>>(() =>
    Object.fromEntries(trip.lines.map((l) => [l.id, l.loadedQty ?? l.plannedQty])));
  const [batch, setBatch] = useState<Record<string, string>>(() =>
    Object.fromEntries(trip.lines.map((l) => [l.id, l.batchNumber ?? ''])));
  const invalid = trip.lines.some((l) => qty[l.id] === '' || Number(qty[l.id]) < 0);
  const submit = async () => {
    try {
      await save.mutateAsync({ id: trip.id, lines: trip.lines.map((l) => ({ loadLineId: l.id, quantity: qty[l.id], batchNumber: batch[l.id] || undefined })) });
      notify.success(tx('Loaded quantity saved'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the loaded quantity')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent>
        <DialogHeader><DialogTitle>{tx('Loaded quantity of {{code}}', { code: trip.code })}</DialogTitle></DialogHeader>
        <div className="space-y-3">
          {trip.lines.map((l) => (
            <div key={l.id} className="grid grid-cols-3 gap-2">
              <Field label={`${l.productName} (${l.uomCode ?? ''}) · ${tx('planned')} ${num(l.plannedQty, 3)}`} className="col-span-2">
                <Input type="number" inputMode="decimal" min={0} step="0.001" className="h-12 text-lg" value={qty[l.id]} onChange={(e) => setQty({ ...qty, [l.id]: e.target.value })} />
              </Field>
              <Field label={tx('Batch')}>
                <Input className="h-12" value={batch[l.id]} onChange={(e) => setBatch({ ...batch, [l.id]: e.target.value })} />
              </Field>
            </div>
          ))}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={invalid || save.isPending} onClick={submit}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
