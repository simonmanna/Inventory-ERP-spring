import { useState } from 'react';
import { Plus } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import {
  useAccounts,
  useAnalyticAccountRules,
  useAnalyticDimension,
  useAnalyticDimensions,
  useAnalyticSummary,
  useCreateAnalyticDimension,
  useCreateAnalyticValue,
  useSetAnalyticAccountRule,
  useUpdateAnalyticDimension,
  useUpdateAnalyticValue,
} from '@/features/accounting/api';
import { money } from '@/lib/format';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';

const SELECT = 'h-9 rounded-md border border-input bg-background px-3 text-sm';

/** Analytic dimensions: master data, account rules and the P&L by dimension value (roadmap 2.4, ADR-S025). */
export default function AnalyticDimensionsPage() {
  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">{tx('Analytic dimensions')}</h1>
        <p className="text-sm text-muted-foreground">
          {tx('Tag journal and document lines with projects, regions or any other dimension, then report by them.')}
        </p>
      </div>
      <Tabs defaultValue="dimensions">
        <TabsList>
          <TabsTrigger value="dimensions">{tx('Dimensions')}</TabsTrigger>
          <TabsTrigger value="rules">{tx('Account rules')}</TabsTrigger>
          <TabsTrigger value="report">{tx('Report')}</TabsTrigger>
        </TabsList>
        <TabsContent value="dimensions">
          <DimensionsTab />
        </TabsContent>
        <TabsContent value="rules">
          <RulesTab />
        </TabsContent>
        <TabsContent value="report">
          <ReportTab />
        </TabsContent>
      </Tabs>
    </div>
  );
}

