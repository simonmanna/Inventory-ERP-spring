/**
 * ADR-S083 O4 — the warehouse tablet: receive against a purchase order, dispatch and receive transfers, adjust and
 * count stock, with or without a connection. A barcode scanner types into the scan box (keyboard wedge + Enter);
 * where the browser supports it, the camera scans too. Work is recorded as sync ops on this registered device and
 * the server applies the S4 rules when it syncs (held postings, negative-stock flags, receipts waiting for their
 * dispatch). Everything shown comes from the synced scopes, so the screen works offline.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { Camera, Loader2, ScanLine, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { tx } from '@/lib/i18n/tx';
import { useAuthStore } from '@/stores/auth.store';
import { syncEngineFor } from '@/lib/offline/web';
import { offlineWindow } from '@/lib/offline/session';
import { DEFAULT_POLICY, type DeviceInfo, type OfflinePolicy, type SyncOp } from '@/lib/offline/types';
import {
  addScan, adjustOp, countOp, describe, dispatchOp, grnOp, openOnLine, outstanding, receiveOp, resolveScan,
  WAREHOUSE_OP_TYPES, type OpDraft, type PoLine, type Scopes, type TransferItem, type WorkLine,
} from '@/features/warehouse/offline-stock';

type Row = Record<string, unknown>;
interface Location { id: string; code: string; name: string; branchId?: string | null }
interface Transfer { id: string; transferCode: string; status: string; fromLocId: string; toLocId: string; items: TransferItem[] }
interface PurchaseOrder { id: string; orderNumber: string; partnerName?: string; status: string; lines: PoLine[] }

const newId = () => crypto.randomUUID();

function useWarehouseData(org: string | undefined) {
  const [device, setDevice] = useState<DeviceInfo | null | undefined>(undefined);
  const [scopes, setScopes] = useState<Scopes>({ products: [], productVariants: [], productPackagings: [], lots: [], serials: [] });
  const [locations, setLocations] = useState<Location[]>([]);
  const [transfers, setTransfers] = useState<Transfer[]>([]);
  const [orders, setOrders] = useState<PurchaseOrder[]>([]);
  const [ops, setOps] = useState<SyncOp[]>([]);
  const [lastSyncAt, setLastSyncAt] = useState<number | null>(null);

  const load = useCallback(async () => {
    if (!org) return;
    const engine = syncEngineFor(org);
    const d = await engine.device().catch(() => undefined);
    setDevice(d ?? null);
    if (!d) return;
    const st = engine.store;
    const [products, productVariants, productPackagings, lots, serials, locs, trs, pos, all, synced] = await Promise.all([
      st.getRows<Row>('products'), st.getRows<Row>('productVariants'), st.getRows<Row>('productPackagings'),
      st.getRows<Row>('lots'), st.getRows<Row>('serials'), st.getRows<Location>('stockLocations'),
      st.getRows<Transfer>('transfers'), st.getRows<PurchaseOrder>('openPurchaseOrders'), st.listOps(),
      st.getMeta<number>('lastSyncAt'),
    ]);
    setScopes({ products, productVariants, productPackagings, lots, serials });
    const branch = (d as DeviceInfo & { branchId?: string | null }).branchId;
    setLocations(locs.filter((l) => !branch || !l.branchId || l.branchId === branch));
    setTransfers(trs);
    setOrders(pos);
    setOps(all.filter((o) => WAREHOUSE_OP_TYPES.includes(o.type)).sort((a, b) => b.deviceSeq - a.deviceSeq).slice(0, 25));
    setLastSyncAt(synced ?? null);
  }, [org]);

  useEffect(() => {
    if (!org) return;
    void load();
    return syncEngineFor(org).subscribe(() => void load());
  }, [org, load]);

  return { device, scopes, locations, transfers, orders, ops, lastSyncAt, reload: load };
}

/** Scan box: a hardware scanner types the code and Enter; the camera button uses BarcodeDetector where available. */
function ScanBox({ onCode }: { onCode: (code: string) => void }) {
  const [value, setValue] = useState('');
  const [camera, setCamera] = useState(false);
  const video = useRef<HTMLVideoElement>(null);
  const supported = typeof window !== 'undefined' && 'BarcodeDetector' in window;

  useEffect(() => {
    if (!camera || !video.current) return;
    let stop = false;
    let stream: MediaStream | null = null;
    void (async () => {
      try {
        stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } });
        if (!video.current) return;
        video.current.srcObject = stream;
        await video.current.play();
        const Detector = (window as unknown as { BarcodeDetector: new () => { detect(v: HTMLVideoElement):
          Promise<Array<{ rawValue: string }>> } }).BarcodeDetector;
        const detector = new Detector();
        while (!stop) {
          const found = await detector.detect(video.current).catch(() => []);
          if (found[0]?.rawValue) {
            onCode(found[0].rawValue);
            await new Promise((r) => setTimeout(r, 1200)); // one item per glance
          } else {
            await new Promise((r) => setTimeout(r, 250));
          }
        }
      } catch {
        toast.error(tx('The camera is not available'));
        setCamera(false);
      }
    })();
    return () => {
      stop = true;
      stream?.getTracks().forEach((t) => t.stop());
    };
  }, [camera, onCode]);

  return (
    <div className="space-y-2">
      <div className="flex gap-2">
        <div className="relative flex-1">
          <ScanLine className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <Input autoFocus className="pl-9 h-12 text-lg" placeholder={tx('Scan or type a barcode, SKU, lot or serial')}
            value={value} onChange={(e) => setValue(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && value.trim()) {
                e.preventDefault();
                onCode(value);
                setValue('');
              }
            }} />
        </div>
        {supported && (
          <Button type="button" variant={camera ? 'default' : 'outline'} className="h-12" onClick={() => setCamera((c) => !c)}>
            <Camera className="h-5 w-5" />
          </Button>
        )}
      </div>
      {camera && <video ref={video} className="w-full max-h-64 rounded-md bg-black object-cover" muted playsInline />}
    </div>
  );
}

