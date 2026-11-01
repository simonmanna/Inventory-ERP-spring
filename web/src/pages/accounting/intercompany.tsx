import { useState } from 'react';
import { PERMISSIONS } from '@erp/shared';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { useAccounts } from '@/features/accounting/api';
import {
  type CompanyGroup,
  useAddGroupMember,
  useCompanyGroup,
  useConsolidation,
  useCreateGroup,
  useIntercompanyJournals,
  useIntercompanyPartners,
  useIntercompanyReconciliation,
  useIntercompanySettings,
  useIntercompanyTransactions,
  useLinkIntercompanyPartner,
  usePostIntercompanyJournal,
  useRemoveGroupMember,
  useRetryIntercompanyTransaction,
  useSaveIntercompanySettings,
  useUpdateGroupMember,
} from '@/features/accounting/intercompany-api';
import { money } from '@/lib/format';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';

const SELECT = 'h-9 rounded-md border border-input bg-background px-3 text-sm';

/** Intercompany & multi-company: group, partners, mirrored documents, journals, reconciliation, consolidation (ADR-S028). */
export default function IntercompanyPage() {
  const { data: group, isLoading } = useCompanyGroup();
  const orgId = useAuthStore((s) => s.organization?.id);
  const isParent = !!group && group.parentOrganizationId === orgId;

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">{tx('Intercompany')}</h1>
        <p className="text-sm text-muted-foreground">
          {tx('Group your companies, mirror intercompany invoices, post intercompany journals and consolidate with eliminations.')}
        </p>
      </div>
      {isLoading ? (
        <Skeleton className="h-40 w-full" />
      ) : (
        <Tabs defaultValue="group">
          <TabsList className="flex-wrap">
            <TabsTrigger value="group">{tx('Company group')}</TabsTrigger>
            {group && <TabsTrigger value="partners">{tx('Partners & settings')}</TabsTrigger>}
            {group && <TabsTrigger value="documents">{tx('Mirrored documents')}</TabsTrigger>}
            {group && <TabsTrigger value="journals">{tx('Intercompany journals')}</TabsTrigger>}
            {isParent && <TabsTrigger value="reconciliation">{tx('Reconciliation')}</TabsTrigger>}
            {isParent && <TabsTrigger value="consolidation">{tx('Consolidation')}</TabsTrigger>}
          </TabsList>
          <TabsContent value="group">
            <GroupTab group={group ?? null} isParent={isParent} />
          </TabsContent>
          {group && (
            <TabsContent value="partners">
              <PartnersTab />
            </TabsContent>
          )}
          {group && (
            <TabsContent value="documents">
              <DocumentsTab />
            </TabsContent>
          )}
          {group && (
            <TabsContent value="journals">
              <JournalsTab group={group} />
            </TabsContent>
          )}
          {isParent && (
            <TabsContent value="reconciliation">
              <ReconciliationTab />
            </TabsContent>
          )}
          {isParent && group && (
            <TabsContent value="consolidation">
              <ConsolidationTab group={group} />
            </TabsContent>
          )}
        </Tabs>
      )}
    </div>
  );
}

