import { useState, type PointerEvent as ReactPointerEvent } from 'react';
import { Camera, Crosshair, Eraser, Plus, Undo2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@/components/ui/dialog';
import { useAuthStore } from '@/stores/auth.store';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { apiMessage, notify } from '@/lib/notify';
import { cn } from '@/lib/utils';
import {
  ACTIVITY_RESEARCH_PARAM, LINK_MAX_LENGTH, PARAM_CHOICES, ZONE_DEFAULT_PARAMS, ZONE_LINKS, ZONE_SHAPES,
  ZONE_TYPES, useCalibrateLpZone, useCreateLpZone, useLpCamera, useLpLocationChoices, useLpTableChoices,
  useLpTillChoices, useRetireLpZone, useUpdateLpZone, type LinkedType, type LpCamera, type LpZone, type Point, type Side,
  type ZoneShape, type ZoneType, type ZoneUpdate,
} from '@/features/loss-prevention/vision-api';
import {
  LINK_LABELS, PARAM_CHOICE_LABELS, PARAM_LABELS, SELECT, ZONE_LABELS, ZONE_USES, centroid, clamp01, labelOf, round4,
  useCameraSnapshot, useObjectUrl, type CameraSnapshot,
} from './vision-ui';
import { defaultLink, inArrow, joinParams, paramProblem, splitParams, svgPoints } from './zone-geometry';

/**
 * Zones of one camera, drawn on a still from the agent. Points are fractions of the picture (x to the right, y down),
 * so a camera that changes resolution keeps its zones in place.
 */

const EDIT_COLOR = '#16a34a';
const OTHER_COLOR = '#f59e0b';

function Shape({ points, color, dashed, aspect, side, line }: {
  points: Point[]; color: string; dashed?: boolean; aspect: number; side?: Side | null; line: boolean;
}) {
  if (points.length < 2) return null;
  const stroke = {
    stroke: color, strokeWidth: 2, strokeDasharray: dashed ? '6 4' : undefined, vectorEffect: 'non-scaling-stroke' as const,
  };
  if (line || points.length === 2) {
    return (
      <g>
        <polyline points={svgPoints(points)} fill="none" {...stroke} />
        {points.length === 2 && side && <polygon points={svgPoints(inArrow(points[0], points[1], side, aspect))} fill={color} />}
      </g>
    );
  }
  return <polygon points={svgPoints(points)} fill={color} fillOpacity={0.2} {...stroke} />;
}

export interface ZoneOverlay {
  id: string;
  name: string;
  polygon: Point[];
  inSide?: Side | null;
}

/**
 * The picture with zones drawn over it. With `onChange` it edits `points`: a click adds a point, dragging a point moves
 * it. Without, it only shows the zones.
 */
export function ZoneCanvas({ imageUrl, aspect, onAspect, points, onChange, shape = 'area', side, overlays = [], placeholder }: {
  imageUrl: string | null;
  aspect: number;
  onAspect?: (aspect: number) => void;
  points?: Point[];
  onChange?: (points: Point[]) => void;
  shape?: ZoneShape;
  side?: Side | null;
  overlays?: ZoneOverlay[];
  placeholder?: string;
}) {
  const [drag, setDrag] = useState<number | null>(null);
  const editing = points && onChange ? { points, onChange } : null;

  const pointAt = (e: ReactPointerEvent<HTMLDivElement>): Point => {
    const r = e.currentTarget.getBoundingClientRect();
    return [round4(clamp01((e.clientX - r.left) / r.width)), round4(clamp01((e.clientY - r.top) / r.height))];
  };
  const onPointerDown = (e: ReactPointerEvent<HTMLDivElement>) => {
    if (!editing || e.button !== 0) return;
    const handle = (e.target as HTMLElement).closest('[data-handle]');
    if (handle) {
      setDrag(Number(handle.getAttribute('data-handle')));
      e.currentTarget.setPointerCapture(e.pointerId);
      e.preventDefault();
      return;
    }
    if (shape === 'line' && editing.points.length >= 2) return;
    editing.onChange([...editing.points, pointAt(e)]);
  };
  const onPointerMove = (e: ReactPointerEvent<HTMLDivElement>) => {
    if (!editing || drag === null) return;
    const p = pointAt(e);
    editing.onChange(editing.points.map((q, i) => (i === drag ? p : q)));
  };
  const onPointerUp = (e: ReactPointerEvent<HTMLDivElement>) => {
    if (drag === null) return;
    if (e.currentTarget.hasPointerCapture(e.pointerId)) e.currentTarget.releasePointerCapture(e.pointerId);
    setDrag(null);
  };

  return (
    <div style={{ aspectRatio: String(aspect) }}
      className={cn('relative w-full touch-none select-none overflow-hidden rounded-md border bg-muted', editing && 'cursor-crosshair')}
      onPointerDown={onPointerDown} onPointerMove={onPointerMove} onPointerUp={onPointerUp} onPointerCancel={onPointerUp}>
      {imageUrl ? (
        <img src={imageUrl} alt="" draggable={false} className="pointer-events-none absolute inset-0 h-full w-full"
          onLoad={(e) => {
            const i = e.currentTarget;
            if (i.naturalWidth && i.naturalHeight) onAspect?.(i.naturalWidth / i.naturalHeight);
          }} />
      ) : (
        <div className="pointer-events-none absolute inset-0 flex items-center justify-center bg-[linear-gradient(to_right,rgba(127,127,127,.15)_1px,transparent_1px),linear-gradient(to_bottom,rgba(127,127,127,.15)_1px,transparent_1px)] bg-[size:10%_10%] p-6 text-center text-xs text-muted-foreground">
          {placeholder}
        </div>
      )}
      <svg className="pointer-events-none absolute inset-0 h-full w-full" viewBox="0 0 1000 1000" preserveAspectRatio="none">
        {overlays.map((o) => (
          <Shape key={o.id} points={o.polygon} color={OTHER_COLOR} dashed aspect={aspect} side={o.inSide} line={o.polygon.length === 2} />
        ))}
        {editing && (
          <Shape points={editing.points} color={EDIT_COLOR} aspect={aspect} side={shape === 'line' ? side : null} line={shape === 'line'} />
        )}
      </svg>
      {overlays.filter((o) => o.polygon.length > 0).map((o) => {
        const [x, y] = centroid(o.polygon);
        return (
          <span key={`label-${o.id}`} style={{ left: `${x * 100}%`, top: `${y * 100}%` }}
            className="pointer-events-none absolute -translate-x-1/2 -translate-y-1/2 whitespace-nowrap rounded bg-black/60 px-1 text-[10px] text-white">
            {o.name}
          </span>
        );
      })}
      {editing?.points.map(([x, y], i) => (
        <span key={i} data-handle={i} style={{ left: `${x * 100}%`, top: `${y * 100}%` }}
          className="absolute flex h-4 w-4 -translate-x-1/2 -translate-y-1/2 cursor-move items-center justify-center rounded-full border-2 border-white bg-green-600 text-[9px] font-bold leading-none text-white shadow">
          {shape === 'line' ? (i === 0 ? 'A' : 'B') : ''}
        </span>
      ))}
    </div>
  );
}

function PictureBar({ snap }: { snap: CameraSnapshot }) {
  if (!snap.enabled) {
    return (
      <p className="text-xs text-muted-foreground">
        {tx('Seeing the camera picture needs the evidence permission. You can still draw and change zones.')}
      </p>
    );
  }
  const message = snap.state === 'loading' ? tx('Loading the picture…')
    : snap.state === 'waiting' ? tx('Waiting for the agent to send a picture…')
      : snap.state === 'none' ? tx('No picture yet. Ask the agent for one.')
        : snap.state === 'timeout' ? tx('No new picture arrived. Check that the agent is online.')
          : snap.state === 'error' ? snap.error
            : snap.snapshot?.takenAt ? `${tx('Picture taken')} ${dateTime(snap.snapshot.takenAt)}` : '';
  return (
    <div className="flex flex-wrap items-center justify-between gap-2">
      <p className={cn('text-xs', snap.state === 'error' ? 'text-destructive' : 'text-muted-foreground')}>{message}</p>
      <Button size="sm" variant="outline" disabled={snap.state === 'loading' || snap.state === 'waiting'}
        onClick={() => void snap.refresh()}>
        <Camera className="mr-1.5 h-4 w-4" />{tx('Take a new picture')}
      </Button>
    </div>
  );
}

const linkText = (z: LpZone) => (z.linkedType ? `${labelOf(LINK_LABELS, z.linkedType)} ${z.linkedId ?? ''}` : '—');

function ZoneForm({ camera, zone, imageUrl, aspect, onAspect, others, snap, onDone }: {
  camera: LpCamera;
  zone: LpZone | null;
  imageUrl: string | null;
  aspect: number;
  onAspect: (a: number) => void;
  others: LpZone[];
  snap: CameraSnapshot;
  onDone: () => void;
}) {
  const can = useAuthStore((s) => s.hasPermission);
  const [zoneType, setZoneType] = useState<ZoneType>(zone?.zoneType ?? 'cash_drawer');
  const [name, setName] = useState(zone?.name ?? '');
  const [shape, setShape] = useState<ZoneShape>(
    zone ? (zone.polygon.length === 2 ? 'line' : 'area') : ZONE_SHAPES.cash_drawer[0],
  );
  const [points, setPoints] = useState<Point[]>(zone?.polygon ?? []);
  const [side, setSide] = useState<Side>(zone?.inSide ?? 'left');
  const [linkedType, setLinkedType] = useState<LinkedType | ''>(zone ? zone.linkedType ?? '' : defaultLink('cash_drawer'));
  const [linkedId, setLinkedId] = useState(zone?.linkedId ?? '');
  const [initial] = useState(() => splitParams(zone?.zoneType ?? 'cash_drawer', zone?.params));
  const [params, setParams] = useState<Record<string, string>>(initial.text);
  const [enabled, setEnabled] = useState(zone?.enabled ?? true);
  // LP7: kept with the other settings the form does not show, but offered as a switch of its own.
  const [research, setResearch] = useState(initial.extra[ACTIVITY_RESEARCH_PARAM] === true);
  const [confirmRetire, setConfirmRetire] = useState(false);
  const create = useCreateLpZone();
  const update = useUpdateLpZone();
  const retire = useRetireLpZone();
  const calibrate = useCalibrateLpZone();
  const tills = useLpTillChoices(linkedType === 'till' && can(PERMISSIONS.cashRegister.read));
  const locations = useLpLocationChoices(linkedType === 'location' && can(PERMISSIONS.inventoryLocation.read));
  const tables = useLpTableChoices(linkedType === 'table' && can(PERMISSIONS.tables.view));
  const links = ZONE_LINKS[zoneType];
  const shapes = ZONE_SHAPES[zoneType];
  const choices = (linkedType === 'till' && tills.isSuccess ? tills.data
    : linkedType === 'location' && locations.isSuccess ? locations.data
      : linkedType === 'table' && tables.isSuccess ? tables.data : null)
    ?.filter((o) => (!o.branchId || o.branchId === camera.branchId) && (o.isActive !== false || o.id === linkedId));

  const onType = (t: ZoneType) => {
    setZoneType(t);
    if (!ZONE_SHAPES[t].includes(shape)) {
      setShape(ZONE_SHAPES[t][0]);
      setPoints([]);
    }
    setLinkedType(defaultLink(t));
    setLinkedId('');
    setParams({});
  };
  const onShape = (s: ZoneShape) => {
    setShape(s);
    if (s === 'line') setPoints((p) => p.slice(0, 2));
  };

  const sameEnds = shape === 'line' && points.length === 2 && points[0][0] === points[1][0] && points[0][1] === points[1][1];
  const problem = !name.trim() ? tx('Give the zone a name.')
    : shape === 'area' && points.length < 3 ? tx('Click at least 3 corners on the picture.')
      : shape === 'line' && points.length !== 2 ? tx('Click the 2 ends of the line, A then B.')
        : sameEnds ? tx('The 2 ends of the line must differ.')
          : links.required && !linkedId.trim() ? tx('Choose the till this zone watches.')
            : linkedType && !linkedId.trim() ? tx('Choose what the zone is linked to, or no link.')
              : linkedId.trim().length > LINK_MAX_LENGTH ? tx('The link is too long.')
                : paramProblem(zoneType, params) ? tx('Check the detection settings: numbers of 0 or more, fractions from 0 to 1.')
                  : null;
  const saving = create.isPending || update.isPending;

  const extra: Record<string, unknown> = { ...initial.extra };
  delete extra[ACTIVITY_RESEARCH_PARAM];
  if (research) extra[ACTIVITY_RESEARCH_PARAM] = true;
  const otherKeys = Object.keys(initial.extra).filter((k) => k !== ACTIVITY_RESEARCH_PARAM);

  const onSave = () => {
    const body: ZoneUpdate = {
      name: name.trim(),
      polygon: points,
      inSide: shape === 'line' ? side : null,
      linkedType: linkedType || null,
      linkedId: linkedType ? linkedId.trim() : null,
      params: joinParams(zoneType, params, extra),
      enabled,
    };
    const done = {
      onSuccess: () => { notify.success(tx('Zone saved. The agent picks it up with its next settings.')); onDone(); },
      onError: (e: unknown) => notify.error(apiMessage(e, tx('Could not save the zone'))),
    };
    if (zone) update.mutate({ id: zone.id, body }, done);
    else create.mutate({ ...body, cameraId: camera.id, zoneType }, done);
  };
  const onCalibrate = (id: string) => calibrate.mutate(id, {
    onSuccess: () => notify.success(tx('Asked the agent to take the current picture as the closed drawer.')),
    onError: (e) => notify.error(apiMessage(e, tx('Could not ask the agent'))),
  });
  const onRetire = (id: string) => retire.mutate(id, {
    onSuccess: () => { notify.success(tx('Zone retired')); onDone(); },
    onError: (e) => notify.error(apiMessage(e, tx('Could not retire the zone'))),
  });

  return (
    <div className="grid gap-4 lg:grid-cols-[3fr_2fr]">
      <div className="space-y-2">
        <PictureBar snap={snap} />
        <ZoneCanvas imageUrl={imageUrl} aspect={aspect} onAspect={onAspect} points={points} onChange={setPoints}
          shape={shape} side={side} overlays={others}
          placeholder={tx('No picture. You can still draw; check the zone once a picture is available.')} />
        <p className="text-xs text-muted-foreground">
          {shape === 'line'
            ? tx('Click the 2 ends of the line, A then B. Drag an end to move it. The arrow points to the inside.')
            : tx('Click on the picture to add corners. Drag a corner to move it.')}
        </p>
        <div className="flex flex-wrap gap-2">
          <Button size="sm" variant="outline" disabled={points.length === 0} onClick={() => setPoints((p) => p.slice(0, -1))}>
            <Undo2 className="mr-1.5 h-4 w-4" />{tx('Remove last point')}
          </Button>
          <Button size="sm" variant="outline" disabled={points.length === 0} onClick={() => setPoints([])}>
            <Eraser className="mr-1.5 h-4 w-4" />{tx('Clear')}
          </Button>
        </div>
      </div>
      <div className="space-y-3">
        <div className="space-y-1">
          <Label>{tx('Name')}</Label>
          <Input value={name} maxLength={100} onChange={(e) => setName(e.target.value)} placeholder={tx('e.g. Till 1 drawer')} />
        </div>
        <div className="space-y-1">
          <Label>{tx('Purpose')}</Label>
          <select className={SELECT} value={zoneType} disabled={!!zone} onChange={(e) => onType(e.target.value as ZoneType)}>
            {ZONE_TYPES.map((t) => <option key={t} value={t}>{labelOf(ZONE_LABELS, t)}</option>)}
          </select>
          <p className="text-xs text-muted-foreground">
            {ZONE_USES[zoneType] ?? tx('Its observations are recorded; no rule uses them yet.')}
            {zone ? ` ${tx('The purpose of a saved zone does not change; retire it and add a new one instead.')}` : ''}
          </p>
        </div>
        {shapes.length > 1 && (
          <div className="space-y-1">
            <Label>{tx('Shape')}</Label>
            <select className={SELECT} value={shape} onChange={(e) => onShape(e.target.value as ZoneShape)}>
              {shapes.map((s) => <option key={s} value={s}>{s === 'line' ? tx('Counting line') : tx('Area')}</option>)}
            </select>
          </div>
        )}
        {shape === 'line' && (
          <div className="space-y-1">
            <Label>{tx('Inside is')}</Label>
            <select className={SELECT} value={side} onChange={(e) => setSide(e.target.value as Side)}>
              <option value="left">{tx('Left of A → B, as seen on the picture')}</option>
              <option value="right">{tx('Right of A → B, as seen on the picture')}</option>
            </select>
            <p className="text-xs text-muted-foreground">{tx('Crossing the line in the direction of the arrow counts as in.')}</p>
          </div>
        )}
        {links.types.length > 0 && (
          <div className="space-y-1">
            <Label>{tx('Linked to')}{links.required ? ' *' : ''}</Label>
            {(links.types.length > 1 || !links.required) && (
              <select className={SELECT} value={linkedType}
                onChange={(e) => { setLinkedType(e.target.value as LinkedType | ''); setLinkedId(''); }}>
                {!links.required && <option value="">{tx('Not linked')}</option>}
                {links.types.map((t) => <option key={t} value={t}>{labelOf(LINK_LABELS, t)}</option>)}
              </select>
            )}
            {linkedType && (choices ? (
              <select className={SELECT} value={linkedId} onChange={(e) => setLinkedId(e.target.value)}>
                <option value="">{tx('Choose…')}</option>
                {linkedId && !choices.some((o) => o.id === linkedId) && <option value={linkedId}>{linkedId}</option>}
                {choices.map((o) => <option key={o.id} value={o.id}>{o.code ? `${o.code} · ${o.name}` : o.name}</option>)}
              </select>
            ) : (
              <Input value={linkedId} maxLength={LINK_MAX_LENGTH} onChange={(e) => setLinkedId(e.target.value)}
                placeholder={linkedType === 'till' ? tx('Till (cash register) id')
                  : linkedType === 'location' ? tx('Stock location id')
                    : linkedType === 'table' ? tx('POS table id') : tx('A name, e.g. Bar 1')} />
            ))}
          </div>
        )}
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
          {tx('The agent watches this zone')}
        </label>
        <label className="flex items-start gap-2 text-sm">
          <input type="checkbox" className="mt-1" checked={research} onChange={(e) => setResearch(e.target.checked)} />
          <span>
            {tx('Activity research (shadow mode)')}
            <span className="block text-xs text-muted-foreground">
              {tx('The agent also reports the activities its recognizers see in this zone, for review in the Research tab. Research activities never make a finding or a case.')}
            </span>
          </span>
        </label>
        <details className="rounded-md border p-2">
          <summary className="cursor-pointer text-sm font-medium">{tx('Detection settings')}</summary>
          <p className="my-2 text-xs text-muted-foreground">{tx('Leave a field empty to use the default shown in it.')}</p>
          <div className="space-y-2">
            {Object.entries(ZONE_DEFAULT_PARAMS[zoneType]).map(([k, d]) => (
              <div key={k} className="space-y-1">
                <Label className="text-xs">{PARAM_LABELS[k] ?? k}</Label>
                {PARAM_CHOICES[k] ? (
                  <select className={SELECT} value={params[k] ?? ''} onChange={(e) => setParams((p) => ({ ...p, [k]: e.target.value }))}>
                    <option value="">{`${tx('Default')}: ${labelOf(PARAM_CHOICE_LABELS, String(d))}`}</option>
                    {PARAM_CHOICES[k].map((c) => <option key={c} value={c}>{labelOf(PARAM_CHOICE_LABELS, c)}</option>)}
                  </select>
                ) : (
                  <Input type={typeof d === 'number' ? 'number' : 'text'} step="any" min={0} value={params[k] ?? ''}
                    placeholder={Array.isArray(d) ? d.join(', ') : String(d)}
                    onChange={(e) => setParams((p) => ({ ...p, [k]: e.target.value }))} />
                )}
              </div>
            ))}
            {otherKeys.length > 0 && (
              <p className="text-xs text-muted-foreground">
                {tx('Other settings kept as they are')}: {otherKeys.join(', ')}
              </p>
            )}
          </div>
        </details>
        {zone && zone.zoneType === 'cash_drawer' && (
          <div className="space-y-1 rounded-md border p-2">
            <p className="text-xs text-muted-foreground">
              {tx('Close the drawer, then press this. The agent takes the current picture as the closed drawer.')}
            </p>
            <Button size="sm" variant="outline" disabled={calibrate.isPending} onClick={() => onCalibrate(zone.id)}>
              <Crosshair className="mr-1.5 h-4 w-4" />{tx('Calibrate: drawer is closed now')}
            </Button>
          </div>
        )}
        {problem && <p className="text-xs text-destructive">{problem}</p>}
        <div className="flex flex-wrap gap-2">
          <Button variant="outline" onClick={onDone}>{tx('Back to the zones')}</Button>
          <Button disabled={!!problem || saving} onClick={onSave}>{tx('Save zone')}</Button>
        </div>
        {zone && (
          <div className="border-t pt-3">
            {!confirmRetire ? (
              <Button size="sm" variant="outline" onClick={() => setConfirmRetire(true)}>{tx('Retire this zone')}</Button>
            ) : (
              <div className="space-y-2">
                <p className="text-xs text-muted-foreground">
                  {tx('The agent stops watching it. Its past observations and cases keep pointing at it.')}
                </p>
                <div className="flex gap-2">
                  <Button size="sm" variant="outline" onClick={() => setConfirmRetire(false)}>{tx('Cancel')}</Button>
                  <Button size="sm" variant="destructive" disabled={retire.isPending} onClick={() => onRetire(zone.id)}>
                    {tx('Retire')}
                  </Button>
                </div>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

/** The zones of one camera over its latest picture; a zone opens in the editor in the same window. */
export function CameraZonesDialog({ cameraId, canEvidence, onClose }: {
  cameraId: string; canEvidence: boolean; onClose: () => void;
}) {
  const { data: camera, isLoading, isError } = useLpCamera(cameraId);
  const snap = useCameraSnapshot(cameraId, canEvidence);
  const imageUrl = useObjectUrl(snap.snapshot?.blob);
  const [aspect, setAspect] = useState(16 / 9);
  const [editing, setEditing] = useState<LpZone | 'new' | null>(null);
  const zones = (camera?.zones ?? []).filter((z) => !z.retiredAt);
  const retired = (camera?.zones ?? []).filter((z) => z.retiredAt);
  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent className="max-h-[92vh] max-w-5xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>
            {camera ? `${camera.name} · ` : ''}
            {editing === 'new' ? tx('New zone') : editing ? editing.name : tx('Zones')}
          </DialogTitle>
          <DialogDescription>
            {tx('A zone marks an area of the picture, or a counting line, with one purpose. Each change is sent to the agent with its settings.')}
          </DialogDescription>
        </DialogHeader>
        {isLoading && <p className="text-muted-foreground">{tx('Loading…')}</p>}
        {isError && <p className="text-destructive">{tx('Could not load the camera.')}</p>}
        {camera && editing === null && (
          <div className="space-y-3">
            <PictureBar snap={snap} />
            <ZoneCanvas imageUrl={imageUrl} aspect={aspect} onAspect={setAspect} overlays={zones}
              placeholder={tx('No picture yet.')} />
            <div className="flex items-center justify-between gap-2">
              <p className="text-sm font-medium">{tx('Zones')} ({zones.length})</p>
              <Button size="sm" onClick={() => setEditing('new')}><Plus className="mr-1.5 h-4 w-4" />{tx('Add a zone')}</Button>
            </div>
            <div className="divide-y rounded-md border text-sm">
              {zones.length === 0 && (
                <p className="p-3 text-muted-foreground">
                  {tx('No zones yet. A cash drawer zone and a checkout zone on the same till feed the cashier camera rules.')}
                </p>
              )}
              {zones.map((z) => (
                <div key={z.id} className="flex flex-wrap items-center justify-between gap-2 p-3 hover:bg-muted/50">
                  <button type="button" onClick={() => setEditing(z)} className="text-left">
                    <span className="font-medium hover:underline">{z.name}</span>
                    <span className="text-muted-foreground"> · {labelOf(ZONE_LABELS, z.zoneType)}
                      {z.polygon.length === 2 ? ` · ${tx('Counting line')}` : ''} · {linkText(z)}</span>
                  </button>
                  {!z.enabled && <Badge variant="outline">{tx('Not watched')}</Badge>}
                </div>
              ))}
            </div>
            {retired.length > 0 && (
              <p className="text-xs text-muted-foreground">{tx('Retired')}: {retired.map((z) => z.name).join(', ')}</p>
            )}
          </div>
        )}
        {camera && editing !== null && (
          <ZoneForm key={editing === 'new' ? 'new' : editing.id} camera={camera} zone={editing === 'new' ? null : editing}
            imageUrl={imageUrl} aspect={aspect} onAspect={setAspect} snap={snap}
            others={zones.filter((z) => editing === 'new' || z.id !== editing.id)} onDone={() => setEditing(null)} />
        )}
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Close window')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
