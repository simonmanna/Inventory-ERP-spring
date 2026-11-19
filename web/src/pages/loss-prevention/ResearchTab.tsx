import { useState } from 'react';
import { Scissors } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@/components/ui/dialog';
import { DataTable, type Column } from '@/components/data-table';
import { DataTablePagination, DateRangeFilter, FilterSelect, ListCard, ListToolbar } from '@/components/list';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { apiMessage, notify } from '@/lib/notify';
import {
  ACTIVITY_LABEL_RE, MAX_REVIEW_NOTE, VERDICTS, VERDICT_FILTERS, useLpActivities, useLpActivitySummary,
  useReviewLpActivity, type Activity, type ActivitySummaryRow, type Verdict,
} from '@/features/loss-prevention/research-api';
import { useLpZones } from '@/features/loss-prevention/vision-api';
import { TABLE, ZONE_LABELS, clipLength, labelOf, words } from './vision-ui';
import { RangeClipForm } from './EvidenceTab';
import { num, options, pct } from './lp-format';

const VERDICT_LABELS: Record<string, string> = {
  unreviewed: tx('Not reviewed'), correct: tx('Correct'), incorrect: tx('Incorrect'), unsure: tx('Unsure'),
};
const VERDICT_VARIANT: Record<string, 'default' | 'secondary' | 'destructive' | 'outline'> = {
  correct: 'default', incorrect: 'destructive', unsure: 'secondary',
};

/**
 * Activity recognition research (LP7, shadow mode). The agent reports what its recognizers see in zones marked for
 * research; reviews say whether they were right, which measures each recognizer. Nothing here makes a finding or a
 * case, and an activity says nothing about who was there.
 */
