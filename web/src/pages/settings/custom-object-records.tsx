import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { CustomFieldInput } from '@/features/customization/CustomFieldInputs';
import {
  type CustomRecord,
  useCustomObject,
  useCustomRecords,
  useDeleteCustomRecord,
  useSaveCustomRecord,
} from '@/features/customization/api';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';

/** Records of one custom object: list, search, create, edit, delete (ADR-S029 §2). */
export default function CustomObjectRecordsPage() {
  const { key = '' } = useParams();
  const canWrite = useAuthStore((s) => s.hasPermission)(PERMISSIONS.customRecord.write);
  const { data: object, isLoading } = useCustomObject(key);
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(1);
  const { data: records } = useCustomRecords(key, search, page);
  const save = useSaveCustomRecord(key);
  const remove = useDeleteCustomRecord(key);
  const [editing, setEditing] = useState<CustomRecord | 'new' | null>(null);
  const [draft, setDraft] = useState<Record<string, unknown>>({});

  if (isLoading || !object) return <Skeleton className="h-40 w-full" />;
  const fields = object.fields ?? [];
  const listed = fields.filter((f) => f.showInList).slice(0, 4);
  const columns = listed.length ? listed : fields.slice(0, 3);
  const totalPages = records?.meta.totalPages ?? 1;

  const open = (r: CustomRecord | 'new') => {
    setEditing(r);
    setDraft(r === 'new' ? {} : { ...r.values });
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h1 className="text-2xl font-semibold">{object.pluralLabel}</h1>
          {object.description && <p className="text-sm text-muted-foreground">{object.description}</p>}
        </div>
        <div className="flex gap-2">
          <Input placeholder={tx('Search')} value={search} onChange={(e) => { setSearch(e.target.value); setPage(1); }}
            className="w-56" />
          {canWrite && fields.length > 0 && <Button onClick={() => open('new')}>{tx('New record')}</Button>}
        </div>
      </div>
      {fields.length === 0 && (
        <p className="text-sm text-muted-foreground">{tx('This object has no fields yet. Add them under Custom fields & objects.')}</p>
      )}
      {editing && (
        <Card>
          <CardHeader>
            <CardTitle className="text-base">{editing === 'new' ? tx('New record') : (editing.title ?? tx('Record'))}</CardTitle>
          </CardHeader>
          <CardContent>
            <form className="grid gap-3 md:grid-cols-2" onSubmit={(e) => {
              e.preventDefault();
              const values: Record<string, unknown> = {};
              fields.forEach((f) => { if (draft[f.key] !== undefined) values[f.key] = draft[f.key]; });
              save.mutate({ id: editing === 'new' ? undefined : editing.id, values }, { onSuccess: () => setEditing(null) });
            }}>
              {fields.map((f) => (
                <CustomFieldInput key={f.key} field={f} value={draft[f.key]} disabled={!canWrite}
                  onChange={(v) => setDraft((d) => ({ ...d, [f.key]: v }))} />
              ))}
              <div className="flex gap-2 md:col-span-2">
                <Button type="submit" disabled={save.isPending}>{tx('Save')}</Button>
                <Button type="button" variant="outline" onClick={() => setEditing(null)}>{tx('Cancel')}</Button>
              </div>
            </form>
          </CardContent>
        </Card>
      )}
      <Card>
        <CardContent className="pt-6">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{tx('Title')}</TableHead>
                {columns.map((c) => <TableHead key={c.key}>{c.label}</TableHead>)}
                <TableHead />
              </TableRow>
            </TableHeader>
            <TableBody>
              {(records?.data ?? []).map((r) => (
                <TableRow key={r.id} className="cursor-pointer" onClick={() => open(r)}>
                  <TableCell>{r.title ?? '—'}</TableCell>
                  {columns.map((c) => {
                    const v = r.values[c.key];
                    return <TableCell key={c.key}>{Array.isArray(v) ? v.join(', ') : v === undefined || v === null ? '' : String(v)}</TableCell>;
                  })}
                  <TableCell className="text-right">
                    {canWrite && (
                      <Button size="sm" variant="ghost" onClick={(e) => { e.stopPropagation(); remove.mutate(r.id); }}>
                        {tx('Delete')}
                      </Button>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          {totalPages > 1 && (
            <div className="mt-3 flex items-center justify-end gap-2 text-sm">
              <Button size="sm" variant="outline" disabled={page <= 1} onClick={() => setPage(page - 1)}>{tx('Previous')}</Button>
              <span>{page} / {totalPages}</span>
              <Button size="sm" variant="outline" disabled={page >= totalPages} onClick={() => setPage(page + 1)}>{tx('Next')}</Button>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