function LocationPicker({ label, value, onChange, locations }: { label: string; value: string; onChange: (v: string) => void;
    locations: Location[] }) {
  return (
    <div className="grid gap-1.5">
      <Label>{label}</Label>
      <select className="h-12 rounded-md border bg-background px-3 text-base" value={value} onChange={(e) => onChange(e.target.value)}>
        <option value="">{tx('Choose…')}</option>
        {locations.map((l) => <option key={l.id} value={l.id}>{l.code} — {l.name}</option>)}
      </select>
    </div>
  );
}

function Lines({ lines, onChange, signed }: { lines: Array<WorkLine & { direction?: 'in' | 'out' }>;
    onChange: (l: Array<WorkLine & { direction?: 'in' | 'out' }>) => void; signed?: boolean }) {
  if (!lines.length) return <p className="text-sm text-muted-foreground">{tx('Nothing scanned yet.')}</p>;
  return (
    <div className="divide-y rounded-md border">
      {lines.map((l, i) => (
        <div key={`${l.productId}:${l.variantId}:${l.batchNumber}`} className="flex flex-wrap items-center gap-2 p-2">
          <div className="min-w-0 flex-1">
            <div className="truncate font-medium">{l.name}</div>
            <div className="text-xs text-muted-foreground">
              {l.batchNumber ? `${tx('Lot')} ${l.batchNumber}` : ''}
              {l.serialNumbers?.length ? `${tx('Serials')}: ${l.serialNumbers.join(', ')}` : ''}
            </div>
          </div>
          {signed && (
            <select className="h-10 rounded-md border bg-background px-2" value={l.direction ?? 'out'}
              onChange={(e) => onChange(lines.map((x, j) => (j === i ? { ...x, direction: e.target.value as 'in' | 'out' } : x)))}>
              <option value="out">{tx('Remove')}</option>
              <option value="in">{tx('Add')}</option>
            </select>
          )}
          <Input type="number" min={0} inputMode="decimal" className="h-10 w-24" value={l.quantity}
            onChange={(e) => onChange(lines.map((x, j) => (j === i ? { ...x, quantity: Number(e.target.value) } : x)))} />
          <Button type="button" variant="ghost" size="sm" onClick={() => onChange(lines.filter((_, j) => j !== i))}>
            <Trash2 className="h-4 w-4" />
          </Button>
        </div>
      ))}
    </div>
  );
}