export function ResearchTab({ canEvidence, canConfigure }: { canEvidence: boolean; canConfigure: boolean }) {
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [zoneId, setZoneId] = useState('');
  const [activity, setActivity] = useState('');
  const [recognizer, setRecognizer] = useState('');
  const [verdict, setVerdict] = useState('');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(25);
  const [reviewing, setReviewing] = useState<Activity | null>(null);
  const period = { from: from || undefined, to: to || undefined };
  const list = useLpActivities({
    ...period, zoneId: zoneId || undefined, activity: activity || undefined, recognizer: recognizer || undefined,
    verdict: verdict || undefined, page, pageSize,
  });
  const summary = useLpActivitySummary(period);
  // The zone list needs lp:configure; without it the zones come from the activities shown.
  const zones = useLpZones({ includeRetired: 'true' }, canConfigure);
  const rows = list.data?.data ?? [];
  const summaryRows = summary.data?.rows ?? [];
  const zoneNames = new Map<string, string>();
  for (const z of zones.data ?? []) zoneNames.set(z.id, z.name);
  for (const r of rows) if (!zoneNames.has(r.zoneId)) zoneNames.set(r.zoneId, r.zoneName ?? r.zoneId);
  if (zoneId && !zoneNames.has(zoneId)) zoneNames.set(zoneId, zoneId);
  const reset = () => setPage(1);

  const columns: Column<Activity>[] = [
    {
      key: 'startedAt', header: tx('When'), render: (a) => (
        <div>
          <p>{dateTime(a.startedAt)}</p>
          <p className="text-xs text-muted-foreground">{clipLength(a.startedAt, a.endedAt)}</p>
        </div>
      ),
    },
    {
      key: 'zone', header: tx('Zone'), render: (a) => (
        <div>
          <p>{a.zoneName ?? a.zoneId}</p>
          {a.zoneType && <p className="text-xs text-muted-foreground">{labelOf(ZONE_LABELS, a.zoneType)}</p>}
        </div>
      ),
    },
    {
      key: 'activity', header: tx('Activity'), render: (a) => (
        <div>
          <p>{words(a.activity)}</p>
          <p className="font-mono text-xs text-muted-foreground">{a.recognizer}</p>
        </div>
      ),
    },
    { key: 'score', header: tx('Score'), className: 'text-right', render: (a) => num(a.score) },
    {
      key: 'review', header: tx('Review'), render: (a) => (
        <div className="flex flex-wrap items-center gap-1">
          {a.review ? (
            <Badge variant={VERDICT_VARIANT[a.review.verdict] ?? 'outline'}>
              {labelOf(VERDICT_LABELS, a.review.verdict)}{a.review.label ? ` · ${words(a.review.label)}` : ''}
            </Badge>
          ) : <Badge variant="outline">{tx('Not reviewed')}</Badge>}
          {a.reviews > 1 && <span className="text-xs text-muted-foreground">{a.reviews} {tx('reviews')}</span>}
        </div>
      ),
    },
    {
      key: 'actions', header: '', render: (a) => (
        <Button size="sm" variant="outline" onClick={(e) => { e.stopPropagation(); setReviewing(a); }}>{tx('Review')}</Button>
      ),
    },
  ];
  const summaryColumns: Column<ActivitySummaryRow>[] = [
    { key: 'recognizer', header: tx('Recognizer'), render: (r) => <span className="font-mono text-xs">{r.recognizer}</span> },
    { key: 'activity', header: tx('Activity'), render: (r) => words(r.activity) },
    { key: 'events', header: tx('Reported'), className: 'text-right', render: (r) => r.events },
    { key: 'reviewed', header: tx('Reviewed'), className: 'text-right', render: (r) => r.reviewed },
    { key: 'correct', header: tx('Correct'), className: 'text-right', render: (r) => r.correct },
    { key: 'incorrect', header: tx('Incorrect'), className: 'text-right', render: (r) => r.incorrect },
    { key: 'unsure', header: tx('Unsure'), className: 'text-right', render: (r) => r.unsure },
    { key: 'precision', header: tx('Precision'), className: 'text-right', render: (r) => pct(r.precision) },
  ];

  return (
    <>
      <p className="text-sm text-muted-foreground">
        {tx('Research, in shadow mode. In zones marked for activity research the agent reports what its recognizers see; your reviews measure how often each recognizer is right. Activities never make a finding or a case, and they say nothing about who was there.')}
      </p>
      <ListToolbar>
        <DateRangeFilter from={from} to={to} onChange={(f, t) => { setFrom(f); setTo(t); reset(); }} />
        <FilterSelect value={zoneId} onChange={(v) => { setZoneId(v); reset(); }} allLabel={tx('All zones')}
          options={[...zoneNames].map(([value, label]) => ({ value, label }))} />
        <FilterSelect value={activity} onChange={(v) => { setActivity(v); reset(); }} allLabel={tx('All activities')}
          options={options([...summaryRows.map((r) => r.activity), ...rows.map((r) => r.activity)], activity, words)} />
        <FilterSelect value={recognizer} onChange={(v) => { setRecognizer(v); reset(); }} allLabel={tx('All recognizers')}
          options={options([...summaryRows.map((r) => r.recognizer), ...rows.map((r) => r.recognizer)], recognizer)} />
        <FilterSelect value={verdict} onChange={(v) => { setVerdict(v); reset(); }} allLabel={tx('Any review')}
          options={VERDICT_FILTERS.map((v) => ({ value: v, label: labelOf(VERDICT_LABELS, v) }))} />
      </ListToolbar>
      <ListCard>
        <div className="overflow-x-auto">
          <DataTable columns={columns} data={rows} loading={list.isLoading} loadingRows={6} getRowId={(a) => a.eventId}
            {...TABLE} onRowClick={(a) => setReviewing(a)}
            emptyMessage={list.isError ? tx('Could not load the activities. Try again.')
              : tx('No activities here. The agent reports them only for zones marked for activity research.')} />
        </div>
      </ListCard>
      {list.data?.meta && (
        <DataTablePagination page={list.data.meta.page} pageSize={pageSize} total={list.data.meta.total}
          totalPages={list.data.meta.totalPages} onPageChange={setPage} onPageSizeChange={setPageSize} noun="event" />
      )}
      <p className="text-sm font-medium">{tx('How often each recognizer is right')}</p>
      <p className="text-xs text-muted-foreground">
        {tx('Precision is correct ÷ (correct + incorrect) over the reviewed activities; unsure reviews are left out. It is empty until something is reviewed.')}
      </p>
      <ListCard>
        <div className="overflow-x-auto">
          <DataTable columns={summaryColumns} data={summaryRows} loading={summary.isLoading} loadingRows={3}
            getRowId={(r) => `${r.recognizer}|${r.activity}`} {...TABLE}
            emptyMessage={summary.isError ? tx('Could not load the summary. Try again.') : tx('No activities in this period.')} />
        </div>
      </ListCard>
      {(summary.data?.relabels.length ?? 0) > 0 && (
        <div className="space-y-1 rounded-md border p-3 text-sm">
          <p className="font-medium">{tx('What reviewers said it really was')}</p>
          {summary.data?.relabels.map((r) => (
            <p key={`${r.recognizer}|${r.activity}|${r.label}`} className="text-muted-foreground">
              <span className="font-mono text-xs">{r.recognizer}</span> · {words(r.activity)} → {words(r.label)} ({r.count})
            </p>
          ))}
        </div>
      )}
      {summary.data?.note && <p className="text-xs text-muted-foreground">{summary.data.note}</p>}
      {reviewing && (
        <ReviewDialog activity={reviewing} canEvidence={canEvidence} onClose={() => setReviewing(null)} />
      )}
    </>
  );
}

