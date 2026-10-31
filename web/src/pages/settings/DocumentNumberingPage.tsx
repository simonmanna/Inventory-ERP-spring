import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import { isHeldForApproval } from '@/features/approval-controls/api';
import { api } from '@/lib/api';
import { tx } from '@/lib/i18n/tx';
import { notify } from '@/lib/notify';
import { useAuthStore } from '@/stores/auth.store';

interface NumberFormat {
  key: string;
  label: string;
  group: string;
  yearly: boolean;
  customized: boolean;
  prefix: string;
  suffix: string;
  padding: number;
  nextNumber: number;
  preview: string;
}

const errorText = (e: any) => e?.response?.data?.message ?? e?.message ?? tx('Failed');

export function DocumentNumberingPage() {
  const qc = useQueryClient();
  const canManage = useAuthStore((s) => s.hasPermission)('document_numbering:manage');
  const [editing, setEditing] = useState<NumberFormat | null>(null);
  const [form, setForm] = useState({ prefix: '', suffix: '', padding: '5', nextNumber: '' });
  const list = useQuery<NumberFormat[]>({
    queryKey: ['document-numbering'],
    queryFn: async () => (await api.get('/document-numbering')).data,
  });
  const groups = useMemo(() => {
    const out = new Map<string, NumberFormat[]>();
    list.data?.forEach((f) => out.set(f.group, [...(out.get(f.group) ?? []), f]));
    return [...out.entries()];
  }, [list.data]);

  const finished = (body: unknown) => {
    notify.success(isHeldForApproval(body) ? tx('Held for approval') : tx('Saved'));
    setEditing(null);
    qc.invalidateQueries({ queryKey: ['document-numbering'] });
    qc.invalidateQueries({ queryKey: ['config-changes'] });
  };

  const save = useMutation({
    mutationFn: async () => {
      const key = editing!.key;
      const body = (await api.put(`/document-numbering/${key}`, {
        prefix: form.prefix,
        suffix: form.suffix,
        padding: Number(form.padding),
      })).data;
      if (form.nextNumber !== '' && Number(form.nextNumber) !== editing!.nextNumber) {
        return (await api.post(`/document-numbering/${key}/advance`, { nextNumber: Number(form.nextNumber) })).data;
      }
      return body;
    },
    onSuccess: finished,
    onError: (e) => notify.error(errorText(e)),
  });
  const reset = useMutation({
    mutationFn: async (key: string) => (await api.delete(`/document-numbering/${key}`)).data,
    onSuccess: finished,
    onError: (e) => notify.error(errorText(e)),
  });

  const open = (f: NumberFormat) => {
    setEditing(f);
    setForm({ prefix: f.prefix, suffix: f.suffix, padding: String(f.padding), nextNumber: String(f.nextNumber) });
  };

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">{tx('Document Numbering')}</h1>
        <p className="text-sm text-muted-foreground">
          {tx('Prefix, suffix and length of document numbers. {YYYY}, {YY} and {MM} fill in the date. Numbers already issued do not change, and a counter can only move forward.')}
        </p>
      </div>

      {list.isLoading && <Skeleton className="h-48 w-full" />}
      {list.isError && <p className="text-sm text-destructive">{errorText(list.error)}</p>}

      {groups.map(([group, formats]) => (
        <Card key={group}>
          <CardHeader><CardTitle className="text-base">{group}</CardTitle></CardHeader>
          <CardContent className="divide-y">
            {formats.map((f) => (
              <div key={f.key} className="flex flex-wrap items-center justify-between gap-2 py-2">
                <div className="min-w-0">
                  <p className="text-sm font-medium">{f.label}</p>
                  <p className="font-mono text-xs text-muted-foreground">{f.preview}</p>
                </div>
                <div className="flex items-center gap-2">
                  {f.customized && <Badge variant="secondary">{tx('Custom')}</Badge>}
                  {canManage && <Button size="sm" variant="outline" onClick={() => open(f)}>{tx('Edit')}</Button>}
                </div>
              </div>
            ))}
          </CardContent>
        </Card>
      ))}

      <Dialog open={editing !== null} onOpenChange={(o) => { if (!o) setEditing(null); }}>
        <DialogContent>
          <DialogHeader><DialogTitle>{editing?.label}</DialogTitle></DialogHeader>
          <div className="grid gap-3 sm:grid-cols-2">
            <div className="space-y-1.5">
              <Label>{tx('Prefix')}</Label>
              <Input value={form.prefix} onChange={(e) => setForm({ ...form, prefix: e.target.value })} />
            </div>
            <div className="space-y-1.5">
              <Label>{tx('Suffix')}</Label>
              <Input value={form.suffix} onChange={(e) => setForm({ ...form, suffix: e.target.value })} />
            </div>
            <div className="space-y-1.5">
              <Label>{tx('Digits')}</Label>
              <Input type="number" min={1} max={12} value={form.padding} onChange={(e) => setForm({ ...form, padding: e.target.value })} />
            </div>
            <div className="space-y-1.5">
              <Label>{tx('Next number')}</Label>
              <Input type="number" min={1} value={form.nextNumber} onChange={(e) => setForm({ ...form, nextNumber: e.target.value })} />
            </div>
          </div>
          <DialogFooter>
            {editing?.customized && (
              <Button variant="ghost" disabled={reset.isPending} onClick={() => reset.mutate(editing.key)}>{tx('Use default')}</Button>
            )}
            <Button variant="outline" onClick={() => setEditing(null)}>{tx('Cancel')}</Button>
            <Button disabled={save.isPending} onClick={() => save.mutate()}>{tx('Save')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
