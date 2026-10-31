import { useState } from 'react';
import { Database, Download } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { DateRangeFilter, ListPageHeader, ListToolbar } from '@/components/list';
import { tx } from '@/lib/i18n/tx';
import { apiMessage, notify } from '@/lib/notify';
import { downloadDataset, useBiDatasets, type BiDataset } from '@/features/alerts/api';

/** The read-only reporting datasets as CSV, for spreadsheets and BI tools (docs/integrations.md). */
export function BiExportsPage() {
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [busy, setBusy] = useState('');
  const { data, isLoading, isError } = useBiDatasets();
  const onDownload = (d: BiDataset) => {
    setBusy(d.key);
    downloadDataset(d.key, { from: from || undefined, to: to || undefined })
      .catch((e) => notify.error(apiMessage(e, tx('Could not download the file'))))
      .finally(() => setBusy(''));
  };
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Database} title={tx('BI Exports')}
        description={tx('Read-only datasets as CSV. Without dates you get this month so far.')} />
      <ListToolbar>
        <DateRangeFilter from={from} to={to} onChange={(f, t) => { setFrom(f); setTo(t); }} />
      </ListToolbar>
      {isLoading && <p className="text-muted-foreground">{tx('Loading…')}</p>}
      {isError && <p className="text-destructive">{tx('Could not load the datasets.')}</p>}
      <div className="grid gap-3 md:grid-cols-2">
        {(data ?? []).map((d) => (
          <Card key={d.key}>
            <CardContent className="flex items-center justify-between gap-3 p-4">
              <div className="min-w-0">
                <p className="font-medium">{d.label}</p>
                <p className="truncate text-xs text-muted-foreground">{d.view} · {d.columns.length} {tx('columns')}</p>
                {!d.dateColumn && <p className="text-xs text-muted-foreground">{tx('Snapshot: dates do not apply.')}</p>}
              </div>
              <Button size="sm" variant="outline" disabled={busy === d.key} onClick={() => onDownload(d)}>
                <Download className="mr-1.5 h-4 w-4" />CSV
              </Button>
            </CardContent>
          </Card>
        ))}
      </div>
    </div>
  );
}