export function StockTabletPage() {
  const org = useAuthStore((s) => s.organization?.id);
  const actor = useAuthStore((s) => s.user?.id);
  const data = useWarehouseData(org);
  const [busy, setBusy] = useState(false);

  const record = useCallback(async (draft: OpDraft, opId?: string): Promise<boolean> => {
    if (!org || !actor) return false;
    const engine = syncEngineFor(org);
    const policy = { ...DEFAULT_POLICY, ...(await engine.store.getMeta<OfflinePolicy>('offlinePolicy')) };
    const w = offlineWindow((await engine.store.getMeta<number>('lastSyncAt')) ?? null, policy, Date.now());
    if (!w.writable) {
      toast.error(w.reason === 'clock_rollback' ? tx('This device clock is behind its last sync. Fix the date and time.')
        : w.reason === 'never_synced' ? tx('This device has not synced yet. Connect once first.')
          : tx('This device has been offline too long. Connect to sync first.'));
      return false;
    }
    setBusy(true);
    try {
      await engine.enqueue({ type: draft.type, actorUserId: actor, payload: draft.payload }, { opId });
      void engine.sync().catch(() => undefined);
      toast.success(tx('Recorded — it posts when this device syncs'));
      await data.reload();
      return true;
    } finally {
      setBusy(false);
    }
  }, [org, actor, data]);

  if (data.device === undefined) return <div className="p-6"><Loader2 className="h-6 w-6 animate-spin" /></div>;
  if (data.device === null) {
    return (
      <div className="p-6 max-w-xl space-y-3">
        <h1 className="text-2xl font-semibold">{tx('Warehouse tablet')}</h1>
        <p>{tx('This browser is not registered as an offline device yet.')}</p>
        <Link className="underline" to="/settings/devices">{tx('Register it under Offline devices → This browser')}</Link>
      </div>
    );
  }

  return (
    <div className="space-y-4 p-4 md:p-6 max-w-4xl">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h1 className="text-2xl font-semibold">{tx('Warehouse tablet')}</h1>
        <span className="text-sm text-muted-foreground">
          {data.device.name} · {data.lastSyncAt ? tx('synced {{t}}', { t: new Date(data.lastSyncAt).toLocaleString() }) : tx('not synced yet')}
        </span>
      </div>
      <Tabs defaultValue="receive-po">
        <TabsList className="flex flex-wrap h-auto">
          <TabsTrigger value="receive-po">{tx('Receive PO')}</TabsTrigger>
          <TabsTrigger value="dispatch">{tx('Dispatch')}</TabsTrigger>
          <TabsTrigger value="receive-transfer">{tx('Receive transfer')}</TabsTrigger>
          <TabsTrigger value="adjust">{tx('Adjust')}</TabsTrigger>
          <TabsTrigger value="count">{tx('Count')}</TabsTrigger>
        </TabsList>
        <TabsContent value="receive-po"><ReceivePo data={data} record={record} busy={busy} /></TabsContent>
        <TabsContent value="dispatch"><Dispatch data={data} record={record} busy={busy} /></TabsContent>
        <TabsContent value="receive-transfer"><ReceiveTransfer data={data} record={record} busy={busy} /></TabsContent>
        <TabsContent value="adjust"><Adjust data={data} record={record} busy={busy} /></TabsContent>
        <TabsContent value="count"><Count data={data} record={record} busy={busy} /></TabsContent>
      </Tabs>
      <Card>
        <CardHeader><CardTitle className="text-base">{tx('Recent work on this device')}</CardTitle></CardHeader>
        <CardContent className="space-y-2">
          {data.ops.length === 0 && <p className="text-sm text-muted-foreground">{tx('Nothing recorded yet.')}</p>}
          {data.ops.map((o) => {
            const d = describe(o);
            const tone = { ok: 'text-emerald-700', wait: 'text-amber-700', warn: 'text-orange-700', bad: 'text-red-700' }[d.tone];
            return (
              <div key={o.opId} className="flex flex-wrap justify-between gap-2 text-sm border-b pb-1">
                <span>{o.type} · {new Date(o.occurredAt).toLocaleString()}</span>
                <span className={tone}>{tx(d.label)}{d.detail ? ` — ${d.detail}` : ''}</span>
              </div>
            );
          })}
        </CardContent>
      </Card>
    </div>
  );
}