function DimensionsTab() {
  const canManage = useAuthStore((s) => s.hasPermission)(PERMISSIONS.analyticDimension.manage);
  const { data, isLoading } = useAnalyticDimensions();
  const create = useCreateAnalyticDimension();
  const update = useUpdateAnalyticDimension();
  const [selected, setSelected] = useState<string>();
  const [code, setCode] = useState('');
  const [name, setName] = useState('');

  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <Card>
        <CardHeader>
          <CardTitle className="text-base">{tx('Dimensions')}</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          {canManage && (
            <form
              className="flex flex-wrap items-end gap-2"
              onSubmit={async (e) => {
                e.preventDefault();
                await create.mutateAsync({ code, name });
                setCode('');
                setName('');
              }}
            >
              <div className="space-y-1">
                <Label htmlFor="dim-code">{tx('Code')}</Label>
                <Input id="dim-code" value={code} onChange={(e) => setCode(e.target.value)} placeholder="project" />
              </div>
              <div className="space-y-1">
                <Label htmlFor="dim-name">{tx('Name')}</Label>
                <Input id="dim-name" value={name} onChange={(e) => setName(e.target.value)} />
              </div>
              <Button type="submit" disabled={!code || !name || create.isPending}>
                <Plus className="mr-1 h-4 w-4" aria-hidden />
                {tx('Add')}
              </Button>
            </form>
          )}
          {isLoading ? (
            <Skeleton className="h-24 w-full" />
          ) : !data?.length ? (
            <p className="text-sm text-muted-foreground">{tx('No dimensions yet.')}</p>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{tx('Code')}</TableHead>
                  <TableHead>{tx('Name')}</TableHead>
                  <TableHead>{tx('Values')}</TableHead>
                  <TableHead>{tx('Status')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {data.map((d) => (
                  <TableRow
                    key={d.id}
                    className={`cursor-pointer ${selected === d.id ? 'bg-muted' : ''}`}
                    onClick={() => setSelected(d.id)}
                  >
                    <TableCell className="font-mono">{d.code}</TableCell>
                    <TableCell>{d.name}</TableCell>
                    <TableCell>{d.valueCount ?? 0}</TableCell>
                    <TableCell>
                      {canManage ? (
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={(e) => {
                            e.stopPropagation();
                            update.mutate({ id: d.id, isActive: !d.isActive });
                          }}
                        >
                          <Badge variant={d.isActive ? 'default' : 'secondary'}>
                            {d.isActive ? tx('Active') : tx('Inactive')}
                          </Badge>
                        </Button>
                      ) : (
                        <Badge variant={d.isActive ? 'default' : 'secondary'}>
                          {d.isActive ? tx('Active') : tx('Inactive')}
                        </Badge>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>
      {selected ? <ValuesCard dimensionId={selected} canManage={canManage} /> : null}
    </div>
  );
}

function ValuesCard({ dimensionId, canManage }: { dimensionId: string; canManage: boolean }) {
  const { data } = useAnalyticDimension(dimensionId);
  const create = useCreateAnalyticValue();
  const update = useUpdateAnalyticValue();
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [parentId, setParentId] = useState('');
  const values = data?.values ?? [];
  const codeOf = (id: string | null) => values.find((v) => v.id === id)?.code ?? '';

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">
          {tx('Values')} — {data?.name}
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        {canManage && (
          <form
            className="flex flex-wrap items-end gap-2"
            onSubmit={async (e) => {
              e.preventDefault();
              await create.mutateAsync({ dimensionId, code, name, parentId: parentId || undefined });
              setCode('');
              setName('');
            }}
          >
            <div className="space-y-1">
              <Label htmlFor="val-code">{tx('Code')}</Label>
              <Input id="val-code" value={code} onChange={(e) => setCode(e.target.value)} />
            </div>
            <div className="space-y-1">
              <Label htmlFor="val-name">{tx('Name')}</Label>
              <Input id="val-name" value={name} onChange={(e) => setName(e.target.value)} />
            </div>
            <div className="space-y-1">
              <Label htmlFor="val-parent">{tx('Parent')}</Label>
              <select id="val-parent" className={SELECT} value={parentId} onChange={(e) => setParentId(e.target.value)}>
                <option value="">—</option>
                {values.map((v) => (
                  <option key={v.id} value={v.id}>
                    {v.code}
                  </option>
                ))}
              </select>
            </div>
            <Button type="submit" disabled={!code || !name || create.isPending}>
              {tx('Add')}
            </Button>
          </form>
        )}
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{tx('Code')}</TableHead>
              <TableHead>{tx('Name')}</TableHead>
              <TableHead>{tx('Parent')}</TableHead>
              <TableHead>{tx('Status')}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {values.map((v) => (
              <TableRow key={v.id}>
                <TableCell className="font-mono">{v.code}</TableCell>
                <TableCell>{v.name}</TableCell>
                <TableCell className="font-mono">{codeOf(v.parentId)}</TableCell>
                <TableCell>
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={!canManage}
                    onClick={() => update.mutate({ dimensionId, valueId: v.id, isActive: !v.isActive })}
                  >
                    <Badge variant={v.isActive ? 'default' : 'secondary'}>
                      {v.isActive ? tx('Active') : tx('Inactive')}
                    </Badge>
                  </Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  );
}

function RulesTab() {
  const canManage = useAuthStore((s) => s.hasPermission)(PERMISSIONS.analyticDimension.manage);
  const { data: dims } = useAnalyticDimensions();
  const { data: accounts } = useAccounts();
  const { data: rules } = useAnalyticAccountRules();
  const setRule = useSetAnalyticAccountRule();
  const [accountId, setAccountId] = useState('');
  const [dimension, setDimension] = useState('');
  const [rule, setRuleValue] = useState<'required' | 'optional' | 'forbidden'>('required');

  return (
    <Card>
      <CardContent className="space-y-3 pt-6">
        <p className="text-sm text-muted-foreground">
          {tx('Required: every line on the account must carry the dimension. Forbidden: lines on it must not.')}
        </p>
        {canManage && (
          <div className="flex flex-wrap items-end gap-2">
            <select className={SELECT} value={accountId} onChange={(e) => setAccountId(e.target.value)} aria-label={tx('Account')}>
              <option value="">{tx('Account')}</option>
              {(accounts?.data ?? []).map((a) => (
                <option key={a.id} value={a.id}>
                  {a.code} {a.name}
                </option>
              ))}
            </select>
            <select className={SELECT} value={dimension} onChange={(e) => setDimension(e.target.value)} aria-label={tx('Dimension')}>
              <option value="">{tx('Dimension')}</option>
              {(dims ?? []).map((d) => (
                <option key={d.id} value={d.code}>
                  {d.name}
                </option>
              ))}
            </select>
            <select
              className={SELECT}
              value={rule}
              onChange={(e) => setRuleValue(e.target.value as typeof rule)}
              aria-label={tx('Rule')}
            >
              <option value="required">{tx('Required')}</option>
              <option value="optional">{tx('Optional')}</option>
              <option value="forbidden">{tx('Forbidden')}</option>
            </select>
            <Button disabled={!accountId || !dimension} onClick={() => setRule.mutate({ accountId, dimension, rule })}>
              {tx('Save')}
            </Button>
          </div>
        )}
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{tx('Account')}</TableHead>
              <TableHead>{tx('Dimension')}</TableHead>
              <TableHead>{tx('Rule')}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {(rules ?? []).map((r) => (
              <TableRow key={r.accountId + r.dimension}>
                <TableCell>
                  {r.accountCode} {r.accountName}
                </TableCell>
                <TableCell className="font-mono">{r.dimension}</TableCell>
                <TableCell>{r.rule === 'required' ? tx('Required') : tx('Forbidden')}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  );
}

function ReportTab() {
  const { data: dims } = useAnalyticDimensions();
  const [dimension, setDimension] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [filter, setFilter] = useState('');
  const { data, isFetching } = useAnalyticSummary({
    dimension: dimension || undefined,
    from: from || undefined,
    to: to || undefined,
    filter: filter || undefined,
  });

  return (
    <Card>
      <CardContent className="space-y-4 pt-6">
        <div className="flex flex-wrap items-end gap-2">
          <div className="space-y-1">
            <Label htmlFor="rep-dim">{tx('Dimension')}</Label>
            <select id="rep-dim" className={SELECT} value={dimension} onChange={(e) => setDimension(e.target.value)}>
              <option value="">—</option>
              <option value="costCenter">{tx('Cost center')}</option>
              {(dims ?? []).map((d) => (
                <option key={d.id} value={d.code}>
                  {d.name}
                </option>
              ))}
            </select>
          </div>
          <div className="space-y-1">
            <Label htmlFor="rep-from">{tx('From')}</Label>
            <Input id="rep-from" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
          </div>
          <div className="space-y-1">
            <Label htmlFor="rep-to">{tx('To')}</Label>
            <Input id="rep-to" type="date" value={to} onChange={(e) => setTo(e.target.value)} />
          </div>
          <div className="space-y-1">
            <Label htmlFor="rep-filter">{tx('Filter')}</Label>
            <Input
              id="rep-filter"
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
              placeholder="region:EAST"
            />
          </div>
        </div>
        {!dimension ? (
          <p className="text-sm text-muted-foreground">{tx('Choose a dimension to see the profit and loss by value.')}</p>
        ) : isFetching && !data ? (
          <Skeleton className="h-24 w-full" />
        ) : data ? (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{tx('Value')}</TableHead>
                <TableHead className="text-right">{tx('Income')}</TableHead>
                <TableHead className="text-right">{tx('Expense')}</TableHead>
                <TableHead className="text-right">{tx('Net')}</TableHead>
                <TableHead className="text-right">{tx('Net incl. children')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.values.map((v) => (
                <TableRow key={v.value}>
                  <TableCell>
                    <span className="font-mono">{v.value}</span> {v.name !== v.value ? v.name : ''}
                  </TableCell>
                  <TableCell className="text-right">{money(v.income)}</TableCell>
                  <TableCell className="text-right">{money(v.expense)}</TableCell>
                  <TableCell className="text-right">{money(v.net)}</TableCell>
                  <TableCell className="text-right">{money(v.rollupNet)}</TableCell>
                </TableRow>
              ))}
              <TableRow>
                <TableCell className="text-muted-foreground">{tx('Unassigned')}</TableCell>
                <TableCell className="text-right">{money(data.unassigned.income)}</TableCell>
                <TableCell className="text-right">{money(data.unassigned.expense)}</TableCell>
                <TableCell className="text-right">{money(data.unassigned.net)}</TableCell>
                <TableCell />
              </TableRow>
              <TableRow className="font-semibold">
                <TableCell>{tx('Total')}</TableCell>
                <TableCell className="text-right">{money(data.totals.income)}</TableCell>
                <TableCell className="text-right">{money(data.totals.expense)}</TableCell>
                <TableCell className="text-right">{money(data.totals.net)}</TableCell>
                <TableCell />
              </TableRow>
            </TableBody>
          </Table>
        ) : null}
      </CardContent>
    </Card>
  );
}
