import { useEffect, useState, type ReactNode } from 'react';
import { Copy, Play, Plus } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@/components/ui/dialog';
import { DataTable, type Column } from '@/components/data-table';
import { FilterSelect, ListCard, ListToolbar } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { getApiBaseUrl } from '@/lib/api';
import { dateTime, formatBytes, relativeTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { apiMessage, notify } from '@/lib/notify';
import {
  STREAM_ALIAS_RE, VISION_SETTINGS, useCreateLpAgent, useCreateLpCamera, useLpAgent, useLpAgents, useLpBranchChoices,
  useLpAgentCommands, useLpCameras, useLpModels, useLpSettings, useNewLpEnrolmentCode, useRevokeLpAgent,
  useSaveLpSettings, useDeleteLpCamera, useUpdateLpAgent, useUpdateLpCamera, type AgentEvent, type EnrolledAgent, type LpAgent, type LpCamera, type LpModels,
} from '@/features/loss-prevention/vision-api';
import {
  AGENT_EVENT_LABELS, AGENT_STATUS_LABELS, COMMAND_KIND_LABELS, COMMAND_STATE_LABELS, HEALTH_LABELS, HEALTH_REASON_LABELS, SELECT, SETTING_LABELS, TABLE, labelOf,
  toNumber,
} from './vision-ui';
import { HealthBadge, LiveViewDialog, StreamBadge } from './vision-shared';
import { CameraZonesDialog } from './ZoneEditor';

/**
 * Cameras (LP1): edge agents, their cameras and zones, and the camera settings. Needs `lp:configure`; the camera
 * picture and live view also need `lp:evidence`. Camera addresses and passwords never reach this server.
 */
export function CamerasTab({ canEvidence }: { canEvidence: boolean }) {
  return (
    <>
      <p className="text-sm text-muted-foreground">
        {tx('An edge agent is a small computer at the branch. It reads the cameras on the local network, keeps the video there and connects out to this server; nothing connects in to it. Faces are not recognised: staff are identified only by their POS login.')}
      </p>
      <AgentsSection />
      <CamerasSection canEvidence={canEvidence} />
      <VisionSettingsCard />
    </>
  );
}

function Fact({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="flex justify-between gap-3 border-b border-dashed py-1">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="text-right">{value === null || value === undefined || value === '' ? '—' : value}</dd>
    </div>
  );
}

const versionText = (current: string | null, wanted: string | null) =>
  `${current ?? '—'}${wanted && wanted !== current ? ` → ${wanted}` : ''}`;

function eventNote(t: AgentEvent): string {
  const d: Record<string, unknown> = t.data ?? {};
  const reasons = Array.isArray(d.reasons) ? d.reasons.map((r) => labelOf(HEALTH_REASON_LABELS, String(r))).join(', ') : '';
  const reason = typeof d.reason === 'string' ? d.reason : '';
  const text = [reasons, reason].filter(Boolean).join(' · ');
  return text ? ` — ${text}` : '';
}

function AgentsSection() {
  const { data, isLoading, isError } = useLpAgents();
  const [adding, setAdding] = useState(false);
  const [openId, setOpenId] = useState<string | null>(null);
  const columns: Column<LpAgent>[] = [
    {
      key: 'name', header: tx('Agent'), render: (a) => (
        <button type="button" className="text-left font-medium hover:underline" onClick={() => setOpenId(a.id)}>
          {a.name}
          {a.hostname && <span className="block text-xs font-normal text-muted-foreground">{a.hostname}</span>}
        </button>
      ),
    },
    { key: 'branchName', header: tx('Branch'), render: (a) => a.branchName ?? a.branchId },
    { key: 'status', header: tx('Enrolment'), render: (a) => labelOf(AGENT_STATUS_LABELS, a.status) },
    {
      key: 'health', header: tx('Health'),
      render: (a) => (a.status === 'revoked' ? '—' : <HealthBadge health={a.health} reasons={a.healthReasons} />),
    },
    {
      key: 'lastHeartbeatAt', header: tx('Last heard'),
      render: (a) => (a.lastHeartbeatAt ? <span title={dateTime(a.lastHeartbeatAt)}>{relativeTime(a.lastHeartbeatAt)}</span> : '—'),
    },
    { key: 'cameraCount', header: tx('Cameras'), className: 'text-right', render: (a) => a.cameraCount ?? '—' },
    { key: 'versions', header: tx('Agent / model'), render: (a) => `${a.agentVersion ?? '—'} / ${a.modelVersion ?? '—'}` },
    { key: 'queueLength', header: tx('Waiting to send'), className: 'text-right', render: (a) => toNumber(a.queueLength) ?? '—' },
  ];
  return (
    <>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="font-medium">{tx('Edge agents')}</p>
        <Button size="sm" onClick={() => setAdding(true)}><Plus className="mr-1.5 h-4 w-4" />{tx('Add an agent')}</Button>
      </div>
      <ListCard>
        <div className="overflow-x-auto">
          <DataTable columns={columns} data={data ?? []} loading={isLoading} loadingRows={3} getRowId={(a) => a.id} {...TABLE}
            emptyMessage={isError ? tx('Could not load the agents. Try again.') : tx('No agents yet. Add one for each branch with cameras.')} />
        </div>
      </ListCard>
      {adding && <NewAgentDialog onClose={() => setAdding(false)} />}
      {openId && <AgentDialog id={openId} onClose={() => setOpenId(null)} />}
    </>
  );
}

/** The one-time enrolment code; it is not shown again after this window closes. */
function EnrolmentCode({ code, expiresAt }: { code: string; expiresAt: string }) {
  const server = new URL(getApiBaseUrl(), window.location.origin).toString().replace(/\/$/, '');
  const copy = () => {
    if (!navigator.clipboard) {
      notify.error(tx('Copying is not available here; select the code instead.'));
      return;
    }
    navigator.clipboard.writeText(code).then(() => notify.success(tx('Copied')), () => notify.error(tx('Could not copy')));
  };
  return (
    <div className="space-y-3 rounded-md border bg-muted/40 p-3">
      <p className="text-sm">{tx('Enter this code on the edge box when it asks for one. It works once.')}</p>
      <div className="flex flex-wrap items-center gap-2">
        <code className="select-all rounded bg-background px-3 py-2 font-mono text-lg tracking-widest">{code}</code>
        <Button size="sm" variant="outline" onClick={copy}><Copy className="mr-1.5 h-4 w-4" />{tx('Copy')}</Button>
      </div>
      <p className="text-xs text-muted-foreground">
        {tx('Expires at')} {dateTime(expiresAt)}. {tx('It is not shown again; issue a new one from the agent if it expires.')}
      </p>
      <p className="text-xs text-muted-foreground">
        {tx('Server address for the edge box')}: <span className="select-all font-mono">{server}</span>
      </p>
    </div>
  );
}

function NewAgentDialog({ onClose }: { onClose: () => void }) {
  const can = useAuthStore((s) => s.hasPermission);
  const canBranches = can(PERMISSIONS.branch.read);
  const branches = useLpBranchChoices(canBranches);
  const create = useCreateLpAgent();
  const [branchId, setBranchId] = useState('');
  const [name, setName] = useState('');
  const [created, setCreated] = useState<EnrolledAgent | null>(null);
  const onCreate = () => create.mutate({ branchId: branchId.trim(), name: name.trim() }, {
    onSuccess: (a) => setCreated(a),
    onError: (e) => notify.error(apiMessage(e, tx('Could not add the agent'))),
  });
  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{created ? created.name : tx('Add an edge agent')}</DialogTitle>
          <DialogDescription>
            {created ? tx('The agent is added. Enrol the edge box with this code.')
              : tx('One agent per branch. It sends observations for its branch only.')}
          </DialogDescription>
        </DialogHeader>
        {created ? <EnrolmentCode code={created.enrolmentCode} expiresAt={created.enrolmentCodeExpiresAt} /> : (
          <div className="space-y-3">
            <div className="space-y-1">
              <Label>{tx('Branch')}</Label>
              {canBranches && !branches.isError ? (
                <select className={SELECT} value={branchId} disabled={branches.isLoading} onChange={(e) => setBranchId(e.target.value)}>
                  <option value="">{branches.isLoading ? tx('Loading…') : tx('Choose…')}</option>
                  {(branches.data ?? []).map((b) => (
                    <option key={b.id} value={b.id}>{b.code ? `${b.code} · ${b.name}` : b.name}</option>
                  ))}
                </select>
              ) : (
                <Input value={branchId} onChange={(e) => setBranchId(e.target.value)} placeholder={tx('Branch id')} />
              )}
            </div>
            <div className="space-y-1">
              <Label>{tx('Name')}</Label>
              <Input value={name} maxLength={100} onChange={(e) => setName(e.target.value)} placeholder={tx('e.g. Main street edge box')} />
            </div>
          </div>
        )}
        <DialogFooter>
          {created ? <Button onClick={onClose}>{tx('Done')}</Button> : (
            <>
              <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
              <Button disabled={!branchId.trim() || !name.trim() || create.isPending} onClick={onCreate}>{tx('Add agent')}</Button>
            </>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

const withCurrent = (versions: string[], current: string) =>
  (current && !versions.includes(current) ? [current, ...versions] : versions);

function AgentSettingsForm({ agent, models }: { agent: LpAgent; models: LpModels | undefined }) {
  const update = useUpdateLpAgent();
  const [name, setName] = useState(agent.name);
  const [model, setModel] = useState(agent.desiredModelVersion ?? '');
  const [release, setRelease] = useState(agent.desiredAgentVersion ?? '');
  const modelVersions = withCurrent((models?.models ?? []).map((m) => m.version), model);
  const releaseVersions = withCurrent((models?.releases ?? []).map((r) => r.version), release);
  const changed = name.trim() !== agent.name || model !== (agent.desiredModelVersion ?? '')
    || release !== (agent.desiredAgentVersion ?? '');
  const onSave = () => update.mutate({
    id: agent.id,
    body: { name: name.trim(), desiredModelVersion: model || null, desiredAgentVersion: release || null },
  }, {
    onSuccess: () => notify.success(tx('Saved')),
    onError: (e) => notify.error(apiMessage(e, tx('Could not save'))),
  });
  return (
    <div className="grid gap-2 border-t pt-3 sm:grid-cols-3">
      <div className="space-y-1">
        <Label>{tx('Name')}</Label>
        <Input value={name} maxLength={100} onChange={(e) => setName(e.target.value)} />
      </div>
      <div className="space-y-1">
        <Label>{tx('Detector model to install')}</Label>
        <select className={SELECT} value={model} onChange={(e) => setModel(e.target.value)}>
          <option value="">{tx('Keep what it has')}</option>
          {modelVersions.map((v) => <option key={v} value={v}>{v}</option>)}
        </select>
      </div>
      <div className="space-y-1">
        <Label>{tx('Agent release to install')}</Label>
        <select className={SELECT} value={release} onChange={(e) => setRelease(e.target.value)}>
          <option value="">{tx('Keep what it has')}</option>
          {releaseVersions.map((v) => <option key={v} value={v}>{v}</option>)}
        </select>
      </div>
      <p className="text-xs text-muted-foreground sm:col-span-2">
        {tx('The agent installs a model or release only when its signature checks out.')}
      </p>
      <div className="flex justify-end">
        <Button size="sm" disabled={!name.trim() || !changed || update.isPending} onClick={onSave}>{tx('Save')}</Button>
      </div>
    </div>
  );
}

function AgentDialog({ id, onClose }: { id: string; onClose: () => void }) {
  const { data: a, isLoading, isError } = useLpAgent(id);
  const models = useLpModels();
  const newCode = useNewLpEnrolmentCode();
  const revoke = useRevokeLpAgent();
  const [code, setCode] = useState<EnrolledAgent | null>(null);
  const [reason, setReason] = useState('');
  const [confirmRevoke, setConfirmRevoke] = useState(false);
  const revoked = a?.status === 'revoked';
  const free = toNumber(a?.diskFreeBytes);
  const total = toNumber(a?.diskTotalBytes);
  const drift = toNumber(a?.clockDriftMs);
  const settingsBehind = !!a && a.configVersion !== null && a.reportedConfigVersion !== a.configVersion;
  const onNewCode = () => newCode.mutate(id, {
    onSuccess: (r) => setCode(r),
    onError: (e) => notify.error(apiMessage(e, tx('Could not issue a code'))),
  });
  const onRevoke = () => revoke.mutate({ id, reason: reason.trim() || undefined }, {
    onSuccess: () => { notify.success(tx('Agent revoked')); setConfirmRevoke(false); },
    onError: (e) => notify.error(apiMessage(e, tx('Could not revoke the agent'))),
  });
  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent className="max-h-[90vh] max-w-3xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{a ? a.name : tx('Agent')}</DialogTitle>
          <DialogDescription>{a ? `${a.branchName ?? a.branchId} · ${labelOf(AGENT_STATUS_LABELS, a.status)}` : ''}</DialogDescription>
        </DialogHeader>
        {isLoading && <p className="text-muted-foreground">{tx('Loading…')}</p>}
        {isError && <p className="text-destructive">{tx('Could not load the agent.')}</p>}
        {a && (
          <div className="space-y-4">
            <div className="flex flex-wrap items-center gap-2">
              {!revoked && <HealthBadge health={a.health} reasons={a.healthReasons} />}
              {!revoked && settingsBehind && <Badge variant="secondary">{tx('Settings not yet picked up by the agent')}</Badge>}
              {a.status === 'pending' && a.enrolCodeExpiresAt && (
                <span className="text-xs text-muted-foreground">{tx('Enrolment code expires at')} {dateTime(a.enrolCodeExpiresAt)}</span>
              )}
            </div>
            <dl className="grid gap-x-6 text-sm sm:grid-cols-2">
              <Fact label={tx('Host')} value={a.hostname} />
              <Fact label={tx('Enrolled')} value={a.enrolledAt ? dateTime(a.enrolledAt) : null} />
              <Fact label={tx('Last heard')} value={a.lastHeartbeatAt ? dateTime(a.lastHeartbeatAt) : null} />
              <Fact label={tx('Observations complete up to')} value={a.observedThrough ? dateTime(a.observedThrough) : null} />
              <Fact label={tx('Clock difference')} value={drift !== null ? `${drift} ms` : null} />
              <Fact label={tx('Disk free')} value={free !== null ? `${formatBytes(free)}${total ? ` / ${formatBytes(total)}` : ''}` : null} />
              <Fact label={tx('Observations waiting to be sent')} value={toNumber(a.queueLength)} />
              <Fact label={tx('Settings version (agent / here)')} value={a.configVersion !== null ? `${a.reportedConfigVersion ?? '—'} / ${a.configVersion}` : null} />
              <Fact label={tx('Agent release')} value={versionText(a.agentVersion, a.desiredAgentVersion)} />
              <Fact label={tx('Detector model')} value={versionText(a.modelVersion, a.desiredModelVersion)} />
            </dl>
            {revoked && (
              <p className="text-sm text-muted-foreground">
                {tx('Revoked')} {dateTime(a.revokedAt)}{a.revokeReason ? ` — ${a.revokeReason}` : ''}
              </p>
            )}
            {!revoked && <AgentSettingsForm key={a.id} agent={a} models={models.data} />}
            {!revoked && (
              <div className="space-y-2 border-t pt-3">
                <p className="text-sm font-medium">{tx('Enrolment')}</p>
                <p className="text-xs text-muted-foreground">
                  {tx('A new code lets an edge box enrol again, for example after it was replaced. Enrolling with it replaces the credentials of the agent.')}
                </p>
                {code ? <EnrolmentCode code={code.enrolmentCode} expiresAt={code.enrolmentCodeExpiresAt} /> : (
                  <Button size="sm" variant="outline" disabled={newCode.isPending} onClick={onNewCode}>
                    {tx('Issue a new enrolment code')}
                  </Button>
                )}
              </div>
            )}
            <div>
              <p className="mb-1 text-sm font-medium">{tx('Cameras')} ({(a.cameras ?? []).length})</p>
              <div className="space-y-1 rounded-md border p-2 text-xs">
                {(a.cameras ?? []).length === 0 && <p className="text-muted-foreground">{tx('No cameras on this agent yet.')}</p>}
                {(a.cameras ?? []).map((c) => (
                  <div key={c.id} className="flex flex-wrap items-center justify-between gap-2">
                    <span><span className="font-medium">{c.name}</span> <span className="font-mono text-muted-foreground">{c.streamAlias}</span></span>
                    <span className="flex items-center gap-2">
                      {toNumber(c.fps) !== null && <span className="text-muted-foreground">{toNumber(c.fps)} fps</span>}
                      <StreamBadge state={c.enabled ? c.streamState : 'disabled'} />
                    </span>
                  </div>
                ))}
              </div>
            </div>
            <div>
              <p className="mb-1 text-sm font-medium">{tx('Heartbeats')} ({(a.heartbeats ?? []).length})</p>
              <div className="max-h-48 overflow-y-auto rounded-md border text-xs">
                <table className="w-full">
                  <thead className="sticky top-0 bg-muted text-left">
                    <tr>
                      <th className="px-2 py-1 font-medium">{tx('Received')}</th>
                      <th className="px-2 py-1 font-medium">{tx('Health')}</th>
                      <th className="px-2 py-1 text-right font-medium">{tx('Waiting to send')}</th>
                      <th className="px-2 py-1 text-right font-medium">{tx('Clock difference')}</th>
                      <th className="px-2 py-1 text-right font-medium">{tx('Disk free')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {(a.heartbeats ?? []).map((h) => (
                      <tr key={h.id} className="border-t">
                        <td className="px-2 py-1">{dateTime(h.receivedAt)}</td>
                        <td className="px-2 py-1">
                          {labelOf(HEALTH_LABELS, h.health)}
                          {h.healthReasons?.length ? ` · ${h.healthReasons.map((r) => labelOf(HEALTH_REASON_LABELS, r)).join(', ')}` : ''}
                        </td>
                        <td className="px-2 py-1 text-right">{toNumber(h.queueLength) ?? '—'}</td>
                        <td className="px-2 py-1 text-right">{toNumber(h.clockDriftMs) !== null ? `${toNumber(h.clockDriftMs)} ms` : '—'}</td>
                        <td className="px-2 py-1 text-right">{formatBytes(toNumber(h.diskFreeBytes))}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                {(a.heartbeats ?? []).length === 0 && <p className="p-2 text-muted-foreground">{tx('No heartbeat yet.')}</p>}
              </div>
            </div>
            <div>
              <p className="mb-1 text-sm font-medium">{tx('History')}</p>
              <div className="max-h-48 space-y-1 overflow-y-auto rounded-md border p-2 text-xs">
                {(a.timeline ?? []).map((t) => (
                  <div key={t.id}>
                    <span className="text-muted-foreground">{dateTime(t.createdAt)} · {t.actorName ?? (t.actorId ? tx('A user') : tx('System'))} · </span>
                    <span className="font-medium">{labelOf(AGENT_EVENT_LABELS, t.kind)}</span>{eventNote(t)}
                  </div>
                ))}
              </div>
            </div>
            <AgentCommands id={id} />
            {!revoked && (
              <div className="space-y-2 border-t pt-3">
                <p className="text-sm font-medium">{tx('Revoke')}</p>
                <p className="text-xs text-muted-foreground">
                  {tx('Its credentials stop working at once: the edge box can no longer send observations or clips.')}
                </p>
                <div className="flex flex-wrap gap-2">
                  <Input className="max-w-sm" value={reason} maxLength={500} onChange={(e) => setReason(e.target.value)}
                    placeholder={tx('Reason (optional)')} />
                  {!confirmRevoke ? (
                    <Button variant="outline" onClick={() => setConfirmRevoke(true)}>{tx('Revoke agent')}</Button>
                  ) : (
                    <>
                      <Button variant="outline" onClick={() => setConfirmRevoke(false)}>{tx('Cancel')}</Button>
                      <Button variant="destructive" disabled={revoke.isPending} onClick={onRevoke}>{tx('Confirm: revoke')}</Button>
                    </>
                  )}
                </div>
              </div>
            )}
          </div>
        )}
        <DialogFooter><Button variant="outline" onClick={onClose}>{tx('Close window')}</Button></DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function CamerasSection({ canEvidence }: { canEvidence: boolean }) {
  const agents = useLpAgents();
  const [agentId, setAgentId] = useState('');
  const { data, isLoading, isError } = useLpCameras({ agentId: agentId || undefined });
  const [editing, setEditing] = useState<LpCamera | 'new' | null>(null);
  const [zonesOf, setZonesOf] = useState<string | null>(null);
  const [live, setLive] = useState<LpCamera | null>(null);
  const usable = (agents.data ?? []).filter((a) => a.status !== 'revoked');
  const agentName = (id: string) => agents.data?.find((a) => a.id === id)?.name ?? '—';
  const columns: Column<LpCamera>[] = [
    {
      key: 'name', header: tx('Camera'), render: (c) => (
        <div>
          <button type="button" className="text-left font-medium hover:underline" onClick={() => setZonesOf(c.id)}>{c.name}</button>
          <p className="font-mono text-xs text-muted-foreground">{c.streamAlias}</p>
        </div>
      ),
    },
    { key: 'agentName', header: tx('Agent'), render: (c) => c.agentName ?? agentName(c.agentId) },
    {
      key: 'streamState', header: tx('Picture'), render: (c) => (
        <div className="flex items-center gap-2">
          <StreamBadge state={c.enabled ? c.streamState : 'disabled'} />
          {toNumber(c.fps) !== null && <span className="text-xs text-muted-foreground">{toNumber(c.fps)} fps</span>}
        </div>
      ),
    },
    {
      key: 'lastFrameAt', header: tx('Last picture'),
      render: (c) => (c.lastFrameAt ? <span title={dateTime(c.lastFrameAt)}>{relativeTime(c.lastFrameAt)}</span> : '—'),
    },
    { key: 'analysed', header: tx('Detection'), render: (c) => (c.analysed ? tx('On') : tx('Off')) },
    { key: 'zoneCount', header: tx('Zones'), className: 'text-right', render: (c) => c.zoneCount ?? '—' },
    {
      key: 'actions', header: '', render: (c) => (
        <div className="flex justify-end gap-1">
          <Button size="sm" variant="outline" onClick={() => setZonesOf(c.id)}>{tx('Zones')}</Button>
          <Button size="sm" variant="outline" onClick={() => setEditing(c)}>{tx('Edit')}</Button>
          {canEvidence && (
            <Button size="sm" variant="outline" disabled={!c.enabled} onClick={() => setLive(c)}>
              <Play className="mr-1 h-3.5 w-3.5" />{tx('Live')}
            </Button>
          )}
        </div>
      ),
    },
  ];
  return (
    <>
      <div className="flex flex-wrap items-center justify-between gap-2 pt-2">
        <p className="font-medium">{tx('Cameras')}</p>
      </div>
      <ListToolbar>
        <FilterSelect value={agentId} onChange={setAgentId} allLabel={tx('All agents')}
          options={(agents.data ?? []).map((a) => ({ value: a.id, label: a.name }))} />
        <Button size="sm" disabled={usable.length === 0} onClick={() => setEditing('new')}
          title={usable.length === 0 ? tx('Add an agent first') : undefined}>
          <Plus className="mr-1.5 h-4 w-4" />{tx('Add a camera')}
        </Button>
      </ListToolbar>
      <ListCard>
        <div className="overflow-x-auto">
          <DataTable columns={columns} data={data ?? []} loading={isLoading} loadingRows={3} getRowId={(c) => c.id} {...TABLE}
            emptyMessage={isError ? tx('Could not load the cameras. Try again.') : tx('No cameras here.')} />
        </div>
      </ListCard>
      {editing && (
        <CameraDialog camera={editing === 'new' ? null : editing} agents={usable} onClose={() => setEditing(null)}
          onCreated={(c) => { setEditing(null); setZonesOf(c.id); }} />
      )}
      {zonesOf && <CameraZonesDialog cameraId={zonesOf} canEvidence={canEvidence} onClose={() => setZonesOf(null)} />}
      {live && <LiveViewDialog camera={live} onClose={() => setLive(null)} />}
    </>
  );
}

function CameraDialog({ camera, agents, onClose, onCreated }: {
  camera: LpCamera | null; agents: LpAgent[]; onClose: () => void; onCreated: (c: LpCamera) => void;
}) {
  const [agentId, setAgentId] = useState(camera?.agentId ?? (agents.length === 1 ? agents[0].id : ''));
  const [name, setName] = useState(camera?.name ?? '');
  const [alias, setAlias] = useState(camera?.streamAlias ?? '');
  const [enabled, setEnabled] = useState(camera?.enabled ?? true);
  const [analysed, setAnalysed] = useState(camera?.analysed ?? true);
  const [notes, setNotes] = useState(camera?.notes ?? '');
  const create = useCreateLpCamera();
  const update = useUpdateLpCamera();
  const remove = useDeleteLpCamera();
  const [confirmDelete, setConfirmDelete] = useState(false);
  const onDelete = () => camera && remove.mutate(camera.id, {
    onSuccess: () => { notify.success(tx('Camera deleted')); onClose(); },
    onError: (e) => notify.error(apiMessage(e, tx('Could not delete the camera'))),
  });
  const aliasProblem = !alias ? null
    : /:\/\/|@|:/.test(alias) ? tx('Enter only the alias here. The stream address and its password stay on the edge box.')
      : !STREAM_ALIAS_RE.test(alias) ? tx('Use lower-case letters, digits, - and _, up to 64, starting with a letter or a digit.')
        : null;
  const invalid = (!camera && !agentId) || !name.trim() || !alias || !!aliasProblem;
  const fail = (e: unknown) => notify.error(apiMessage(e, tx('Could not save the camera')));
  const onSave = () => {
    const body = { name: name.trim(), streamAlias: alias, enabled, analysed, notes: notes.trim() || null };
    if (camera) {
      update.mutate({ id: camera.id, body }, { onSuccess: () => { notify.success(tx('Saved')); onClose(); }, onError: fail });
    } else {
      create.mutate({ ...body, agentId }, {
        onSuccess: (c) => { notify.success(tx('Camera added. Now mark its zones.')); onCreated(c); },
        onError: fail,
      });
    }
  };
  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{camera ? camera.name : tx('Add a camera')}</DialogTitle>
          <DialogDescription>
            {tx('On the edge box, enter the stream address and its password under the alias given here. They never reach this server.')}
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-3">
          <div className="space-y-1">
            <Label>{tx('Agent')}</Label>
            {camera ? <p className="text-sm">{camera.agentName ?? agents.find((a) => a.id === camera.agentId)?.name ?? camera.agentId}</p> : (
              <select className={SELECT} value={agentId} onChange={(e) => setAgentId(e.target.value)}>
                <option value="">{tx('Choose…')}</option>
                {agents.map((a) => <option key={a.id} value={a.id}>{a.branchName ? `${a.name} · ${a.branchName}` : a.name}</option>)}
              </select>
            )}
          </div>
          <div className="space-y-1">
            <Label>{tx('Name')}</Label>
            <Input value={name} maxLength={100} onChange={(e) => setName(e.target.value)} placeholder={tx('e.g. Till 1 overhead')} />
          </div>
          <div className="space-y-1">
            <Label>{tx('Stream alias')}</Label>
            <Input value={alias} maxLength={64} className="font-mono" onChange={(e) => setAlias(e.target.value.trim())}
              placeholder="till-1" />
            {aliasProblem && <p className="text-xs text-destructive">{aliasProblem}</p>}
          </div>
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
            {tx('The agent reads this camera')}
          </label>
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={analysed} onChange={(e) => setAnalysed(e.target.checked)} />
            {tx('Look for events in its picture (off: video is kept, nothing is detected)')}
          </label>
          <div className="space-y-1">
            <Label>{tx('Notes')}</Label>
            <Textarea value={notes} maxLength={1000} rows={2} onChange={(e) => setNotes(e.target.value)} />
          </div>
        </div>
        {camera && (
          <div className="space-y-2 border-t pt-3">
            <p className="text-xs text-muted-foreground">
              {tx('Deleting retires its zones and frees the alias. Its clips and past observations are kept. To pause a camera, switch it off instead.')}
            </p>
            {!confirmDelete ? (
              <Button variant="outline" size="sm" onClick={() => setConfirmDelete(true)}>{tx('Delete camera')}</Button>
            ) : (
              <div className="flex flex-wrap gap-2">
                <Button variant="destructive" size="sm" disabled={remove.isPending} onClick={onDelete}>
                  {tx('Yes, delete it')}
                </Button>
                <Button variant="outline" size="sm" onClick={() => setConfirmDelete(false)}>{tx('Keep it')}</Button>
              </div>
            )}
          </div>
        )}
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={invalid || create.isPending || update.isPending} onClick={onSave}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** Camera and evidence settings, for the organization or one branch. A save replaces the map of that scope, so the
 * settings this card does not show are sent back as they are. */
function VisionSettingsCard() {
  const can = useAuthStore((s) => s.hasPermission);
  const { data, isLoading, isError } = useLpSettings();
  const agents = useLpAgents();
  const branchList = useLpBranchChoices(can(PERMISSIONS.branch.read));
  const save = useSaveLpSettings();
  const [scope, setScope] = useState('');
  const [values, setValues] = useState<Record<string, string>>({});
  useEffect(() => {
    if (!data) return;
    const stored: Record<string, unknown> = (scope ? data.branches?.[scope] : data.organization) ?? {};
    setValues(Object.fromEntries(VISION_SETTINGS.map((k) => [k, stored[k] === undefined || stored[k] === null ? '' : String(stored[k])])));
  }, [data, scope]);
  const scopes = new Map<string, string>();
  for (const b of branchList.data ?? []) scopes.set(b.id, b.name);
  for (const a of agents.data ?? []) if (!scopes.has(a.branchId)) scopes.set(a.branchId, a.branchName ?? a.branchId);
  for (const id of Object.keys(data?.branches ?? {})) if (id && !scopes.has(id)) scopes.set(id, id);
  const supported = VISION_SETTINGS.filter((k) => data?.defaults?.[k] !== undefined);
  const inherited = (k: string) => (scope ? data?.organization?.[k] ?? data?.defaults?.[k] : data?.defaults?.[k]);
  const invalid = supported.some((k) => {
    const v = (values[k] ?? '').trim();
    return v !== '' && !/^\d+$/.test(v);
  });
  const onSave = () => {
    if (!data) return;
    const base: Record<string, number> = { ...((scope ? data.branches?.[scope] : data.organization) ?? {}) };
    for (const k of supported) {
      const v = (values[k] ?? '').trim();
      if (v === '') delete base[k];
      else base[k] = Number(v);
    }
    save.mutate({ branchId: scope || undefined, values: base }, {
      onSuccess: () => notify.success(tx('Saved')),
      onError: (e) => notify.error(apiMessage(e, tx('Could not save'))),
    });
  };
  return (
    <Card>
      <CardContent className="space-y-3 p-4">
        <div className="flex flex-wrap items-start justify-between gap-2">
          <div>
            <p className="font-medium">{tx('Camera and evidence settings')}</p>
            <p className="text-xs text-muted-foreground">
              {tx('An empty field uses the value shown in it, from the organization or the default. Other loss prevention settings are kept as they are.')}
            </p>
          </div>
          <select className={`${SELECT} max-w-xs`} value={scope} onChange={(e) => setScope(e.target.value)}>
            <option value="">{tx('Whole organization')}</option>
            {[...scopes].map(([id, name]) => <option key={id} value={id}>{`${tx('Branch')}: ${name}`}</option>)}
          </select>
        </div>
        {isLoading && <p className="text-sm text-muted-foreground">{tx('Loading…')}</p>}
        {isError && <p className="text-sm text-destructive">{tx('Could not load the settings.')}</p>}
        {data && supported.length === 0 && (
          <p className="text-sm text-muted-foreground">{tx('This server does not offer the camera settings yet.')}</p>
        )}
        {data && supported.length > 0 && (
          <>
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
              {supported.map((k) => (
                <div key={k} className="space-y-1">
                  <Label className="text-xs">{SETTING_LABELS[k]?.label ?? k}</Label>
                  <div className="flex items-center gap-2">
                    <Input type="number" min={0} step={1} value={values[k] ?? ''} placeholder={String(inherited(k) ?? '')}
                      onChange={(e) => setValues((v) => ({ ...v, [k]: e.target.value }))} />
                    <span className="text-xs text-muted-foreground">{SETTING_LABELS[k]?.unit}</span>
                  </div>
                </div>
              ))}
            </div>
            <div className="flex items-center justify-end gap-2">
              {invalid && <p className="text-xs text-destructive">{tx('Use whole numbers of 0 or more.')}</p>}
              <Button size="sm" disabled={invalid || save.isPending} onClick={onSave}>{tx('Save settings')}</Button>
            </div>
          </>
        )}
      </CardContent>
    </Card>
  );
}

/** What was sent to the box and what it answered: a still, a calibration, an upload or live view that did not happen. */
function AgentCommands({ id }: { id: string }) {
  const { data, isLoading } = useLpAgentCommands(id);
  const rows = data ?? [];
  return (
    <div>
      <p className="mb-1 text-sm font-medium">{tx('Requests to the box')}</p>
      <div className="max-h-48 overflow-y-auto rounded-md border text-xs">
        <table className="w-full">
          <thead className="sticky top-0 bg-muted text-left">
            <tr>
              <th className="px-2 py-1 font-medium">{tx('Sent')}</th>
              <th className="px-2 py-1 font-medium">{tx('Request')}</th>
              <th className="px-2 py-1 font-medium">{tx('Status')}</th>
              <th className="px-2 py-1 font-medium">{tx('Answer')}</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((c) => (
              <tr key={c.id} className="border-t">
                <td className="px-2 py-1">{dateTime(c.createdAt)}</td>
                <td className="px-2 py-1">{labelOf(COMMAND_KIND_LABELS, c.kind)}</td>
                <td className="px-2 py-1">
                  <Badge variant={c.state === 'failed' || c.state === 'expired' ? 'destructive' : c.state === 'done' ? 'secondary' : 'outline'}>
                    {labelOf(COMMAND_STATE_LABELS, c.state)}
                  </Badge>
                </td>
                <td className="px-2 py-1 text-muted-foreground">{c.result?.message ?? (c.completedAt ? dateTime(c.completedAt) : '—')}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {!isLoading && rows.length === 0 && <p className="p-2 text-muted-foreground">{tx('Nothing sent yet.')}</p>}
      </div>
    </div>
  );
}