interface TabProps {
  data: ReturnType<typeof useWarehouseData>;
  record: (d: OpDraft, opId?: string) => Promise<boolean>;
  busy: boolean;
}

function useScanLines(scopes: Scopes, locationId?: string) {
  const [lines, setLines] = useState<Array<WorkLine & { direction?: 'in' | 'out' }>>([]);
  const onCode = useCallback((code: string) => {
    const hit = resolveScan(code, scopes, locationId);
    if (!hit) {
      toast.error(tx('“{{code}}” is not on this device. Sync, or check the code.', { code }));
      return;
    }
    setLines((l) => addScan(l, hit));
  }, [scopes, locationId]);
  return { lines, setLines, onCode };
}

function submit(fn: () => OpDraft, record: TabProps['record'], after: () => void, opId?: string) {
  let draft: OpDraft;
  try {
    draft = fn();
  } catch (e) {
    toast.error((e as Error).message);
    return;
  }
  void record(draft, opId).then((ok) => ok && after());
}

function ReceivePo({ data, record, busy }: TabProps) {
  const [poId, setPoId] = useState('');
  const [warehouseId, setWarehouseId] = useState('');
  const [qty, setQty] = useState<Record<string, number>>({});
  const [lots, setLots] = useState<Record<string, string>>({});
  const po = data.orders.find((o) => o.id === poId);
  const onCode = (code: string) => {
    const hit = resolveScan(code, data.scopes);
    const line = hit && po?.lines.find((l) => l.productId === hit.productId && (l.variantId ?? null) === hit.variantId);
    if (!line) {
      toast.error(tx('That item is not on this purchase order'));
      return;
    }
    setQty((q) => ({ ...q, [line.id]: (q[line.id] ?? 0) + (hit?.quantity ?? 1) }));
    if (hit?.kind === 'lot') setLots((l) => ({ ...l, [line.id]: hit.batchNumber }));
  };
  return (
    <Card><CardContent className="space-y-3 pt-4">
      <div className="grid gap-3 md:grid-cols-2">
        <div className="grid gap-1.5">
          <Label>{tx('Purchase order')}</Label>
          <select className="h-12 rounded-md border bg-background px-3" value={poId} onChange={(e) => { setPoId(e.target.value); setQty({}); }}>
            <option value="">{tx('Choose…')}</option>
            {data.orders.map((o) => <option key={o.id} value={o.id}>{o.orderNumber} — {o.partnerName ?? ''}</option>)}
          </select>
        </div>
        <LocationPicker label={tx('Receive into')} value={warehouseId} onChange={setWarehouseId} locations={data.locations} />
      </div>
      {po && (
        <>
          <ScanBox onCode={onCode} />
          <div className="divide-y rounded-md border">
            {po.lines.map((l) => (
              <div key={l.id} className="flex flex-wrap items-center gap-2 p-2">
                <div className="flex-1 min-w-0">
                  <div className="truncate">{l.description}</div>
                  <div className="text-xs text-muted-foreground">{tx('{{n}} still to receive', { n: openOnLine(l) })}</div>
                </div>
                <Input placeholder={tx('Lot')} className="h-10 w-28" value={lots[l.id] ?? ''}
                  onChange={(e) => setLots((x) => ({ ...x, [l.id]: e.target.value }))} />
                <Input type="number" min={0} className="h-10 w-24" value={qty[l.id] ?? 0}
                  onChange={(e) => setQty((q) => ({ ...q, [l.id]: Number(e.target.value) }))} />
              </div>
            ))}
          </div>
          <Button type="button" className="h-12 w-full" disabled={busy} onClick={() => submit(() => grnOp({ clientId: newId(),
            purchaseOrderId: po.id, warehouseId, lines: po.lines.map((line) => ({ line, quantity: qty[line.id] ?? 0,
              batchNumber: lots[line.id] })) }), record, () => { setQty({}); setLots({}); })}>
            {tx('Record receipt')}
          </Button>
        </>
      )}
      {!data.orders.length && <p className="text-sm text-muted-foreground">{tx('No open purchase orders on this device.')}</p>}
    </CardContent></Card>
  );
}

