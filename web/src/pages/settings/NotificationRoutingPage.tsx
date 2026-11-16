import { useMemo, useState } from 'react';
import { BellRing } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { ListPageHeader } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { apiMessage, notify } from '@/lib/notify';
import { useRoles, useUsers } from '@/features/staff/api';
import {
  useResetRoute, useRoutedEvents, useRoutingActivity, useSaveRoute, type RoutedEventRoute,
} from '@/features/notification-routing/api';

const CHANNELS = ['in_app', 'email', 'push', 'sms'] as const;

const channelLabel = (c: string) =>
  c === 'in_app' ? tx('In-app') : c === 'email' ? tx('Email') : c === 'push' ? tx('Push') : tx('SMS');

const AREA_LABELS: Record<string, string> = {
  sales: 'Sales & invoicing',
  purchasing: 'Purchasing',
  inventory: 'Inventory',
  projects: 'Tasks & projects',
  helpdesk: 'Helpdesk',
  hr: 'HR',
  fleet: 'Fleet',
  maintenance: 'Maintenance & assets',
  production: 'Production & engineering',
  quality: 'Quality',
};
const areaLabel = (a: string | null) => (a ? tx(AREA_LABELS[a] ?? a) : '');

const toggle = (list: string[], v: string) => (list.includes(v) ? list.filter((x) => x !== v) : [...list, v]);

/** Who is told when something happens: per event, the record's responsible people, roles, users, channels. */
export function NotificationRoutingPage() {
  const can = useAuthStore((s) => s.hasPermission);
  const manage = can(PERMISSIONS.notificationRouting.manage);
  const [tab, setTab] = useState('events');
  const [search, setSearch] = useState('');
  const [editing, setEditing] = useState<RoutedEventRoute | null>(null);
  const events = useRoutedEvents();
  const activity = useRoutingActivity(tab === 'activity');
  const roles = useRoles();

  const grouped = useMemo(() => {
    const q = search.trim().toLowerCase();
    const out = new Map<string, RoutedEventRoute[]>();
    for (const e of events.data ?? []) {
      if (q && !e.label.toLowerCase().includes(q) && !e.event.includes(q)) continue;
      out.set(e.area, [...(out.get(e.area) ?? []), e]);
    }
    return out;
  }, [events.data, search]);

  const roleName = (id: string) => roles.data?.find((r) => r.id === id)?.name ?? id;
  const who = (e: RoutedEventRoute) => [
    ...e.selectedParties.map((k) => e.parties.find((p) => p.key === k)?.label ?? k),
    ...e.roleIds.map((r) => `${tx('Role')} ${roleName(r)}`),
    ...(e.userIds.length ? [`${e.userIds.length} ${tx('named user(s)')}`] : []),
  ];

  return (
    <div className="space-y-4">
      <ListPageHeader
        icon={BellRing}
        title={tx('Notification routing')}
        description={tx('Who is told when something happens. By default the people responsible for the record are told in-app; the person who did it is never told.')}
      />
      <Tabs value={tab} onValueChange={setTab}>
        <TabsList>
          <TabsTrigger value="events">{tx('Events')}</TabsTrigger>
          <TabsTrigger value="activity">{tx('Recent activity')}</TabsTrigger>
        </TabsList>
        <TabsContent value="events" className="space-y-4">
          <Input className="max-w-sm" placeholder={tx('Search events')} value={search}
            onChange={(e) => setSearch(e.target.value)} />
          {events.isLoading && <p className="text-sm text-muted-foreground">{tx('Loading...')}</p>}
          {[...grouped.entries()].map(([area, list]) => (
            <Card key={area}>
              <CardContent className="p-0">
                <div className="border-b px-4 py-2 text-sm font-semibold">{areaLabel(area)}</div>
                <ul className="divide-y">
                  {list.map((e) => (
                    <li key={e.event} className="flex flex-wrap items-center gap-3 px-4 py-2 text-sm">
                      <div className="min-w-[14rem] flex-1">
                        <div className="font-medium">{tx(e.label)}</div>
                        <div className="text-xs text-muted-foreground">
                          {e.enabled ? (who(e).join(' · ') || tx('Nobody')) : tx('Off')}
                        </div>
                      </div>
                      <div className="flex flex-wrap gap-1">
                        {e.enabled && e.channels.map((c) => <Badge key={c} variant="secondary">{channelLabel(c)}</Badge>)}
                        {!e.enabled && <Badge variant="outline">{tx('Off')}</Badge>}
                        {e.customized && <Badge variant="default">{tx('Customized')}</Badge>}
                      </div>
                      {manage && <Button size="sm" variant="outline" onClick={() => setEditing(e)}>{tx('Edit')}</Button>}
                    </li>
                  ))}
                </ul>
              </CardContent>
            </Card>
          ))}
        </TabsContent>
        <TabsContent value="activity">
          <Card>
            <CardContent className="p-0">
              <table className="w-full text-sm">
                <thead className="border-b text-left text-xs text-muted-foreground">
                  <tr>
                    <th className="px-4 py-2">{tx('When')}</th>
                    <th className="px-4 py-2">{tx('Area')}</th>
                    <th className="px-4 py-2">{tx('Event')}</th>
                    <th className="px-4 py-2 text-right">{tx('People told')}</th>
                  </tr>
                </thead>
                <tbody className="divide-y">
                  {(activity.data ?? []).map((a, i) => (
                    <tr key={`${a.claimedAt}-${i}`}>
                      <td className="px-4 py-2 whitespace-nowrap">{dateTime(a.claimedAt)}</td>
                      <td className="px-4 py-2">{areaLabel(a.area)}</td>
                      <td className="px-4 py-2">{tx(a.label)}</td>
                      <td className="px-4 py-2 text-right">{a.recipients}</td>
                    </tr>
                  ))}
                  {activity.data?.length === 0 && (
                    <tr><td colSpan={4} className="px-4 py-6 text-center text-muted-foreground">{tx('Nothing routed yet')}</td></tr>
                  )}
                </tbody>
              </table>
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>
      {editing && <RouteDialog route={editing} onClose={() => setEditing(null)} />}
    </div>
  );
}