/** One activity: what was reported, the latest review, a new review, and a clip of the moment from the buffer. */
function ReviewDialog({ activity: a, canEvidence, onClose }: {
  activity: Activity; canEvidence: boolean; onClose: () => void;
}) {
  const [verdict, setVerdict] = useState<Verdict | ''>('');
  const [label, setLabel] = useState('');
  const [note, setNote] = useState('');
  const [asking, setAsking] = useState(false);
  const review = useReviewLpActivity();
  const labelText = label.trim();
  const problem = !verdict ? tx('Choose a verdict.')
    : verdict === 'incorrect' && labelText && !ACTIVITY_LABEL_RE.test(labelText)
      ? tx('A label is 1 to 64 characters: lowercase letters, digits or _, starting with a letter.')
      : note.length > MAX_REVIEW_NOTE ? tx('The note holds at most 1000 characters.') : null;
  const onSave = () => {
    if (!verdict) return;
    review.mutate({
      eventId: a.eventId,
      body: {
        verdict,
        label: verdict === 'incorrect' && labelText ? labelText : undefined,
        note: note.trim() || undefined,
      },
    }, {
      onSuccess: () => { notify.success(tx('Review saved')); onClose(); },
      onError: (e) => notify.error(apiMessage(e, tx('Could not save the review'))),
    });
  };
  const started = new Date(a.startedAt).getTime();
  const ended = new Date(a.endedAt).getTime();
  const fact = (name: string, value: string) => (
    <p><span className="text-muted-foreground">{name}: </span>{value}</p>
  );
  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent className="max-h-[90vh] max-w-2xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{words(a.activity)}</DialogTitle>
          <DialogDescription>
            {tx('Say whether the recognizer was right. Reviews are kept; the latest one counts. A review is research only and never opens a case.')}
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-4">
          <div className="grid gap-1 text-sm sm:grid-cols-2">
            {fact(tx('Zone'), `${a.zoneName ?? a.zoneId}${a.zoneType ? ` · ${labelOf(ZONE_LABELS, a.zoneType)}` : ''}`)}
            {fact(tx('Recognizer'), a.recognizer)}
            {fact(tx('From'), dateTime(a.startedAt))}
            {fact(tx('To'), `${dateTime(a.endedAt)} (${clipLength(a.startedAt, a.endedAt)})`)}
            {fact(tx('Score'), num(a.score))}
            {fact(tx('Clip kept by the agent'), a.clipId ? tx('Yes, on the edge box') : tx('No'))}
          </div>
          {a.review && (
            <div className="space-y-1 rounded-md border p-3 text-sm">
              <p className="font-medium">
                {tx('Latest review')}{a.reviews > 1 ? ` (${a.reviews} ${tx('reviews')})` : ''}
              </p>
              <div className="flex flex-wrap items-center gap-2">
                <Badge variant={VERDICT_VARIANT[a.review.verdict] ?? 'outline'}>{labelOf(VERDICT_LABELS, a.review.verdict)}</Badge>
                {a.review.label && <span>{tx('Really')}: {words(a.review.label)}</span>}
                <span className="text-xs text-muted-foreground">
                  {a.review.reviewerName ?? a.review.reviewedBy ?? ''} · {dateTime(a.review.createdAt)}
                </span>
              </div>
              {a.review.note && <p className="text-muted-foreground">{a.review.note}</p>}
            </div>
          )}
          <div className="space-y-1">
            <Label>{tx('Verdict')}</Label>
            <div className="flex flex-wrap gap-2">
              {VERDICTS.map((v) => (
                <Button key={v} type="button" size="sm" variant={verdict === v ? 'default' : 'outline'}
                  onClick={() => setVerdict(v)}>
                  {labelOf(VERDICT_LABELS, v)}
                </Button>
              ))}
            </div>
          </div>
          {verdict === 'incorrect' && (
            <div className="space-y-1">
              <Label>{tx('What it really was (optional)')}</Label>
              <Input value={label} maxLength={64} onChange={(e) => setLabel(e.target.value)}
                placeholder={tx('e.g. item_scanned')} />
              <p className="text-xs text-muted-foreground">
                {tx('Lowercase letters, digits and _, as the activities are named. Leave empty when it was nothing in particular.')}
              </p>
            </div>
          )}
          <div className="space-y-1">
            <Label>{tx('Note (optional)')}</Label>
            <Textarea value={note} maxLength={MAX_REVIEW_NOTE} onChange={(e) => setNote(e.target.value)}
              placeholder={tx('What the video shows')} />
          </div>
          {canEvidence && a.cameraId && (
            <div className="space-y-2">
              <Button size="sm" variant="outline" onClick={() => setAsking((v) => !v)}>
                <Scissors className="mr-1.5 h-4 w-4" />{asking ? tx('Cancel') : tx('Clip from the buffer')}
              </Button>
              {asking && (
                <div className="rounded-md border p-3">
                  <RangeClipForm cameras={[{ id: a.cameraId, name: a.zoneName ?? a.cameraId }]} defaultCameraId={a.cameraId}
                    defaultFrom={Number.isNaN(started) ? undefined : new Date(started - 10_000)}
                    defaultTo={Number.isNaN(ended) ? undefined : new Date(ended + 10_000)}
                    defaultReason={`${tx('Activity research')}: ${a.activity}`} onDone={() => setAsking(false)} />
                </div>
              )}
            </div>
          )}
          {problem && verdict && <p className="text-xs text-destructive">{problem}</p>}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Close window')}</Button>
          <Button disabled={!!problem || review.isPending} onClick={onSave}>{tx('Save review')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