function GroupTab({ group, isParent }: { group: CompanyGroup | null; isParent: boolean }) {
  const canManage = useAuthStore((s) => s.hasPermission)(PERMISSIONS.intercompany.manage);
  const create = useCreateGroup();
  const add = useAddGroupMember();
  const update = useUpdateGroupMember();
  const remove = useRemoveGroupMember();
  const [name, setName] = useState('');
  const [code, setCode] = useState('');
  const [pct, setPct] = useState('100');

  if (!group) {
    return (
      <Card>
        <CardHeader>
          <CardTitle className="text-base">{tx('No company group yet')}</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3 text-sm">
          <p className="text-muted-foreground">
            {tx('Create a group with this company as the parent, then add the companies you also administer.')}
          </p>
          {canManage && (
            <form
              className="flex flex-wrap items-end gap-2"
              onSubmit={(e) => {
                e.preventDefault();
                if (name.trim()) create.mutate(name.trim());
              }}
            >
              <div className="space-y-1">
                <Label>{tx('Group name')}</Label>
                <Input value={name} onChange={(e) => setName(e.target.value)} className="w-64" />
              </div>
              <Button type="submit" disabled={create.isPending || !name.trim()}>{tx('Create group')}</Button>
            </form>
          )}
        </CardContent>
      </Card>
    );
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">
          {group.name} <span className="text-sm font-normal text-muted-foreground">({group.currencyCode})</span>
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{tx('Company')}</TableHead>
              <TableHead>{tx('Currency')}</TableHead>
              <TableHead className="text-right">{tx('Ownership %')}</TableHead>
              <TableHead />
            </TableRow>
          </TableHeader>
          <TableBody>
            {group.members.map((m) => (
              <TableRow key={m.organizationId}>
                <TableCell>
                  {m.name} <span className="text-muted-foreground">({m.code})</span>{' '}
                  {m.isParent && <Badge variant="secondary">{tx('Parent')}</Badge>}
                </TableCell>
                <TableCell>{m.currencyCode}</TableCell>
                <TableCell className="text-right">
                  {isParent && canManage && !m.isParent ? (
                    <Input
                      key={m.ownershipPercent}
                      defaultValue={m.ownershipPercent}
                      className="ml-auto h-8 w-24 text-right"
                      onBlur={(e) => {
                        const v = Number(e.target.value);
                        if (v > 0 && v <= 100 && String(v) !== m.ownershipPercent) {
                          update.mutate({ organizationId: m.organizationId, ownershipPercent: v });
                        }
                      }}
                    />
                  ) : (
                    m.ownershipPercent
                  )}
                </TableCell>
                <TableCell className="text-right">
                  {isParent && canManage && !m.isParent && (
                    <Button size="sm" variant="ghost" onClick={() => remove.mutate(m.organizationId)}>
                      {tx('Remove')}
                    </Button>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
        {isParent && canManage && (
          <form
            className="flex flex-wrap items-end gap-2"
            onSubmit={(e) => {
              e.preventDefault();
              if (!code.trim()) return;
              add.mutate({ organizationCode: code.trim(), ownershipPercent: Number(pct) || 100 },
                { onSuccess: () => setCode('') });
            }}
          >
            <div className="space-y-1">
              <Label>{tx('Company code')}</Label>
              <Input value={code} onChange={(e) => setCode(e.target.value)} className="w-48" />
            </div>
            <div className="space-y-1">
              <Label>{tx('Ownership %')}</Label>
              <Input value={pct} onChange={(e) => setPct(e.target.value)} className="w-24" type="number" />
            </div>
            <Button type="submit" disabled={add.isPending || !code.trim()}>{tx('Add company')}</Button>
            <p className="w-full text-xs text-muted-foreground">
              {tx('You need an account with the same e-mail and intercompany rights in that company.')}
            </p>
          </form>
        )}
      </CardContent>
    </Card>
  );
}

function PartnersTab() {
  const canManage = useAuthStore((s) => s.hasPermission)(PERMISSIONS.intercompany.manage);
  const { data: links, isLoading } = useIntercompanyPartners(true);
  const link = useLinkIntercompanyPartner();
  const { data: settings } = useIntercompanySettings();
  const save = useSaveIntercompanySettings();
  const { data: accounts } = useAccounts();
  const expenseAccounts = (accounts?.data ?? []).filter((a) => !a.isGroup && a.isActive);

  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <Card>
        <CardHeader>
          <CardTitle className="text-base">{tx('Intercompany partners')}</CardTitle>
        </CardHeader>
        <CardContent>
          {isLoading ? (
            <Skeleton className="h-24 w-full" />
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{tx('Company')}</TableHead>
                  <TableHead>{tx('Partner')}</TableHead>
                  <TableHead />
                </TableRow>
              </TableHeader>
              <TableBody>
                {(links ?? []).map((l) => (
                  <TableRow key={l.counterpartOrganizationId}>
                    <TableCell>{l.counterpartName}</TableCell>
                    <TableCell>{l.partnerName ? `${l.partnerName} (${l.partnerCode})` : '—'}</TableCell>
                    <TableCell className="text-right">
                      {canManage && !l.partnerId && (
                        <Button size="sm" variant="outline" onClick={() => link.mutate({
                          counterpartOrganizationId: l.counterpartOrganizationId })}>
                          {tx('Create partner')}
                        </Button>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>
      <Card>
        <CardHeader>
          <CardTitle className="text-base">{tx('Mirroring')}</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3 text-sm">
          <p className="text-muted-foreground">
            {tx('A sales invoice posted by another group company to this company becomes a vendor bill here.')}
          </p>
          {settings && (
            <form
              className="space-y-3"
              onSubmit={(e) => {
                e.preventDefault();
                const f = new FormData(e.currentTarget);
                save.mutate({
                  mirrorMode: String(f.get('mirrorMode')),
                  billExpenseAccountId: String(f.get('billExpenseAccountId') ?? ''),
                });
              }}
            >
              <div className="space-y-1">
                <Label>{tx('Incoming invoices')}</Label>
                <select name="mirrorMode" defaultValue={settings.mirrorMode} className={SELECT} disabled={!canManage}>
                  <option value="off">{tx('Do not mirror')}</option>
                  <option value="draft">{tx('Create a draft bill')}</option>
                  <option value="post">{tx('Create and post the bill')}</option>
                </select>
              </div>
              <div className="space-y-1">
                <Label>{tx('Expense account for bill lines')}</Label>
                <select name="billExpenseAccountId" defaultValue={settings.billExpenseAccountId ?? ''} className={SELECT}
                  disabled={!canManage}>
                  <option value="">{tx('Default expense mapping')}</option>
                  {expenseAccounts.map((a) => (
                    <option key={a.id} value={a.id}>{a.code} — {a.name}</option>
                  ))}
                </select>
              </div>
              {canManage && <Button type="submit" disabled={save.isPending}>{tx('Save')}</Button>}
            </form>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

function DocumentsTab() {
  const canManage = useAuthStore((s) => s.hasPermission)(PERMISSIONS.intercompany.manage);
  const [status, setStatus] = useState('');
  const { data, isLoading } = useIntercompanyTransactions(status || undefined);
  const retry = useRetryIntercompanyTransaction();

  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between">
        <CardTitle className="text-base">{tx('Mirrored documents')}</CardTitle>
        <select value={status} onChange={(e) => setStatus(e.target.value)} className={SELECT}>
          <option value="">{tx('All')}</option>
          <option value="mirrored">{tx('Mirrored')}</option>
          <option value="failed">{tx('Failed')}</option>
          <option value="skipped">{tx('Skipped')}</option>
        </select>
      </CardHeader>
      <CardContent>
        {isLoading ? (
          <Skeleton className="h-24 w-full" />
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{tx('Source')}</TableHead>
                <TableHead>{tx('Bill')}</TableHead>
                <TableHead className="text-right">{tx('Amount')}</TableHead>
                <TableHead>{tx('Status')}</TableHead>
                <TableHead />
              </TableRow>
            </TableHeader>
            <TableBody>
              {(data?.data ?? []).map((t) => (
                <TableRow key={t.id}>
                  <TableCell>
                    {t.sourceDocumentNumber} <span className="text-muted-foreground">({t.sourceCompanyName})</span>
                  </TableCell>
                  <TableCell>
                    {t.targetDocumentNumber ?? '—'} <span className="text-muted-foreground">({t.targetCompanyName})</span>
                  </TableCell>
                  <TableCell className="text-right">{money(t.amount)} {t.currencyCode}</TableCell>
                  <TableCell>
                    <Badge variant={t.status === 'failed' ? 'destructive' : t.status === 'mirrored' ? 'default' : 'secondary'}>
                      {tx(t.status === 'mirrored' ? 'Mirrored' : t.status === 'failed' ? 'Failed' : 'Skipped')}
                    </Badge>
                    {t.error && <div className="mt-1 text-xs text-muted-foreground">{t.error}</div>}
                  </TableCell>
                  <TableCell className="text-right">
                    {canManage && t.direction === 'outgoing' && t.status !== 'mirrored' && (
                      <Button size="sm" variant="outline" disabled={retry.isPending} onClick={() => retry.mutate(t.id)}>
                        {tx('Retry')}
                      </Button>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </CardContent>
    </Card>
  );
}

function JournalsTab({ group }: { group: CompanyGroup }) {
  const canPost = useAuthStore((s) => s.hasPermission)(PERMISSIONS.intercompany.post);
  const orgId = useAuthStore((s) => s.organization?.id);
  const { data, isLoading } = useIntercompanyJournals();
  const { data: accounts } = useAccounts();
  const post = usePostIntercompanyJournal();
  const others = group.members.filter((m) => m.organizationId !== orgId);
  const today = new Date().toISOString().slice(0, 10);

  return (
    <div className="space-y-4">
      {canPost && (
        <Card>
          <CardHeader>
            <CardTitle className="text-base">{tx('New intercompany journal')}</CardTitle>
          </CardHeader>
          <CardContent>
            <form
              className="grid gap-3 md:grid-cols-3"
              onSubmit={(e) => {
                e.preventDefault();
                const f = new FormData(e.currentTarget);
                post.mutate({
                  targetOrganizationId: String(f.get('target')),
                  date: String(f.get('date')),
                  description: String(f.get('description')),
                  amount: Number(f.get('amount')),
                  sourceAccountId: String(f.get('sourceAccountId')),
                  targetAccountCode: String(f.get('targetAccountCode')),
                });
              }}
            >
              <div className="space-y-1">
                <Label>{tx('Other company')}</Label>
                <select name="target" className={SELECT} required>
                  {others.map((m) => <option key={m.organizationId} value={m.organizationId}>{m.name}</option>)}
                </select>
              </div>
              <div className="space-y-1">
                <Label>{tx('Date')}</Label>
                <Input name="date" type="date" defaultValue={today} required />
              </div>
              <div className="space-y-1">
                <Label>{tx('Amount')}</Label>
                <Input name="amount" type="number" step="0.01" min="0.01" required />
              </div>
              <div className="space-y-1 md:col-span-3">
                <Label>{tx('Description')}</Label>
                <Input name="description" required maxLength={500} />
              </div>
              <div className="space-y-1">
                <Label>{tx('Credit account here')}</Label>
                <select name="sourceAccountId" className={SELECT} required>
                  {(accounts?.data ?? []).filter((a) => !a.isGroup && a.isActive).map((a) => (
                    <option key={a.id} value={a.id}>{a.code} — {a.name}</option>
                  ))}
                </select>
              </div>
              <div className="space-y-1">
                <Label>{tx('Debit account code in the other company')}</Label>
                <Input name="targetAccountCode" required placeholder="1100" />
              </div>
              <div className="flex items-end">
                <Button type="submit" disabled={post.isPending}>{tx('Post in both companies')}</Button>
              </div>
            </form>
          </CardContent>
        </Card>
      )}
      <Card>
        <CardContent className="pt-6">
          {isLoading ? (
            <Skeleton className="h-24 w-full" />
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{tx('Number')}</TableHead>
                  <TableHead>{tx('Date')}</TableHead>
                  <TableHead>{tx('From')}</TableHead>
                  <TableHead>{tx('To')}</TableHead>
                  <TableHead>{tx('Description')}</TableHead>
                  <TableHead className="text-right">{tx('Amount')}</TableHead>
                  <TableHead>{tx('Entries')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {(data?.data ?? []).map((j) => (
                  <TableRow key={j.id}>
                    <TableCell>{j.number}</TableCell>
                    <TableCell>{String(j.postingDate).slice(0, 10)}</TableCell>
                    <TableCell>{j.sourceCompanyName}</TableCell>
                    <TableCell>{j.targetCompanyName}</TableCell>
                    <TableCell>{j.description}</TableCell>
                    <TableCell className="text-right">
                      {money(j.amount)} {j.currencyCode}
                      {j.targetCurrencyCode !== j.currencyCode && (
                        <div className="text-xs text-muted-foreground">{money(j.targetAmount)} {j.targetCurrencyCode}</div>
                      )}
                    </TableCell>
                    <TableCell className="text-xs">{j.sourceEntryNumber} / {j.targetEntryNumber}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

function ReconciliationTab() {
  const [asOf, setAsOf] = useState('');
  const { data, isLoading } = useIntercompanyReconciliation(true, asOf || undefined);
  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between">
        <CardTitle className="text-base">{tx('Intercompany balances')} {data && `(${data.currencyCode})`}</CardTitle>
        <Input type="date" value={asOf} onChange={(e) => setAsOf(e.target.value)} className="w-44" />
      </CardHeader>
      <CardContent>
        {isLoading ? (
          <Skeleton className="h-24 w-full" />
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{tx('Company A')}</TableHead>
                <TableHead>{tx('Company B')}</TableHead>
                <TableHead className="text-right">{tx('A with B')}</TableHead>
                <TableHead className="text-right">{tx('B with A')}</TableHead>
                <TableHead className="text-right">{tx('Difference')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {(data?.pairs ?? []).map((p) => (
                <TableRow key={`${p.companyA.organizationId}-${p.companyB.organizationId}`}>
                  <TableCell>{p.companyA.name}</TableCell>
                  <TableCell>{p.companyB.name}</TableCell>
                  <TableCell className="text-right">{money(p.balanceA)}</TableCell>
                  <TableCell className="text-right">{money(p.balanceB)}</TableCell>
                  <TableCell className="text-right">
                    <Badge variant={p.matched ? 'default' : 'destructive'}>{money(p.difference)}</Badge>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </CardContent>
    </Card>
  );
}

function ConsolidationTab({ group }: { group: CompanyGroup }) {
  const [statement, setStatement] = useState<'pl' | 'tb'>('pl');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const { data, isLoading } = useConsolidation(true, statement, from || undefined, to || undefined);
  const columns = [...group.members.map((m) => ({ id: m.organizationId, label: m.code })),
    { id: 'eliminations', label: tx('Eliminations') }, { id: 'consolidated', label: tx('Consolidated') }];
  const totals = (data?.totals ?? {}) as Record<string, Record<string, string> | string>;
  const totalRows = statement === 'pl' ? ['income', 'expense', 'netProfit'] : ['net'];
  const totalLabel: Record<string, string> = {
    income: tx('Income'), expense: tx('Expense'), netProfit: tx('Net profit'), net: tx('Net'),
  };

  return (
    <Card>
      <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-2">
        <CardTitle className="text-base">{tx('Group consolidation')} {data && `(${data.currencyCode})`}</CardTitle>
        <div className="flex flex-wrap gap-2">
          <select value={statement} onChange={(e) => setStatement(e.target.value as 'pl' | 'tb')} className={SELECT}>
            <option value="pl">{tx('Profit and loss')}</option>
            <option value="tb">{tx('Trial balance')}</option>
          </select>
          <Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} className="w-40" />
          <Input type="date" value={to} onChange={(e) => setTo(e.target.value)} className="w-40" />
        </div>
      </CardHeader>
      <CardContent className="overflow-x-auto">
        {isLoading || !data ? (
          <Skeleton className="h-40 w-full" />
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{tx('Account')}</TableHead>
                {columns.map((c) => <TableHead key={c.id} className="text-right">{c.label}</TableHead>)}
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.rows.map((r) => (
                <TableRow key={r.code}>
                  <TableCell>{r.code} — {r.name}</TableCell>
                  {columns.map((c) => <TableCell key={c.id} className="text-right">{money(r.byCompany[c.id] ?? '0')}</TableCell>)}
                </TableRow>
              ))}
              {totalRows.map((k) => {
                const t = totals[k] as Record<string, string> | undefined;
                return (
                  <TableRow key={k} className="font-semibold">
                    <TableCell>{totalLabel[k]}</TableCell>
                    {columns.map((c) => <TableCell key={c.id} className="text-right">{money(t?.[c.id] ?? '0')}</TableCell>)}
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        )}
        {data && (
          <div className="mt-4 grid gap-2 text-sm md:grid-cols-3">
            <div>{tx('Intercompany difference')}: <strong>{money(String(totals.intercompanyDifference ?? '0'))}</strong></div>
            {statement === 'pl' && (
              <>
                <div>{tx('Non-controlling interest')}: <strong>{money(String(totals.nonControllingInterest ?? '0'))}</strong></div>
                <div>{tx('Attributable to the parent')}: <strong>{money(String(totals.attributableToParent ?? '0'))}</strong></div>
              </>
            )}
          </div>
        )}
      </CardContent>
    </Card>
  );
}
