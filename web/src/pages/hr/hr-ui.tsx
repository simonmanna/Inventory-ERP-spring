import type { ReactNode } from 'react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import type { Row } from '@/features/hr/hr-pro-api';
import { notify } from '@/lib/notify';
import { apiErrorMessage } from '@/lib/api-error';
import { tx } from '@/lib/i18n/tx';

/** Small building blocks shared by the HP-1…HP-10 HR screens. */

export interface Col {
  key: string;
  label: string;
  align?: 'right';
  render?: (r: Row) => ReactNode;
}

export function DataTable({ cols, rows, loading, empty, onRow, selectedId }: {
  cols: Col[];
  rows: Row[] | undefined;
  loading?: boolean;
  empty: string;
  onRow?: (r: Row) => void;
  selectedId?: string;
}) {
  if (loading) return <Skeleton className="h-24 w-full" />;
  if (!rows?.length) return <p className="py-6 text-center text-sm text-muted-foreground">{empty}</p>;
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b text-left text-xs uppercase text-muted-foreground">
            {cols.map((c) => (
              <th key={c.key} className={`py-2 pr-3 ${c.align === 'right' ? 'text-right' : ''}`}>{c.label}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((r, i) => (
            <tr
              key={String(r.id ?? r.employeeId ?? i)}
              className={`border-b last:border-0 ${onRow ? 'cursor-pointer hover:bg-muted/40' : ''} ${selectedId && selectedId === r.id ? 'bg-muted/50' : ''}`}
              onClick={onRow ? () => onRow(r) : undefined}
            >
              {cols.map((c) => (
                <td key={c.key} className={`py-1.5 pr-3 ${c.align === 'right' ? 'text-right tabular-nums' : ''}`}>
                  {c.render ? c.render(r) : String(r[c.key] ?? '—')}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function Section({ title, actions, children }: { title: string; actions?: ReactNode; children: ReactNode }) {
  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between space-y-0">
        <CardTitle className="text-base">{title}</CardTitle>
        {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
      </CardHeader>
      <CardContent className="space-y-3">{children}</CardContent>
    </Card>
  );
}

export function Field({ label, children, htmlFor }: { label: string; children: ReactNode; htmlFor?: string }) {
  return (
    <div className="grid gap-1.5">
      <Label htmlFor={htmlFor}>{label}</Label>
      {children}
    </div>
  );
}

export const selectClass = 'h-9 rounded-md border bg-background px-3 text-sm';

export function PageHeader({ title, subtitle }: { title: string; subtitle: string }) {
  return (
    <div>
      <h1 className="text-xl font-semibold">{title}</h1>
      <p className="text-sm text-muted-foreground">{subtitle}</p>
    </div>
  );
}

/** Run a mutation with a success toast and the server's error message on failure. */
export function done(success: string, failure?: string) {
  return {
    onSuccess: () => notify.success(success),
    onError: (err: unknown) => notify.error(apiErrorMessage(err, failure ?? tx('The action failed'))),
  };
}
