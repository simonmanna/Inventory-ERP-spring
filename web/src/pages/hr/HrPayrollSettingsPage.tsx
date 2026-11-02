import { useMemo, useState } from 'react';
import { Settings2 } from 'lucide-react';
import { useHrPayrollComponents, useCreateHrPayrollComponent, useUpdateHrPayrollComponent, useDeleteHrPayrollComponent, useHrTaxTables, useCreateHrTaxTable, useUpdateHrTaxTable, useDeleteHrTaxTable } from '@/features/hr/api';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { HrOvertimePanel } from './HrOvertimePanel';
import { HrCostAllocationPanel } from './HrCostAllocationPanel';
import { HrPayrollGeneralPanel } from './HrPayrollGeneralPanel';
import { HrPayGroupsPanel } from './HrPayGroupsPanel';
import { tx } from '@/lib/i18n/tx';

const fmtPct = (r: number | null) => (r === null ? '—' : `${r * 100}%`);

export function HrPayrollSettingsPage() {
  const [tab, setTab] = useState('general');
  const [open, setOpen] = useState(false);
  const [editing, setEditing] = useState<any>(null);
  const [form, setForm] = useState<any>({});
  const { data: comps } = useHrPayrollComponents();
  const { data: tables } = useHrTaxTables();
  const createC = useCreateHrPayrollComponent();
  const updateC = useUpdateHrPayrollComponent();
  const deleteC = useDeleteHrPayrollComponent();
  const createT = useCreateHrTaxTable();
  const updateT = useUpdateHrTaxTable();
  const deleteT = useDeleteHrTaxTable();

  const compRows = useMemo(() => comps?.rows ?? [], [comps]);
  const tableRows = useMemo(() => tables?.rows ?? [], [tables]);

  const openCompCreate = () => { setEditing(null); setForm({ componentType: 'ALLOWANCE', calcMethod: 'FIXED', isTaxable: true, isRecurring: true, appliesTo: '', isActive: true }); setOpen(true); };
  const openCompEdit = (c: any) => {
    setEditing(c);
    setForm({
      code: c.code, name: c.name, componentType: c.componentType, calcMethod: c.calcMethod,
      amount: c.amount ?? '', rate: c.rate ?? '', isTaxable: c.isTaxable, isRecurring: c.isRecurring,
      appliesTo: c.appliesTo ?? '', isActive: c.isActive,
    });
    setOpen(true);
  };
  const submitComp = async () => {
    const payload: any = {
      code: form.code, name: form.name, componentType: form.componentType, calcMethod: form.calcMethod,
      isTaxable: form.isTaxable, isRecurring: form.isRecurring, isActive: form.isActive,
      appliesTo: form.appliesTo || null,
    };
    if (form.amount !== '' && form.amount !== null) payload.amount = Number(form.amount);
    if (form.rate !== '' && form.rate !== null) payload.rate = Number(form.rate);
    if (editing) await updateC.mutateAsync({ id: editing.id, dto: payload });
    else await createC.mutateAsync(payload);
    setOpen(false);
  };

  const openTableCreate = () => { setEditing(null); setForm({ taxType: 'PAYE', effectiveFrom: new Date().toISOString().slice(0, 10), isActive: true }); setOpen(true); };
  const openTableEdit = (t: any) => {
    setEditing(t);
    setForm({ code: t.code, name: t.name, countryCode: t.countryCode ?? '', taxType: t.taxType, effectiveFrom: t.effectiveFrom?.slice(0, 10) ?? '', isActive: t.isActive });
    setOpen(true);
  };
  const submitTable = async () => {
    const payload: any = {
      code: form.code, name: form.name, taxType: form.taxType, effectiveFrom: new Date(form.effectiveFrom).toISOString(),
      isActive: form.isActive, countryCode: form.countryCode || null,
    };
    if (editing) await updateT.mutateAsync({ id: editing.id, dto: payload });
    else await createT.mutateAsync(payload);
    setOpen(false);
  };

  return (
    <div className="space-y-4 p-6">
      <div>
        <h1 className="text-xl font-semibold">{tx('Payroll settings')}</h1>
        <p className="text-sm text-muted-foreground">{tx('Payroll rules, pay groups and calendars, components and tax tables.')}</p>
      </div>

      <Tabs value={tab} onValueChange={setTab}>
        <TabsList>
          <TabsTrigger value="general">{tx('General')}</TabsTrigger>
          <TabsTrigger value="groups">{tx('Pay groups')}</TabsTrigger>
          <TabsTrigger value="components">{tx('Components')}</TabsTrigger>
          <TabsTrigger value="taxes">{tx('Tax tables')}</TabsTrigger>
          <TabsTrigger value="overtime">{tx('Overtime')}</TabsTrigger>
          <TabsTrigger value="costs">{tx('Cost allocation')}</TabsTrigger>
        </TabsList>

        <TabsContent value="general" className="space-y-4">
          <HrPayrollGeneralPanel />
        </TabsContent>

        <TabsContent value="groups" className="space-y-4">
          <HrPayGroupsPanel />
        </TabsContent>

        <TabsContent value="components" className="space-y-4">
          <div className="flex justify-end">
            <Button onClick={openCompCreate}>{tx('New component')}</Button>
          </div>
          <Card>
            <CardHeader className="bg-muted/30 border-b rounded-t-lg">
              <CardTitle className="text-sm">{compRows.length} {tx('components')}</CardTitle>
            </CardHeader>
            <CardContent className="p-0">
              <div className="divide-y">
                {compRows.length === 0 && <p className="p-6 text-sm text-muted-foreground">{tx('No components yet — add housing, transport, insurance, etc.')}</p>}
                {compRows.map((c: any) => (
                  <div key={c.id} className="flex items-center justify-between px-4 py-3 hover:bg-muted/40">
                    <div className="flex items-center gap-3">
                      <Settings2 className="h-4 w-4 text-muted-foreground" />
                      <div>
                        <p className="text-sm font-medium">{c.code} · {c.name}</p>
                        <p className="text-xs text-muted-foreground">
                          {c.componentType === 'ALLOWANCE' ? '+' : '−'}{' '}
                          {c.calcMethod === 'FIXED' ? tx('Rp {{v1}}', { v1: Number(c.amount ?? 0).toLocaleString('id-ID') }) : fmtPct(c.rate)}
                          {c.isTaxable ? tx(' · taxable') : tx(' · non-taxable')} · {c.isRecurring ? 'recurring' : 'one-off'}
                          {c.appliesTo ? ` · ${c.appliesTo.replace(/_/g, ' ')}` : ''}
                        </p>
                      </div>
                    </div>
                    <div className="flex items-center gap-2">
                      {!c.isActive && <Badge variant="outline" className="bg-muted text-muted-foreground">{tx('Inactive')}</Badge>}
                      <button onClick={() => openCompEdit(c)} className="rounded-md border px-2 py-1 text-xs hover:bg-muted/60">{tx('Edit')}</button>
                      <button onClick={() => { if (confirm(tx('Delete component {{name}}?', { name: c.name }))) deleteC.mutate(c.id); }}
                        className="rounded-md border px-2 py-1 text-xs text-rose-600 hover:bg-rose-50">{tx('Delete')}</button>
                    </div>
                  </div>
                ))}
              </div>
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="taxes" className="space-y-4">
          <div className="flex justify-end">
            <Button onClick={openTableCreate}>{tx('New tax table')}</Button>
          </div>
          <Card>
            <CardHeader className="bg-muted/30 border-b rounded-t-lg">
              <CardTitle className="text-sm">{tableRows.length} {tx('tax tables')}</CardTitle>
            </CardHeader>
            <CardContent className="p-0">
              <div className="divide-y">
                {tableRows.length === 0 && <p className="p-6 text-sm text-muted-foreground">{tx('No tax tables yet — add PAYE brackets so payroll can withhold.')}</p>}
                {tableRows.map((t: any) => (
                  <div key={t.id} className="flex items-center justify-between px-4 py-3 hover:bg-muted/40">
                    <div className="flex-1">
                      <p className="text-sm font-medium">{t.code} · {t.name} · {t.taxType.replace(/_/g, ' ')}</p>
                      <p className="text-xs text-muted-foreground">
                        {tx('Effective')} {t.effectiveFrom?.slice(0, 10)}{t.countryCode ? ` · ${t.countryCode}` : ''} ·{' '}
                        {(t.brackets ?? []).map((b: any) => `Rp ${Number(b.fromAmount).toLocaleString('id-ID')}–${b.toAmount ? 'Rp ' + Number(b.toAmount).toLocaleString('id-ID') : '∞'} @ ${b.rate * 100}%`).join(' · ')}
                      </p>
                    </div>
                    <div className="flex items-center gap-2">
                      {!t.isActive && <Badge variant="outline" className="bg-muted text-muted-foreground">{tx('Inactive')}</Badge>}
                      <button onClick={() => openTableEdit(t)} className="rounded-md border px-2 py-1 text-xs hover:bg-muted/60">{tx('Edit')}</button>
                      <button onClick={() => { if (confirm(tx('Delete tax table {{name}}?', { name: t.name }))) deleteT.mutate(t.id); }}
                        className="rounded-md border px-2 py-1 text-xs text-rose-600 hover:bg-rose-50">{tx('Delete')}</button>
                    </div>
                  </div>
                ))}
              </div>
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="overtime" className="space-y-4">
          <HrOvertimePanel />
        </TabsContent>

        <TabsContent value="costs" className="space-y-4">
          <HrCostAllocationPanel />
        </TabsContent>
      </Tabs>

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>
              {editing
                ? tab === 'components' ? tx('Edit component') : tx('Edit tax table')
                : tab === 'components' ? tx('New component') : tx('New tax table')}
            </DialogTitle>
          </DialogHeader>
          {tab === 'components' ? (
            <div className="grid gap-3">
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <Label>{tx('Code *')}</Label>
                  <Input value={form.code ?? ''} onChange={(e) => setForm({ ...form, code: e.target.value })} />
                </div>
                <div>
                  <Label>{tx('Name *')}</Label>
                  <Input value={form.name ?? ''} onChange={(e) => setForm({ ...form, name: e.target.value })} />
                </div>
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <Label>{tx('Type')}</Label>
                  <select value={form.componentType} onChange={(e) => setForm({ ...form, componentType: e.target.value })}
                    className="w-full rounded-md border bg-card px-3 py-2 text-sm">
                    <option value="ALLOWANCE">{tx('Allowance')}</option>
                    <option value="DEDUCTION">{tx('Deduction')}</option>
                  </select>
                </div>
                <div>
                  <Label>{tx('Calc method')}</Label>
                  <select value={form.calcMethod} onChange={(e) => setForm({ ...form, calcMethod: e.target.value })}
                    className="w-full rounded-md border bg-card px-3 py-2 text-sm">
                    <option value="FIXED">{tx('Fixed amount')}</option>
                    <option value="PERCENTAGE">{tx('Percentage of base')}</option>
                  </select>
                </div>
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <Label>{form.calcMethod === 'FIXED' ? tx('Amount') : tx('Rate (0.05 = 5%)')}</Label>
                  <Input type="number" step="0.0001" value={form.amount ?? (form.calcMethod === 'PERCENTAGE' ? '' : form.amount ?? '')}
                    onChange={(e) => setForm({ ...form, [form.calcMethod === 'FIXED' ? 'amount' : 'rate']: e.target.value })} />
                </div>
                <div>
                  <Label>{tx('Applies to')}</Label>
                  <select value={form.appliesTo ?? ''} onChange={(e) => setForm({ ...form, appliesTo: e.target.value })}
                    className="w-full rounded-md border bg-card px-3 py-2 text-sm">
                    <option value="">{tx('Everyone')}</option>
                    <option value="ALL">{tx('All employees')}</option>
                    <option value="FULL_TIME">{tx('Full-time only')}</option>
                    <option value="CONTRACT">{tx('Contract only')}</option>
                  </select>
                </div>
              </div>
              <div className="flex gap-4 text-sm">
                <label className="flex items-center gap-1"><input type="checkbox" checked={form.isTaxable} onChange={(e) => setForm({ ...form, isTaxable: e.target.checked })} /> {tx('Taxable')}</label>
                <label className="flex items-center gap-1"><input type="checkbox" checked={form.isRecurring} onChange={(e) => setForm({ ...form, isRecurring: e.target.checked })} /> {tx('Recurring')}</label>
                <label className="flex items-center gap-1"><input type="checkbox" checked={form.isActive} onChange={(e) => setForm({ ...form, isActive: e.target.checked })} /> {tx('Active')}</label>
              </div>
              <Button onClick={submitComp} disabled={!form.code || !form.name || createC.isPending || updateC.isPending}>
                {editing ? tx('Save changes') : tx('Create component')}
              </Button>
            </div>
          ) : (
            <div className="grid gap-3">
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <Label>{tx('Code *')}</Label>
                  <Input value={form.code ?? ''} onChange={(e) => setForm({ ...form, code: e.target.value })} />
                </div>
                <div>
                  <Label>{tx('Name *')}</Label>
                  <Input value={form.name ?? ''} onChange={(e) => setForm({ ...form, name: e.target.value })} />
                </div>
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <Label>{tx('Tax type')}</Label>
                  <select value={form.taxType} onChange={(e) => setForm({ ...form, taxType: e.target.value })}
                    className="w-full rounded-md border bg-card px-3 py-2 text-sm">
                    <option value="PAYE">{tx('PAYE')}</option>
                    <option value="PENSION">{tx('Pension')}</option>
                    <option value="SOCIAL_SECURITY">{tx('Social security')}</option>
                    <option value="LOCAL">{tx('Local')}</option>
                  </select>
                </div>
                <div>
                  <Label>{tx('Effective from *')}</Label>
                  <Input type="date" value={form.effectiveFrom ?? ''} onChange={(e) => setForm({ ...form, effectiveFrom: e.target.value })} />
                </div>
              </div>
              <div>
                <Label>{tx('Country code')}</Label>
                <Input value={form.countryCode ?? ''} onChange={(e) => setForm({ ...form, countryCode: e.target.value })} placeholder="ID" />
              </div>
              <p className="text-xs text-muted-foreground">{tx('Brackets are managed through the API — the payroll engine applies the top-most active table of each type.')}</p>
              <Button onClick={submitTable} disabled={!form.code || !form.name || !form.effectiveFrom || createT.isPending || updateT.isPending}>
                {editing ? tx('Save changes') : tx('Create tax table')}
              </Button>
            </div>
          )}
        </DialogContent>
      </Dialog>
    </div>
  );
}