function Dispatch({ data, record, busy }: TabProps) {
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const { lines, setLines, onCode } = useScanLines(data.scopes, from);
  const approved = data.transfers.filter((t) => t.status === 'approved' && data.locations.some((l) => l.id === t.fromLocId));
  return (
    <Card><CardContent className="space-y-3 pt-4">
      {approved.length > 0 && (
        <div className="space-y-2">
          <Label>{tx('Approved transfers ready to dispatch')}</Label>
          {approved.map((t) => (
            <div key={t.id} className="flex items-center justify-between gap-2 rounded-md border p-2">
              <span>{t.transferCode} · {t.items.length} {tx('line(s)')}</span>
              <Button type="button" disabled={busy} onClick={() => submit(() => dispatchOp({ clientId: newId(), transferId: t.id }),
                record, () => undefined)}>{tx('Dispatch')}</Button>
            </div>
          ))}
        </div>
      )}
      <Label>{tx('Or dispatch scanned goods')}</Label>
      <div className="grid gap-3 md:grid-cols-2">
        <LocationPicker label={tx('From')} value={from} onChange={setFrom} locations={data.locations} />
        <LocationPicker label={tx('To')} value={to} onChange={setTo} locations={data.locations} />
      </div>
      <ScanBox onCode={onCode} />
      <Lines lines={lines} onChange={setLines} />
      <Button type="button" className="h-12 w-full" disabled={busy} onClick={() => submit(() => dispatchOp({ clientId: newId(),
        fromLocationId: from, toLocationId: to, lines }), record, () => setLines([]))}>
        {tx('Dispatch to transit')}
      </Button>
    </CardContent></Card>
  );
}

