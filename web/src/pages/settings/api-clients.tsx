import { useState } from 'react';
import { PERMISSIONS } from '@erp/shared';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import {
  useApiClients,
  useApiScopes,
  useCreateApiClient,
  useRevokeApiClient,
  useRotateApiClient,
} from '@/features/customization/api';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';

/** API clients for the OAuth 2.0 client-credentials grant (ADR-S029 §5). */
export default function ApiClientsPage() {
  const canManage = useAuthStore((s) => s.hasPermission)(PERMISSIONS.apiClient.manage);
  const { data: clients } = useApiClients();
  const { data: scopes } = useApiScopes();
  const create = useCreateApiClient();
  const rotate = useRotateApiClient();
  const revoke = useRevokeApiClient();
  const [name, setName] = useState('');
  const [picked, setPicked] = useState<string[]>([]);
  const [filter, setFilter] = useState('');
  const [secret, setSecret] = useState<{ clientId: string; clientSecret: string } | null>(null);

  const grantable = (scopes?.grantable ?? []).filter((s) => !filter || s.includes(filter));

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">{tx('API clients')}</h1>
        <p className="text-sm text-muted-foreground">
          {tx('Machine access for integrations: POST /api/v1/oauth/token with grant_type=client_credentials, then call the API with the Bearer token.')}
        </p>
      </div>
      {secret && (
        <Card className="border-amber-500">
          <CardContent className="space-y-1 pt-6 text-sm">
            <p className="font-medium">{tx('Copy the secret now. It is not shown again.')}</p>
            <div>client_id: <code className="select-all">{secret.clientId}</code></div>
            <div>client_secret: <code className="select-all break-all">{secret.clientSecret}</code></div>
            <Button size="sm" variant="outline" onClick={() => setSecret(null)}>{tx('Done')}</Button>
          </CardContent>
        </Card>
      )}
      <Card>
        <CardContent className="pt-6">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{tx('Name')}</TableHead>
                <TableHead>client_id</TableHead>
                <TableHead>{tx('Scopes')}</TableHead>
                <TableHead>{tx('Last used')}</TableHead>
                <TableHead>{tx('Status')}</TableHead>
                <TableHead />
              </TableRow>
            </TableHeader>
            <TableBody>
              {(clients ?? []).map((c) => (
                <TableRow key={c.id}>
                  <TableCell>{c.name}</TableCell>
                  <TableCell className="font-mono text-xs">{c.clientId}</TableCell>
                  <TableCell className="max-w-xs text-xs">{c.scopes.join(' ')}</TableCell>
                  <TableCell className="text-xs">{c.lastUsedAt ? String(c.lastUsedAt).replace('T', ' ').slice(0, 16) : '—'}</TableCell>
                  <TableCell>
                    <Badge variant={c.revokedAt ? 'destructive' : c.isActive ? 'default' : 'secondary'}>
                      {c.revokedAt ? tx('Revoked') : c.isActive ? tx('Active') : tx('Inactive')}
                    </Badge>
                  </TableCell>
                  <TableCell className="space-x-1 text-right">
                    {canManage && !c.revokedAt && (
                      <>
                        <Button size="sm" variant="ghost" onClick={() => rotate.mutate(c.id, {
                          onSuccess: (r) => setSecret({ clientId: c.clientId, clientSecret: (r as { clientSecret: string }).clientSecret }),
                        })}>{tx('Rotate secret')}</Button>
                        <Button size="sm" variant="ghost" onClick={() => revoke.mutate(c.id)}>{tx('Revoke')}</Button>
                      </>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
      {canManage && (
        <Card>
          <CardHeader>
            <CardTitle className="text-base">{tx('New API client')}</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            <div className="space-y-1">
              <Label>{tx('Name')}</Label>
              <Input value={name} onChange={(e) => setName(e.target.value)} className="w-80" />
            </div>
            <div className="space-y-1">
              <Label>{tx('Scopes')} ({picked.length})</Label>
              <Input value={filter} onChange={(e) => setFilter(e.target.value)} placeholder={tx('Filter, e.g. partner:')} className="w-80" />
              <div className="grid max-h-64 grid-cols-2 gap-1 overflow-y-auto rounded border p-2 md:grid-cols-4">
                {grantable.map((s) => (
                  <label key={s} className="flex items-center gap-1 font-mono text-xs">
                    <input type="checkbox" checked={picked.includes(s)}
                      onChange={(e) => setPicked((p) => (e.target.checked ? [...p, s] : p.filter((x) => x !== s)))} />
                    {s}
                  </label>
                ))}
              </div>
            </div>
            <Button disabled={create.isPending || !name.trim() || picked.length === 0} onClick={() => create.mutate(
              { name, scopes: picked },
              { onSuccess: (c) => { setSecret({ clientId: c.clientId, clientSecret: c.clientSecret ?? '' }); setName(''); setPicked([]); } },
            )}>{tx('Create client')}</Button>
          </CardContent>
        </Card>
      )}
    </div>
  );
}
