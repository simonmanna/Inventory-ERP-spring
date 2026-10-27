import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Plus, Target } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { useCostCenters } from '@/features/accounting/api';
import { useBranchOptions, useBudgets, useCreateBudget } from '@/features/finance/api';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';

export function BudgetsPage() {
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const { data, isLoading } = useBudgets({ pageSize: 100 });

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold">{tx('Budgets')}</h1>
          <p className="text-sm text-muted-foreground">
            {tx('Plan income and expenses per account and month, then compare with actual results.')}
          </p>
        </div>
        <Button onClick={() => setOpen(true)}>
          <Plus className="mr-2 h-4 w-4" />
          {tx('New budget')}
        </Button>
      </div>

      <div className="rounded-md border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{tx('Name')}</TableHead>
              <TableHead>{tx('Year')}</TableHead>
              <TableHead>{tx('Period')}</TableHead>
              <TableHead>{tx('Status')}</TableHead>
              <TableHead className="text-right">{tx('Lines')}</TableHead>
              <TableHead className="text-right">{tx('Total')}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {isLoading ? (
              <TableRow>
                <TableCell colSpan={6}>
                  <Skeleton className="h-4 w-full" />
                </TableCell>
              </TableRow>
            ) : !data || data.data.length === 0 ? (
              <TableRow>
                <TableCell colSpan={6} className="h-24 text-center text-muted-foreground">
                  <Target className="mx-auto mb-2 h-6 w-6" />
                  {tx('No budgets yet.')}
                </TableCell>
              </TableRow>
            ) : (
              data.data.map((b) => (
                <TableRow key={b.id} className="cursor-pointer" onClick={() => navigate(`/budgets/${b.id}`)}>
                  <TableCell className="font-medium">
                    <Link to={`/budgets/${b.id}`}>{b.name}</Link>
                  </TableCell>
                  <TableCell>{b.fiscalYear}</TableCell>
                  <TableCell>
                    {date(b.startDate)} – {date(b.endDate)}
                  </TableCell>
                  <TableCell>
                    <Badge variant={b.status === 'approved' ? 'default' : 'secondary'}>{tx(b.status)}</Badge>
                  </TableCell>
                  <TableCell className="text-right">{b.lineCount ?? 0}</TableCell>
                  <TableCell className="text-right">{money(b.totalAmount)}</TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </div>

      <CreateBudgetDialog open={open} onOpenChange={setOpen} onCreated={(id) => navigate(`/budgets/${id}`)} />
    </div>
  );
}

function CreateBudgetDialog({
  open,
  onOpenChange,
  onCreated,
}: {
  open: boolean;
  onOpenChange: (v: boolean) => void;
  onCreated: (id: string) => void;
}) {
  const [name, setName] = useState('');
  const [year, setYear] = useState(String(new Date().getFullYear()));
  const [branchId, setBranchId] = useState('');
  const [costCenterId, setCostCenterId] = useState('');
  const create = useCreateBudget();
  const { data: branches } = useBranchOptions();
  const { data: centers } = useCostCenters();

  const submit = () =>
    create.mutate(
      {
        name: name.trim(),
        fiscalYear: Number(year),
        branchId: branchId || undefined,
        costCenterId: costCenterId || undefined,
      },
      {
        onSuccess: (b) => {
          onOpenChange(false);
          setName('');
          onCreated((b as { id: string }).id);
        },
      },
    );

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{tx('New budget')}</DialogTitle>
        </DialogHeader>
        <div className="space-y-3">
          <div className="space-y-1">
            <Label>{tx('Name')}</Label>
            <Input value={name} onChange={(e) => setName(e.target.value)} placeholder={tx('e.g. Operating budget')} />
          </div>
          <div className="space-y-1">
            <Label>{tx('Fiscal year')}</Label>
            <Input type="number" value={year} onChange={(e) => setYear(e.target.value)} />
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-1">
              <Label>{tx('Branch (optional)')}</Label>
              <select
                className="h-9 w-full rounded-md border bg-background px-2 text-sm"
                value={branchId}
                onChange={(e) => setBranchId(e.target.value)}
              >
                <option value="">{tx('All branches')}</option>
                {(branches ?? []).map((b) => (
                  <option key={b.id} value={b.id}>
                    {b.name}
                  </option>
                ))}
              </select>
            </div>
            <div className="space-y-1">
              <Label>{tx('Cost center (optional)')}</Label>
              <select
                className="h-9 w-full rounded-md border bg-background px-2 text-sm"
                value={costCenterId}
                onChange={(e) => setCostCenterId(e.target.value)}
              >
                <option value="">{tx('All cost centers')}</option>
                {(centers?.data ?? []).map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}
                  </option>
                ))}
              </select>
            </div>
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            {tx('Cancel')}
          </Button>
          <Button disabled={!name.trim() || !year || create.isPending} onClick={submit}>
            {tx('Create')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