function ReceiveTransfer({ data, record, busy }: TabProps) {
  const [transferId, setTransferId] = useState('');
  const [got, setGot] = useState<Record<string, { received: number; damaged: number; short: number }>>({});
  const inbound = useMemo(() => data.transfers.filter((t) => data.locations.some((l) => l.id === t.toLocId)), [data]);
  const t = inbound.find((x) => x.id === transferId);
  const dispatched = !!t && t.status !== 'approved';
  const onCode = (code: string) => {
    const hit = resolveScan(code, data.scopes);
    const item = hit && t?.items.find((i) => i.productId === hit.productId && (i.variantId ?? null) === hit.variantId);
    if (!item) {
      toast.error(tx('That item is not on this transfer'));
      return;
    }
    setGot((g) => ({ ...g, [item.id]: { ...(g[item.id] ?? { received: 0, damaged: 0, short: 0 }),
      received: (g[item.id]?.received ?? 0) + (hit?.quantity ?? 1) } }));
  };
  const set = (id: string, k: 'received' | 'damaged' | 'short', v: number) =>
    setGot((g) => ({ ...g, [id]: { ...(g[id] ?? { received: 0, damaged: 0, short: 0 }), [k]: v } }));
  return (
    <Card><CardContent className="space-y-3 pt-4">
      <div className="grid gap-1.5">
        <Label>{tx('Incoming transfer')}</Label>
        <select className="h-12 rounded-md border bg-background px-3" value={transferId} onChange={(e) => { setTransferId(e.target.value); setGot({}); }}>
          <option value="">{tx('Choose…')}</option>
          {inbound.map((x) => <option key={x.id} value={x.id}>{x.transferCode} ({x.status})</option>)}
        </select>
      </div>
      {t && (
        <>
          {!dispatched && (
            <p className="rounded-md bg-amber-50 p-2 text-sm text-amber-900 dark:bg-amber-950 dark:text-amber-100">
              {tx('The server does not have this dispatch yet. The receipt is kept and applied when the dispatch syncs.')}
            </p>
          )}
          <ScanBox onCode={onCode} />
          <div className="divide-y rounded-md border">
            {t.items.map((i) => (
              <div key={i.id} className="grid grid-cols-2 md:grid-cols-5 items-center gap-2 p-2">
                <div className="col-span-2">
                  <div className="truncate">{i.productName}</div>
                  <div className="text-xs text-muted-foreground">{tx('{{n}} expected', { n: outstanding(i, dispatched) })}</div>
                </div>
                {(['received', 'damaged', 'short'] as const).map((k) => (
                  <div key={k} className="grid gap-1">
                    <span className="text-xs text-muted-foreground">{tx(k === 'received' ? 'Received' : k === 'damaged' ? 'Damaged' : 'Missing')}</span>
                    <Input type="number" min={0} className="h-10" value={got[i.id]?.[k] ?? 0}
                      onChange={(e) => set(i.id, k, Number(e.target.value))} />
                  </div>
                ))}
              </div>
            ))}
          </div>
          <Button type="button" className="h-12 w-full" disabled={busy} onClick={() => submit(() => receiveOp({ clientId: newId(),
            transferId: t.id, lines: t.items.map((i) => ({ itemId: i.id, ...(got[i.id] ?? { received: 0, damaged: 0, short: 0 }) })) }),
            record, () => setGot({}))}>
            {tx('Record receipt')}
          </Button>
        </>
      )}
      {!inbound.length && <p className="text-sm text-muted-foreground">{tx('No transfers coming to this branch on this device.')}</p>}
    </CardContent></Card>
  );
}

function Adjust({ data, record, busy }: TabProps) {
  const [locationId, setLocationId] = useState('');
  const [reason, setReason] = useState('');
  const { lines, setLines, onCode } = useScanLines(data.scopes, locationId);
  return (
    <Card><CardContent className="space-y-3 pt-4">
      <div className="grid gap-3 md:grid-cols-2">
        <LocationPicker label={tx('Location')} value={locationId} onChange={setLocationId} locations={data.locations} />
        <div className="grid gap-1.5">
          <Label>{tx('Reason')}</Label>
          <Input className="h-12" value={reason} onChange={(e) => setReason(e.target.value)} placeholder={tx('e.g. damaged, found')} />
        </div>
      </div>
      <ScanBox onCode={onCode} />
      <Lines lines={lines} onChange={setLines} signed />
      <p className="text-xs text-muted-foreground">{tx('The adjustment waits for approval in the back office, as any adjustment does.')}</p>
      <Button type="button" className="h-12 w-full" disabled={busy} onClick={() => submit(() => adjustOp({ clientId: newId(),
        locationId, reason, lines: lines.map((l) => ({ ...l, direction: l.direction ?? 'out' })) }), record, () => setLines([]))}>
        {tx('Record adjustment')}
      </Button>
    </CardContent></Card>
  );
}

function Count({ data, record, busy }: TabProps) {
  const [locationId, setLocationId] = useState('');
  const { lines, setLines, onCode } = useScanLines(data.scopes, locationId);
  return (
    <Card><CardContent className="space-y-3 pt-4">
      <LocationPicker label={tx('Location')} value={locationId} onChange={setLocationId} locations={data.locations} />
      <ScanBox onCode={onCode} />
      <Lines lines={lines} onChange={setLines} />
      <p className="text-xs text-muted-foreground">{tx('Scan every item on the shelf. The difference is worked out against the stock at the moment you counted.')}</p>
      <Button type="button" className="h-12 w-full" disabled={busy} onClick={() => submit(() => countOp({ locationId, lines }),
        record, () => setLines([]))}>
        {tx('Record count')}
      </Button>
    </CardContent></Card>
  );
}