function RouteDialog({ route, onClose }: { route: RoutedEventRoute; onClose: () => void }) {
  const [enabled, setEnabled] = useState(route.enabled);
  const [parties, setParties] = useState<string[]>(route.selectedParties);
  const [roleIds, setRoleIds] = useState<string[]>(route.roleIds);
  const [userIds, setUserIds] = useState<string[]>(route.userIds);
  const [channels, setChannels] = useState<string[]>(route.channels);
  const [userSearch, setUserSearch] = useState('');
  const roles = useRoles();
  const users = useUsers({ page: 1, pageSize: 100, search: userSearch || undefined });
  const save = useSaveRoute();
  const reset = useResetRoute();

  const onSave = () => save.mutate({ event: route.event, body: { enabled, parties, roleIds, userIds, channels } }, {
    onSuccess: () => { notify.success(tx('Saved')); onClose(); },
    onError: (e) => notify.error(apiMessage(e, tx('Could not save'))),
  });
  const onReset = () => reset.mutate(route.event, {
    onSuccess: () => { notify.success(tx('Back to the defaults')); onClose(); },
    onError: (e) => notify.error(apiMessage(e, tx('Could not save'))),
  });

  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent className="max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{tx(route.label)}</DialogTitle>
          <DialogDescription>{route.event}</DialogDescription>
        </DialogHeader>
        <div className="space-y-4">
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
            {tx('Tell people when this happens')}
          </label>
          <div className="space-y-1">
            <Label>{tx('People on the record')}</Label>
            <div className="grid gap-1 sm:grid-cols-2">
              {route.parties.map((p) => (
                <label key={p.key} className="flex items-center gap-2 text-sm">
                  <input type="checkbox" checked={parties.includes(p.key)} onChange={() => setParties(toggle(parties, p.key))} />
                  {tx(p.label)}
                  {route.defaultParties.includes(p.key) && <span className="text-xs text-muted-foreground">({tx('default')})</span>}
                </label>
              ))}
            </div>
          </div>
          <div className="space-y-1">
            <Label>{tx('Everyone in these roles')}</Label>
            <div className="grid max-h-32 gap-1 overflow-y-auto sm:grid-cols-2">
              {(roles.data ?? []).map((r) => (
                <label key={r.id} className="flex items-center gap-2 text-sm">
                  <input type="checkbox" checked={roleIds.includes(r.id)} onChange={() => setRoleIds(toggle(roleIds, r.id))} />
                  {r.name}
                </label>
              ))}
            </div>
          </div>
          <div className="space-y-1">
            <Label>{tx('Also these users')}</Label>
            <Input placeholder={tx('Search users')} value={userSearch} onChange={(e) => setUserSearch(e.target.value)} />
            <div className="grid max-h-32 gap-1 overflow-y-auto sm:grid-cols-2">
              {(users.data?.data ?? []).filter((u) => u.isActive || userIds.includes(u.id)).map((u) => (
                <label key={u.id} className="flex items-center gap-2 text-sm">
                  <input type="checkbox" checked={userIds.includes(u.id)} onChange={() => setUserIds(toggle(userIds, u.id))} />
                  {[u.firstName, u.lastName].filter(Boolean).join(' ')} <span className="text-xs text-muted-foreground">{u.email}</span>
                </label>
              ))}
            </div>
            {userIds.length > 0 && <p className="text-xs text-muted-foreground">{userIds.length} {tx('selected')}</p>}
          </div>
          <div className="space-y-1">
            <Label>{tx('Channels')}</Label>
            <div className="flex flex-wrap gap-4">
              {CHANNELS.map((c) => (
                <label key={c} className="flex items-center gap-2 text-sm">
                  <input type="checkbox" checked={channels.includes(c)} onChange={() => setChannels(toggle(channels, c))} />
                  {channelLabel(c)}
                </label>
              ))}
            </div>
            <p className="text-xs text-muted-foreground">{tx('Each person can still turn a category off in their own notification preferences.')}</p>
          </div>
        </div>
        <DialogFooter className="gap-2">
          {route.customized && (
            <Button variant="ghost" disabled={reset.isPending} onClick={onReset}>{tx('Reset to defaults')}</Button>
          )}
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={save.isPending || (enabled && channels.length === 0)} onClick={onSave}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
